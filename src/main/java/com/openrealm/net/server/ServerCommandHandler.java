package com.openrealm.net.server;

import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.openrealm.account.dto.AccountDto;
import com.openrealm.account.dto.AccountProvision;
import com.openrealm.account.dto.AccountSubscription;
import com.openrealm.account.dto.CharacterDto;
import com.openrealm.account.dto.ChestDto;
import com.openrealm.account.dto.PlayerAccountDto;
import com.openrealm.net.test.StressTestClient;
import com.openrealm.game.contants.EntityType;
import com.openrealm.game.contants.GlobalConstants;
import com.openrealm.game.contants.LootTier;
import com.openrealm.game.contants.StatusEffectType;
import com.openrealm.game.contants.TextEffect;
import com.openrealm.game.data.GameDataLookup;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.entity.Player;
import com.openrealm.game.entity.Portal;
import com.openrealm.game.entity.item.AttributeModifier;
import com.openrealm.game.entity.item.Enchantment;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.entity.item.LootContainer;
import com.openrealm.game.entity.item.Rarity;
import com.openrealm.game.entity.Enemy;
import com.openrealm.game.math.Vector2f;
import java.util.Random;
import com.openrealm.game.contants.CharacterClass;
import com.openrealm.game.model.CharacterClassModel;
import com.openrealm.game.model.DungeonGraphNode;
import com.openrealm.game.model.MapModel;
import com.openrealm.game.model.PortalModel;
import com.openrealm.game.model.RealmEventModel;
import com.openrealm.game.tile.Tile;
import com.openrealm.net.messaging.CommandType;
import com.openrealm.net.messaging.ServerCommandMessage;
import com.openrealm.net.client.packet.LoadPacket;
import com.openrealm.net.client.packet.UnloadPacket;
import com.openrealm.net.client.packet.UpdatePacket;
import com.openrealm.net.entity.NetBullet;
import com.openrealm.net.entity.NetEnemy;
import com.openrealm.net.entity.NetLootContainer;
import com.openrealm.net.entity.NetPlayer;
import com.openrealm.net.entity.NetPortal;
import com.openrealm.net.party.PartyManager;
import com.openrealm.net.realm.Realm;
import com.openrealm.net.realm.RealmManagerServer;
import com.openrealm.net.realm.RealmOverseer;
import com.openrealm.net.server.packet.CommandPacket;
import com.openrealm.net.server.packet.TextPacket;
import com.openrealm.util.AdminRestrictedCommand;
import com.openrealm.util.CommandHandler;
import com.openrealm.util.GameObjectUtils;
import com.openrealm.util.WorkerThread;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ServerCommandHandler {
    public static final Map<String, MethodHandle> COMMAND_CALLBACKS = new HashMap<>();
    public static final Map<String, CommandHandler> COMMAND_DESCRIPTIONS = new HashMap<>();
    public static final Map<String, AccountProvision[]> RESTRICTED_COMMAND_PROVISIONS = new HashMap<>();
    public static final Map<Long, List<AccountProvision>> PLAYER_PROVISION_CACHE = new HashMap<>();
    private static final List<StressTestClient> ACTIVE_BOTS = new ArrayList<>();
    private static final List<String> BOT_ACCOUNT_GUIDS = new ArrayList<>();
    
    public static void invokeCommand(RealmManagerServer mgr, CommandPacket command) throws Exception {
        final ServerCommandMessage message = CommandType.fromPacket(command);
        final long fromPlayerId = mgr.getRemoteAddresses().get(command.getSrcIp());
        final Realm playerRealm = mgr.findPlayerRealm(fromPlayerId);
        if (playerRealm == null) {
            log.warn("Command '{}' from player {} ignored — player not in any realm", message.getCommand(), fromPlayerId);
            return;
        }
        final Player fromPlayer = playerRealm.getPlayer(fromPlayerId);
        if (fromPlayer == null) {
            log.warn("Command '{}' from player {} ignored — player not found in realm", message.getCommand(), fromPlayerId);
            return;
        }
        try {
        	final String cmdLower = message.getCommand().toLowerCase();
        	final AccountProvision[] requiredProvisions = RESTRICTED_COMMAND_PROVISIONS.get(cmdLower);
        	if (requiredProvisions != null) {
        	    // /admin itself bypasses the toggle so user can re-enable from disabled state.
        	    if (!fromPlayer.isAdminModeEnabled() && !"admin".equals(cmdLower)) {
        	        throw new IllegalStateException(
        	            "Admin mode is OFF — type /admin to re-enable");
        	    }
        	    List<AccountProvision> held = PLAYER_PROVISION_CACHE.get(fromPlayer.getId());
        	    if (held == null) {
        	        log.info("Player {} invoking restricted command '{}' — fetching provisions", fromPlayer.getName(), message.getCommand());
        	        final AccountDto playerAccount = ServerGameLogic.DATA_SERVICE.executeGet("/admin/account/" + fromPlayer.getAccountUuid(), null, AccountDto.class);
        	        if (playerAccount == null) {
        	            throw new IllegalStateException("Failed to look up account for player " + fromPlayer.getName());
        	        }
        	        held = playerAccount.getAccountProvisions() != null ? playerAccount.getAccountProvisions() : new ArrayList<>();
        	        PLAYER_PROVISION_CACHE.put(fromPlayer.getId(), held);
        	    }
        	    if (!AccountProvision.checkAccess(held, requiredProvisions)) {
        	        throw new IllegalStateException(
        	            "Player " + fromPlayer.getName() + " lacks required provision for command /" + message.getCommand());
        	    }
        	}
            
            final MethodHandle methodHandle = COMMAND_CALLBACKS.get(message.getCommand().toLowerCase());

            if (methodHandle == null) {
                sendCommandError(mgr, fromPlayer, 501,
                        "Unknown command /" + message.getCommand());
            } else {
                methodHandle.invokeExact(mgr, fromPlayer, message);
            }
        } catch (Throwable e) {
            log.error("Failed to handle server command /{}. Reason: {}", message.getCommand(), e.getMessage());
            final String reason = (e.getMessage() != null) ? e.getMessage() : e.getClass().getSimpleName();
            sendCommandError(mgr, fromPlayer, 502, reason);
        }
    }

    private static void sendCommandError(RealmManagerServer mgr, Player fromPlayer, int code, String reason) {
        try {
            final CommandPacket errorResponse = CommandPacket.createError(fromPlayer, code, reason);
            mgr.enqueueServerPacket(fromPlayer, errorResponse);
        } catch (Exception ignored) {}
    }
    
	@CommandHandler(value = "op", description = "Promote a user to administrator. Or demote them back to a regular user")
	@AdminRestrictedCommand(provisions={AccountProvision.OPENREALM_SYS_ADMIN})
	public static void invokeOpUser(RealmManagerServer mgr, Player target, ServerCommandMessage message) {
		if (message.getArgs() == null || message.getArgs().size() < 1)
			throw new IllegalArgumentException("Usage: /op {PLAYER_NAME}");
		log.info("**Player OP** Player {} is promoting/demoting {} to/from server operator", target.getAccountUuid(),
				message.getArgs().get(0));
		try {
			final AccountDto callerAccount = ServerGameLogic.DATA_SERVICE
					.executeGet("/admin/account/" + target.getAccountUuid(), null, AccountDto.class);
			if (!callerAccount.isAdmin()) {
				throw new IllegalArgumentException("You are required to be a server operator to invoke this command");
			}
			final Player toOp = mgr.findPlayerByName(message.getArgs().get(0));
			if (toOp == null) {
				throw new IllegalArgumentException("Player " + message.getArgs().get(0) + " does not exist.");
			} else if (toOp.getAccountUuid().equals(target.getAccountUuid())) {
				throw new IllegalArgumentException("You cannot OP yourself. Idiot.");
			}

			final AccountDto targetAccount = ServerGameLogic.DATA_SERVICE
					.executeGet("/admin/account/" + toOp.getAccountUuid(), null, AccountDto.class);
			boolean removed = false;
			if (targetAccount.isAdmin()) {
				targetAccount.removeProvision(AccountProvision.OPENREALM_ADMIN);
				removed = true;
			} else {
				targetAccount.addProvision(AccountProvision.OPENREALM_ADMIN);
			}
			PLAYER_PROVISION_CACHE.remove(toOp.getId());
			ServerGameLogic.DATA_SERVICE.executePut("/admin/account", targetAccount,
					AccountDto.class);
			final String operation = " is " + (removed ? "no longer " : "now ");
			final String msg = "Player " + message.getArgs().get(0) + operation + "a server operator";
			mgr.enqueueServerPacket(target, TextPacket.from("SYSTEM", target.getName(), msg));
		} catch (Exception e) {
			throw new IllegalArgumentException("Failed to op user. Reason: " + e.getMessage());
		}
	}

    @CommandHandler(value="stat", description="Modify or max individual Player stats")
	@AdminRestrictedCommand(provisions={AccountProvision.OPENREALM_ADMIN})
    public static void invokeSetStats(RealmManagerServer mgr, Player target, ServerCommandMessage message) {
        if (message.getArgs() == null || message.getArgs().size() < 1)
            throw new IllegalArgumentException("Usage: /stat {STAT_NAME} {STAT_VALUE}");
        final short valueToSet = message.getArgs().get(0).equalsIgnoreCase("max") ? -1
                : Short.parseShort(message.getArgs().get(1));
        CharacterClassModel classModel = GameDataManager.CHARACTER_CLASSES.get(target.getClassId());
        log.info("Player {} set stat {} to {}", target.getName(), message.getArgs().get(0), valueToSet);
        switch (message.getArgs().get(0)) {
        case "hp":
            target.getStats().setHp(valueToSet);
            break;
        case "mp":
            target.getStats().setMp(valueToSet);
            break;
        case "str":
            target.getStats().setStr(valueToSet);
            break;
        case "def":
            target.getStats().setDef(valueToSet);
            break;
        case "spd":
            target.getStats().setSpd(valueToSet);
            break;
        case "dex":
            target.getStats().setDex(valueToSet);
            break;
        case "vit":
            target.getStats().setVit(valueToSet);
            break;
        case "wis":
            target.getStats().setWis(valueToSet);
            break;
        case "max":
            target.setStats(classModel.getMaxStats());
            break;
        default:
            throw new IllegalArgumentException("Unknown stat " + message.getArgs().get(0));
        }
    }

    @CommandHandler(value="testplayers", description="Spawns a variable number of headless test players at the user")
	@AdminRestrictedCommand(provisions={AccountProvision.OPENREALM_SYS_ADMIN})
    public static void invokeSpawnTest(RealmManagerServer mgr, Player target, ServerCommandMessage message)
            throws Exception {
        if (message.getArgs() == null || message.getArgs().size() != 1)
            throw new IllegalArgumentException("Usage: /testplayers {COUNT}");
        final Realm playerRealm = mgr.findPlayerRealm(target.getId());
        log.info("Player {} spawn {} players  at {}", target.getName(), message.getArgs().get(0), target.getPos());
        mgr.spawnTestPlayers(playerRealm.getRealmId(), Integer.parseInt(message.getArgs().get(0)),
                target.getPos().clone());
    }
    
    @CommandHandler(value="about", description="Get server info")
    public static void invokeAbout(RealmManagerServer mgr, Player target, ServerCommandMessage message)
            throws Exception {
        final List<String> text = Arrays.asList(
                "OpenRealm Server " + ServerGameLogic.GAME_VERSION,
                "Players connected: " + mgr.getRealms().values().stream().map(realm -> realm.getPlayers().size()).collect(Collectors.summingInt(count -> count)),
                "Players in my realm: " + mgr.findPlayerRealm(target.getId()).getPlayers().size());
        mgr.enqueChunkedText(target, text);
        log.info("Player {} request command about.", target.getName());
    }

    @CommandHandler(value="pos", description="Show current world position")
    public static void invokePos(RealmManagerServer mgr, Player target, ServerCommandMessage message)
            throws Exception {
        final Vector2f pos = target.getPos();
        final int tileX = (int) (pos.x / 32);
        final int tileY = (int) (pos.y / 32);
        final Realm realm = mgr.findPlayerRealm(target.getId());
        final String realmInfo = realm != null
                ? String.format("Realm %d (map %d)", realm.getRealmId(), realm.getMapId())
                : "Unknown";
        final List<String> text = Arrays.asList(
                String.format("Position: %.1f, %.1f", pos.x, pos.y),
                String.format("Tile: %d, %d", tileX, tileY),
                realmInfo);
        mgr.enqueChunkedText(target, text);
    }

    @CommandHandler(value="tile", description="Change all tiles in the viewport to the provided tile ID")
	@AdminRestrictedCommand(provisions={AccountProvision.OPENREALM_ADMIN})
    public static void invokeSetTile(RealmManagerServer mgr, Player target, ServerCommandMessage message)
            throws Exception {
        final Short newTileId = Short.parseShort(message.getArgs().get(0));
        final Vector2f playerPos = target.getPos();
        final Realm playerRealm = mgr.findPlayerRealm(target.getId());
        final Tile[] toModify = playerRealm.getTileManager().getBaseTiles(playerPos);
        for(Tile tile : toModify) {
            if(tile==null) continue;
            tile.setTileId(newTileId);
        }
        log.info("Player {} request command tile.", target.getName());
    }

    @CommandHandler(value="help", description="This command")
    public static void invokeHelp(RealmManagerServer mgr, Player target, ServerCommandMessage message)
            throws Exception {
        String commandHelpText = "Available Commands:   ";
        TextPacket commandHelp = TextPacket.from("SYSTEM", target.getName(), commandHelpText);
        mgr.enqueueServerPacket(target, commandHelp);
        for (String commandHandlerKey : COMMAND_CALLBACKS.keySet()) {
            final CommandHandler handler = COMMAND_DESCRIPTIONS.get(commandHandlerKey);
            commandHelpText = "/" + commandHandlerKey + " - "+handler.description();
            commandHelp = TextPacket.from("SYSTEM", target.getName(), commandHelpText);
            mgr.enqueueServerPacket(target, commandHelp);
        }

        log.info("Player {} request command help.", commandHelp);
    }
    
    @CommandHandler(value="heal", description="Restores all Player health and mp")
	@AdminRestrictedCommand(provisions={AccountProvision.OPENREALM_MODERATOR})
    public static void invokePlayerHeal(RealmManagerServer mgr, Player target, ServerCommandMessage message)
            throws Exception {
        target.setHealth(target.getComputedStats().getHp());
        target.setMana(target.getComputedStats().getMp());
        log.info("Player {} healed themselves", target.getName());
    }

    @CommandHandler(value="cdreset", description="Admin: instantly reset all ability cooldowns on yourself.")
    @AdminRestrictedCommand(provisions={AccountProvision.OPENREALM_MODERATOR})
    public static void invokeCooldownReset(RealmManagerServer mgr, Player target, ServerCommandMessage message)
            throws Exception {
        final long[] cds = target.getAbilityCooldowns();
        if (cds != null) {
            for (int i = 0; i < cds.length; i++) cds[i] = 0L;
        }
        // Legacy single-ability gate — items flowing through playerAbilityState bypass the slot table.
        mgr.getPlayerAbilityState().remove(target.getId());
        target.setCurrentCast(null);
        log.info("Player {} reset all ability cooldowns", target.getName());
        mgr.enqueueServerPacket(target,
                TextPacket.from("SYSTEM", target.getName(), "Cooldowns reset"));
    }

    @CommandHandler(value="party",
        description="Party: /party invite {name} | /party accept | /party decline | /party leave | /party list")
    public static void invokeParty(RealmManagerServer mgr, Player target, ServerCommandMessage message)
            throws Exception {
        final List<String> args = message.getArgs() == null ? Collections.emptyList() : message.getArgs();
        if (args.isEmpty()) {
            replySystem(mgr, target, "Usage: /party invite {name} | accept | decline | leave | list");
            return;
        }
        final String sub = args.get(0).trim().toLowerCase();
        final PartyManager pm = mgr.getPartyManager();
        switch (sub) {
            case "invite": {
                if (args.size() < 2) { replySystem(mgr, target, "Usage: /party invite {name}"); return; }
                final Player other = mgr.getPlayerByName(args.get(1));
                if (other == null) { replySystem(mgr, target, "Player not found: " + args.get(1)); return; }
                final String err = pm.invite(target.getId(), other.getId());
                if (err != null) { replySystem(mgr, target, "Invite failed: " + err); return; }
                replySystem(mgr, target, "Invite sent to " + other.getName() + ".");
                replySystem(mgr, other, target.getName() + " invited you to a party. Type /party accept or /party decline.");
                mgr.broadcastPartyUpdate(pm.getPartyId(target.getId()));
                return;
            }
            case "accept": {
                final long pid = pm.accept(target.getId());
                if (pid == 0L) { replySystem(mgr, target, "No pending party invite (or it expired)."); return; }
                replySystem(mgr, target, "Joined party.");
                mgr.broadcastPartyUpdate(pid);
                return;
            }
            case "decline": {
                final long inviter = pm.decline(target.getId());
                if (inviter == 0L) { replySystem(mgr, target, "No pending party invite."); return; }
                replySystem(mgr, target, "Invite declined.");
                final Player inviterP = mgr.getPlayerById(inviter);
                if (inviterP != null) replySystem(mgr, inviterP, target.getName() + " declined your party invite.");
                // Tear down 1-person lobby if no other pending invites remain.
                final long disbanded = pm.disbandIfSoloWithNoPendingInvites(inviter);
                if (disbanded != 0L && inviterP != null) {
                    mgr.sendEmptyPartyUpdate(inviterP);
                }
                return;
            }
            case "leave": {
                final long pid = pm.getPartyId(target.getId());
                if (pid == 0L) { replySystem(mgr, target, "You are not in a party."); return; }
                final PartyManager.LeaveResult res = pm.leave(target.getId());
                replySystem(mgr, target, "You left the party.");
                mgr.sendEmptyPartyUpdate(target);
                if (res.evictedSurvivorId != 0L) {
                    // 2→1 dissolve: tell the lone survivor the party is gone.
                    final Player survivor = mgr.getPlayerById(res.evictedSurvivorId);
                    if (survivor != null) {
                        mgr.sendEmptyPartyUpdate(survivor);
                        replySystem(mgr, survivor, target.getName() + " left — your party has disbanded.");
                    }
                } else {
                    mgr.broadcastPartyUpdate(pid);  // remaining members (>=2)
                }
                return;
            }
            case "list": {
                final List<Long> roster = pm.getPartyMembers(target.getId());
                if (roster.isEmpty()) { replySystem(mgr, target, "You are not in a party."); return; }
                final StringBuilder sb = new StringBuilder("Party (" + roster.size() + "/" + PartyManager.MAX_PARTY_SIZE + "): ");
                boolean first = true;
                for (final Long id : roster) {
                    final Player p = mgr.getPlayerById(id);
                    if (p == null) continue;
                    if (!first) sb.append(", ");
                    sb.append(p.getName());
                    first = false;
                }
                replySystem(mgr, target, sb.toString());
                return;
            }
            default:
                replySystem(mgr, target, "Unknown /party subcommand: " + sub);
        }
    }

    private static void replySystem(RealmManagerServer mgr, Player to, String msg) throws Exception {
        mgr.enqueueServerPacket(to, TextPacket.from("SYSTEM", to.getName(), msg));
    }

    @CommandHandler(value="sp", description="Admin: grant N skill points. Usage: /sp {N} (omit N for +10)")
    @AdminRestrictedCommand(provisions={AccountProvision.OPENREALM_MODERATOR})
    public static void invokeGrantSkillPoints(RealmManagerServer mgr, Player target, ServerCommandMessage message)
            throws Exception {
        int amount = 10;
        if (message.getArgs() != null && !message.getArgs().isEmpty()) {
            try {
                amount = Integer.parseInt(message.getArgs().get(0).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Usage: /sp {N}  (N must be an integer; omit for +10)");
            }
        }
        if (amount == 0) return;
        target.setAvailableSkillPoints(Math.max(0, target.getAvailableSkillPoints() + amount));
        log.info("[SKILL-POINTS] /sp granted {} to {} (pool now {})",
                amount, target.getName(), target.getAvailableSkillPoints());
        mgr.enqueueServerPacket(target, UpdatePacket.from(target));
    }

    @CommandHandler(value="size", description="Temporarily resize your character. Usage: /size {PIXELS} (or /size reset)")
    @AdminRestrictedCommand(provisions={AccountProvision.OPENREALM_MODERATOR})
    public static void invokePlayerSize(RealmManagerServer mgr, Player target, ServerCommandMessage message)
            throws Exception {
        if (message.getArgs() == null || message.getArgs().size() != 1) {
            throw new IllegalArgumentException("Usage: /size {PIXELS} (or /size reset)");
        }
        final String arg = message.getArgs().get(0);
        final int newSize;
        if ("reset".equalsIgnoreCase(arg) || "default".equalsIgnoreCase(arg)) {
            newSize = GlobalConstants.PLAYER_SIZE;
        } else {
            newSize = Integer.parseInt(arg);
            if (newSize < 4 || newSize > 256) {
                throw new IllegalArgumentException("PIXELS must be between 4 and 256");
            }
        }
        target.setSize(newSize);
        // bounds is built at construction; refresh so collision matches the new size.
        if (target.getBounds() != null) {
            target.getBounds().setWidth(newSize);
            target.getBounds().setHeight(newSize);
        }
        log.info("Player {} resized themselves to {}px", target.getName(), newSize);
        mgr.enqueueServerPacket(target,
                TextPacket.from("SYSTEM", target.getName(),
                        "Resized to " + newSize + "px (logout to reset)"));
        // UpdatePacket doesn't carry size — broadcast LoadPacket so clients pick up the change.
        try {
            final Realm rebroadcastRealm = mgr.findPlayerRealm(target.getId());
            if (rebroadcastRealm != null) {
                final NetPlayer net = NetPlayer.fromPlayer(target);
                final LoadPacket resizeBroadcast =
                        new LoadPacket(
                                new NetPlayer[]{ net },
                                new NetEnemy[0],
                                new NetBullet[0],
                                new NetLootContainer[0],
                                new NetPortal[0],
                                (byte) 0);
                for (final Map.Entry<Long, Player> entry : rebroadcastRealm.getPlayers().entrySet()) {
                    final Player p = entry.getValue();
                    if (p == null || p.isHeadless()) continue;
                    mgr.enqueueServerPacket(p, resizeBroadcast);
                }
            }
        } catch (Exception ex) {
            log.warn("Failed to broadcast /size LoadPacket: {}", ex.getMessage());
        }
    }

    @CommandHandler(value="spawn", description="Spawn enemies by id OR name. Usage: /spawn {ENEMY_ID_OR_NAME} [COUNT]")
	@AdminRestrictedCommand(provisions={AccountProvision.OPENREALM_MODERATOR})
    public static void invokeEnemySpawn(RealmManagerServer mgr, Player target, ServerCommandMessage message)
            throws Exception {
        if (message.getArgs() == null || message.getArgs().isEmpty() || message.getArgs().size() > 2)
            throw new IllegalArgumentException("Usage: /spawn {ENEMY_ID_OR_NAME} [COUNT]");

        final int enemyId = GameDataLookup.resolveEnemyId(message.getArgs().get(0));
        int count = 1;
        if (message.getArgs().size() == 2) {
            count = Integer.parseInt(message.getArgs().get(1));
            if (count < 1) {
                throw new IllegalArgumentException("COUNT must be >= 1");
            }
            if (count > 5000) {
                throw new IllegalArgumentException("COUNT capped at 5000 per command");
            }
        }

        log.info("Player {} spawn enemy {} ×{} at {}",
                target.getName(), enemyId, count, target.getPos());
        final Realm from = mgr.findPlayerRealm(target.getId());
        if (from == null) {
            throw new IllegalArgumentException("No realm for player");
        }
        // Fixed disc radius regardless of count, with uniform-area distribution.
        final Random rng = new Random();
        final float baseX = target.getPos().x;
        final float baseY = target.getPos().y;
        final float SPAWN_RADIUS = 4f * GlobalConstants.BASE_TILE_SIZE; // ~4 tiles
        for (int i = 0; i < count; i++) {
            final float dx, dy;
            if (count == 1) {
                dx = 0f; dy = 0f;
            } else {
                final float angle = (float) (rng.nextFloat() * Math.PI * 2.0);
                final float r = SPAWN_RADIUS * (float) Math.sqrt(rng.nextFloat());
                dx = (float) (Math.cos(angle) * r);
                dy = (float) (Math.sin(angle) * r);
            }
            final Vector2f spawnPos = new Vector2f(baseX + dx, baseY + dy);
            final Enemy spawned = GameObjectUtils.getEnemyFromId(enemyId, spawnPos);
            if (spawned == null) {
                throw new IllegalArgumentException("Unknown enemy id: " + enemyId);
            }
            spawned.setAdminSpawned(true);
            from.addEnemy(spawned);
        }
        if (count > 1) {
            mgr.enqueueServerPacket(target,
                    TextPacket.from("SYSTEM", target.getName(),
                            "Spawned " + count + " of enemy " + enemyId));
        }
    }

    @CommandHandler(value="kill", description="Admin: kill all enemies within a radius. Usage: /kill {RADIUS_TILES}")
	@AdminRestrictedCommand(provisions={AccountProvision.OPENREALM_MODERATOR})
    public static void invokeKillEnemiesInRadius(RealmManagerServer mgr, Player target,
            ServerCommandMessage message) throws Exception {
        if (message.getArgs() == null || message.getArgs().size() != 1)
            throw new IllegalArgumentException("Usage: /kill {RADIUS_TILES}");

        final float radiusTiles = Float.parseFloat(message.getArgs().get(0));
        if (radiusTiles <= 0f)
            throw new IllegalArgumentException("RADIUS_TILES must be > 0");

        final Realm realm = mgr.findPlayerRealm(target.getId());
        if (realm == null)
            throw new IllegalArgumentException("No realm for player");

        final float radius = radiusTiles * GlobalConstants.BASE_TILE_SIZE;
        final float radiusSq = radius * radius;
        final Vector2f center = target.getPos();

        // Two-pass snapshot to avoid mutating enemies map mid-iteration.
        // Skip INVINCIBLE entities (static NPCs with permanentEffects:[6], no respawn path).
        final List<Enemy> toKill = new ArrayList<>();
        for (final Enemy e : realm.getEnemies().values()) {
            if (e == null || e.getDeath()) continue;
            if (e.hasEffect(StatusEffectType.INVINCIBLE)) continue;
            final float dx = e.getPos().x - center.x;
            final float dy = e.getPos().y - center.y;
            if (dx * dx + dy * dy <= radiusSq) {
                toKill.add(e);
            }
        }
        final Long[] killedIds = new Long[toKill.size()];
        for (int i = 0; i < toKill.size(); i++) {
            final Enemy e = toKill.get(i);
            killedIds[i] = e.getId();
            realm.getExpiredEnemies().add(e.getId());
            realm.removeEnemy(e);
        }

        // Explicit UnloadPacket so clients drop killed enemies this tick (not next ledger diff).
        if (killedIds.length > 0) {
            final UnloadPacket unload = UnloadPacket.from(
                new Long[0], new Long[0], killedIds, new Long[0], new Long[0]);
            for (final Player p : realm.getPlayers().values()) {
                mgr.enqueueServerPacket(p, unload);
            }
        }

        log.info("Player {} /kill: removed {} enemies within {} tiles at {}",
                target.getName(), toKill.size(), radiusTiles, center);
        mgr.enqueueServerPacket(target,
                TextPacket.from("SYSTEM", target.getName(),
                        "Killed " + toKill.size() + " enemies within " + radiusTiles + " tiles"));
    }

    @CommandHandler(value="clearspawn", description="Admin: remove every enemy in your realm that was created via /spawn (map-static NPCs untouched).")
    @AdminRestrictedCommand(provisions={AccountProvision.OPENREALM_MODERATOR})
    public static void invokeClearSpawn(RealmManagerServer mgr, Player target,
            ServerCommandMessage message) throws Exception {
        final Realm realm = mgr.findPlayerRealm(target.getId());
        if (realm == null)
            throw new IllegalArgumentException("No realm for player");

        // Filter strictly on adminSpawned flag — natural spawns and map NPCs survive.
        final List<Enemy> toClear = new ArrayList<>();
        for (final Enemy e : realm.getEnemies().values()) {
            if (e == null || e.getDeath()) continue;
            if (!e.isAdminSpawned()) continue;
            toClear.add(e);
        }
        final Long[] clearedIds = new Long[toClear.size()];
        for (int i = 0; i < toClear.size(); i++) {
            final Enemy e = toClear.get(i);
            clearedIds[i] = e.getId();
            realm.getExpiredEnemies().add(e.getId());
            realm.removeEnemy(e);
        }
        if (clearedIds.length > 0) {
            final UnloadPacket unload = UnloadPacket.from(
                new Long[0], new Long[0], clearedIds, new Long[0], new Long[0]);
            for (final Player p : realm.getPlayers().values()) {
                mgr.enqueueServerPacket(p, unload);
            }
        }

        log.info("Player {} /clearspawn: removed {} admin-spawned enemies",
                target.getName(), toClear.size());
        mgr.enqueueServerPacket(target,
                TextPacket.from("SYSTEM", target.getName(),
                        "Cleared " + toClear.size() + " admin-spawned enemies"));
    }

    @CommandHandler(value="damage", description="Admin: deal damage to enemies in a radius. Usage: /damage {AMOUNT} {RADIUS_TILES} [ENEMY_ID]")
    @AdminRestrictedCommand(provisions={AccountProvision.OPENREALM_MODERATOR})
    public static void invokeDamageEnemies(RealmManagerServer mgr, Player target,
            ServerCommandMessage message) throws Exception {
        if (message.getArgs() == null || message.getArgs().size() < 2 || message.getArgs().size() > 3)
            throw new IllegalArgumentException("Usage: /damage {AMOUNT} {RADIUS_TILES} [ENEMY_ID]");

        final int amount = Integer.parseInt(message.getArgs().get(0));
        final float radiusTiles = Float.parseFloat(message.getArgs().get(1));
        final Integer filterEnemyId = message.getArgs().size() == 3
                ? Integer.parseInt(message.getArgs().get(2)) : null;
        if (amount <= 0)
            throw new IllegalArgumentException("AMOUNT must be > 0");
        if (radiusTiles <= 0f)
            throw new IllegalArgumentException("RADIUS_TILES must be > 0");

        final Realm realm = mgr.findPlayerRealm(target.getId());
        if (realm == null)
            throw new IllegalArgumentException("No realm for player");

        final float radius = radiusTiles * GlobalConstants.BASE_TILE_SIZE;
        final float radiusSq = radius * radius;
        final Vector2f center = target.getPos();

        // Snapshot so we can safely call enemyDeath on the natural code path
        // without mutating the enemies map mid-iteration. Unlike /kill we
        // intentionally use the full enemyDeath() flow so loot, XP, level-ups,
        // and overseer notifications fire — that's the whole point of /damage
        // versus /kill (combat testing vs stress-test wipe).
        final List<Enemy> targets = new ArrayList<>();
        for (final Enemy e : realm.getEnemies().values()) {
            if (e == null || e.getDeath()) continue;
            if (e.hasEffect(StatusEffectType.INVINCIBLE)) continue;
            if (filterEnemyId != null && e.getEnemyId() != filterEnemyId) continue;
            final float dx = e.getPos().x - center.x;
            final float dy = e.getPos().y - center.y;
            if (dx * dx + dy * dy <= radiusSq) targets.add(e);
        }
        int hit = 0, killed = 0;
        for (final Enemy e : targets) {
            final int newHp = Math.max(0, e.getHealth() - amount);
            e.setHealth(newHp);
            hit++;
            if (newHp == 0) {
                realm.getExpiredEnemies().add(e.getId());
                mgr.enemyDeath(realm, e);
                killed++;
            }
        }

        log.info("Player {} /damage {}×{} (filter={}): hit={} killed={}",
                target.getName(), amount, radiusTiles, filterEnemyId, hit, killed);
        final String filterMsg = filterEnemyId != null ? " (enemy " + filterEnemyId + ")" : "";
        mgr.enqueueServerPacket(target,
                TextPacket.from("SYSTEM", target.getName(),
                        "Dealt " + amount + " damage to " + hit + " enemies" + filterMsg
                                + " (" + killed + " killed)"));
    }

    @CommandHandler(value="admin", description="Toggle your admin mode on/off. When OFF: admin commands are blocked, godmode clears, and your name color resets to default.")
    @AdminRestrictedCommand(provisions={AccountProvision.OPENREALM_MODERATOR})
    public static void invokeAdminToggle(RealmManagerServer mgr, Player target,
            ServerCommandMessage message) throws Exception {
        if (target.isAdminModeEnabled()) {
            target.setStoredChatRole(target.getChatRole());
            target.setChatRole("");
            if (target.hasEffect(StatusEffectType.INVINCIBLE)) {
                target.resetEffects();
            }
            target.setAdminModeEnabled(false);
            log.info("Player {} /admin: admin mode OFF", target.getName());
            mgr.enqueueServerPacket(target, TextPacket.from("SYSTEM", target.getName(),
                    "Admin mode OFF — restricted commands blocked, name color reset, godmode cleared"));
        } else {
            if (target.getStoredChatRole() != null && !target.getStoredChatRole().isEmpty()) {
                target.setChatRole(target.getStoredChatRole());
            }
            target.setAdminModeEnabled(true);
            log.info("Player {} /admin: admin mode ON", target.getName());
            mgr.enqueueServerPacket(target, TextPacket.from("SYSTEM", target.getName(),
                    "Admin mode ON — restricted commands available again"));
        }
        // Invalidate realm load-state so chatRole change reaches viewers next tick.
        final Realm realm = mgr.findPlayerRealm(target.getId());
        if (realm != null) {
            mgr.invalidateRealmLoadState(realm);
        }
    }

    @CommandHandler(value="hide", description="Toggle invisibility from other players. While hidden you also bypass enemy targeting and AOE damage; godmode auto-enables.")
    @AdminRestrictedCommand(provisions={AccountProvision.OPENREALM_MODERATOR})
    public static void invokeHide(RealmManagerServer mgr, Player target,
            ServerCommandMessage message) throws Exception {
        if (target.isHiddenFromOthers()) {
            target.setHiddenFromOthers(false);
            if (target.hasEffect(StatusEffectType.INVINCIBLE)) {
                target.resetEffects();
            }
            log.info("Player {} /hide: visible", target.getName());
            mgr.enqueueServerPacket(target, TextPacket.from("SYSTEM", target.getName(),
                    "Hide OFF — you're visible again. Godmode cleared."));
        } else {
            target.setHiddenFromOthers(true);
            target.addEffect(StatusEffectType.INVINCIBLE, 1000 * 60 * 60 * 24);
            log.info("Player {} /hide: hidden", target.getName());
            mgr.enqueueServerPacket(target, TextPacket.from("SYSTEM", target.getName(),
                    "Hide ON — invisible to other players, untargetable, godmode enabled."));
        }
        // Invalidate so viewers drop/restore the hidden player on next tick (not 3s snapshot).
        final Realm realm = mgr.findPlayerRealm(target.getId());
        if (realm != null) {
            mgr.invalidateRealmLoadState(realm);
        }
    }

    @CommandHandler(value="gmc", description="Send a message to every online GM (admin/sysadmin/mod). Usage: /gmc {message}")
    @AdminRestrictedCommand(provisions={AccountProvision.OPENREALM_MODERATOR})
    public static void invokeGmChat(RealmManagerServer mgr, Player target,
            ServerCommandMessage message) throws Exception {
        if (message.getArgs() == null || message.getArgs().isEmpty()) {
            throw new IllegalArgumentException("Usage: /gmc {message}");
        }
        // Reassemble — parser splits on whitespace.
        final String body = String.join(" ", message.getArgs());
        final String labeled = "[GM] " + target.getName() + ": " + body;
        int delivered = 0;
        for (final Player p : mgr.getPlayers()) {
            final String role = p.getChatRole();
            if (role == null) continue;
            if (!role.equals("sysadmin") && !role.equals("admin") && !role.equals("mod")) continue;
            mgr.enqueueServerPacket(p, TextPacket.from("SYSTEM", p.getName(), labeled));
            delivered++;
        }
        log.info("Player {} /gmc -> {} GM(s): {}", target.getName(), delivered, body);
    }

    @CommandHandler(value="visit", description="Teleport into a player's realm. Usage: /visit {PLAYER_NAME}")
    @AdminRestrictedCommand(provisions={AccountProvision.OPENREALM_MODERATOR})
    public static void invokeVisit(RealmManagerServer mgr, Player target,
            ServerCommandMessage message) throws Exception {
        if (message.getArgs() == null || message.getArgs().size() != 1) {
            throw new IllegalArgumentException("Usage: /visit {PLAYER_NAME}");
        }
        final String name = message.getArgs().get(0);
        final Player victim = mgr.findPlayerByName(name);
        if (victim == null) {
            throw new IllegalArgumentException("Player " + name + " not found online");
        }
        if (victim.getId() == target.getId()) {
            throw new IllegalArgumentException("You can't /visit yourself");
        }
        final Realm victimRealm = mgr.findPlayerRealm(victim.getId());
        if (victimRealm == null) {
            throw new IllegalArgumentException("Target's realm could not be located");
        }
        final Realm currentRealm = mgr.findPlayerRealm(target.getId());
        if (currentRealm != null && currentRealm.getRealmId() == victimRealm.getRealmId()) {
            target.setPos(victim.getPos().clone());
            mgr.enqueueServerPacket(target, TextPacket.from("SYSTEM", target.getName(),
                    "Already in " + name + "'s realm — snapped to their position."));
            log.info("Player {} /visit {} (same realm)", target.getName(), name);
            return;
        }
        transferAdminToRealm(mgr, target, currentRealm, victimRealm, victim.getPos());
        mgr.enqueueServerPacket(target, TextPacket.from("SYSTEM", target.getName(),
                "Visited " + name + " in realm " + victimRealm.getRealmId()));
        log.info("Player {} /visit {} (realm {} -> {})", target.getName(), name,
                currentRealm != null ? currentRealm.getRealmId() : null, victimRealm.getRealmId());
    }

    @CommandHandler(value="summon", description="Pull another player to your location. Usage: /summon {PLAYER_NAME}")
    @AdminRestrictedCommand(provisions={AccountProvision.OPENREALM_ADMIN})
    public static void invokeSummon(RealmManagerServer mgr, Player target,
            ServerCommandMessage message) throws Exception {
        if (message.getArgs() == null || message.getArgs().size() != 1) {
            throw new IllegalArgumentException("Usage: /summon {PLAYER_NAME}");
        }
        final String name = message.getArgs().get(0);
        final Player victim = mgr.findPlayerByName(name);
        if (victim == null) {
            throw new IllegalArgumentException("Player " + name + " not found online");
        }
        if (victim.getId() == target.getId()) {
            throw new IllegalArgumentException("You can't /summon yourself");
        }
        final Realm adminRealm = mgr.findPlayerRealm(target.getId());
        final Realm victimRealm = mgr.findPlayerRealm(victim.getId());
        if (adminRealm == null || victimRealm == null) {
            throw new IllegalArgumentException("One of the realms could not be located");
        }
        if (adminRealm.getRealmId() == victimRealm.getRealmId()) {
            victim.setPos(target.getPos().clone());
            mgr.enqueueServerPacket(victim, TextPacket.from("SYSTEM", victim.getName(),
                    "You were summoned by " + target.getName()));
            mgr.enqueueServerPacket(target, TextPacket.from("SYSTEM", target.getName(),
                    "Summoned " + name + " to your position."));
            log.info("Player {} /summon {} (same realm)", target.getName(), name);
            return;
        }
        transferAdminToRealm(mgr, victim, victimRealm, adminRealm, target.getPos());
        mgr.enqueueServerPacket(victim, TextPacket.from("SYSTEM", victim.getName(),
                "You were summoned by " + target.getName()));
        mgr.enqueueServerPacket(target, TextPacket.from("SYSTEM", target.getName(),
                "Summoned " + name + " from realm " + victimRealm.getRealmId()));
        log.info("Player {} /summon {} (realm {} -> {})", target.getName(), name,
                victimRealm.getRealmId(), adminRealm.getRealmId());
    }

    @CommandHandler(value="world", description="Teleport to a world without using a portal. Usage: /world {MAP_NAME_OR_ID} [new]")
    @AdminRestrictedCommand(provisions={AccountProvision.OPENREALM_ADMIN})
    public static void invokeWorld(RealmManagerServer mgr, Player target,
            ServerCommandMessage message) throws Exception {
        if (message.getArgs() == null || message.getArgs().isEmpty()) {
            throw new IllegalArgumentException("Usage: /world {MAP_NAME_OR_ID} [new]");
        }
        final String arg = message.getArgs().get(0);
        final boolean forceNew = message.getArgs().size() >= 2
                && "new".equalsIgnoreCase(message.getArgs().get(1));

        // Resolve: numeric mapId -> mapName -> nodeId.
        MapModel mapModel = null;
        String resolvedNodeId = null;
        try {
            final int mapId = Integer.parseInt(arg);
            mapModel = GameDataManager.MAPS.get(mapId);
        } catch (NumberFormatException ignored) { /* not numeric */ }

        if (mapModel == null && GameDataManager.MAPS != null) {
            for (final MapModel m : GameDataManager.MAPS.values()) {
                if (m.getMapName() != null && m.getMapName().equalsIgnoreCase(arg)) {
                    mapModel = m;
                    break;
                }
            }
        }
        if (mapModel == null && GameDataManager.DUNGEON_GRAPH != null
                && GameDataManager.DUNGEON_GRAPH.containsKey(arg)) {
            resolvedNodeId = arg;
            final DungeonGraphNode node = GameDataManager.DUNGEON_GRAPH.get(arg);
            if (node != null) {
                mapModel = GameDataManager.MAPS.get(node.getMapId());
            }
        }
        if (mapModel == null) {
            throw new IllegalArgumentException("No map found for '" + arg
                    + "'. Try a numeric mapId, mapName, or dungeon-graph nodeId.");
        }

        // Shared-ness comes from dungeon-graph node, not map.
        boolean isShared = false;
        if (resolvedNodeId != null) {
            final DungeonGraphNode node = GameDataManager.DUNGEON_GRAPH.get(resolvedNodeId);
            isShared = node != null && (node.isShared() || node.isEntryPoint());
        }

        final Realm currentRealm = mgr.findPlayerRealm(target.getId());

        Realm targetRealm = null;
        if (!forceNew) {
            if (resolvedNodeId != null) {
                targetRealm = mgr.findRealmForNode(resolvedNodeId).orElse(null);
            } else {
                for (final Realm r : mgr.getRealms().values()) {
                    if (r.getMapId() == mapModel.getMapId() && r.isOverworld()) {
                        targetRealm = r;
                        break;
                    }
                }
            }
        }
        if (targetRealm == null) {
            targetRealm = new Realm(isShared, mapModel.getMapId());
            if (resolvedNodeId != null) targetRealm.setNodeId(resolvedNodeId);
            mgr.addRealm(targetRealm);
            log.info("Player {} /world: created fresh realm {} (map={}, node={}, shared={})",
                    target.getName(), targetRealm.getRealmId(),
                    mapModel.getMapName(), resolvedNodeId, isShared);
        }

        final Vector2f spawnPos = (mapModel.getRandomSpawnPoint() != null)
                ? mapModel.getRandomSpawnPoint()
                : targetRealm.getTileManager().getSafePosition();
        transferAdminToRealm(mgr, target, currentRealm, targetRealm, spawnPos);
        mgr.enqueueServerPacket(target, TextPacket.from("SYSTEM", target.getName(),
                "Transferred to " + mapModel.getMapName() + " (realm " + targetRealm.getRealmId() + ")"));
        log.info("Player {} /world {} -> realm {} (forceNew={})",
                target.getName(), arg, targetRealm.getRealmId(), forceNew);
    }

    /** Mirrors portal-use flow: vault save, dungeon cleanup, invincibility grace, load invalidation. */
    private static void transferAdminToRealm(RealmManagerServer mgr, Player who,
            Realm from, Realm to, Vector2f spawnPos) {
        if (from != null) {
            from.getPlayers().remove(who.getId());
            from.removePlayer(who);

            if (from.getMapId() == 1) {
                try {
                    final List<ChestDto> chests = from.serializeChests();
                    ServerGameLogic.DATA_SERVICE.executePost(
                            "/data/account/" + who.getAccountUuid() + "/chest",
                            chests, PlayerAccountDto.class);
                } catch (Exception e) {
                    log.error("Failed to save vault chests for {} on transfer: {}",
                            who.getName(), e.getMessage());
                }
                try {
                    final List<ChestDto> storage = from.serializePotionStorage(who.getId());
                    ServerGameLogic.DATA_SERVICE.executePost(
                            "/data/account/" + who.getAccountUuid() + "/potion-storage",
                            storage, PlayerAccountDto.class);
                } catch (Exception e) {
                    log.error("Failed to save potion storage for {} on transfer: {}",
                            who.getName(), e.getMessage());
                }
                from.setShutdown(true);
                mgr.getRealms().remove(from.getRealmId());
            }

            if (from.getPlayers().isEmpty() && from.getNodeId() != null
                    && !"nexus".equals(from.getNodeId())) {
                final DungeonGraphNode node = GameDataManager.DUNGEON_GRAPH.get(from.getNodeId());
                if (node != null && !node.isShared() && !node.isEntryPoint()) {
                    from.setShutdown(true);
                    mgr.getRealms().remove(from.getRealmId());
                }
            }
        }

        who.setPos(spawnPos != null ? spawnPos.clone() : to.getTileManager().getSafePosition());
        who.addEffect(StatusEffectType.INVINCIBLE, 4000);
        mgr.broadcastTextEffect(EntityType.PLAYER, who, TextEffect.PLAYER_INFO, "Invincible");
        to.addPlayer(who);
        mgr.clearPlayerState(who.getId());
        mgr.invalidateRealmLoadState(to);
        ServerGameLogic.sendImmediateLoadMap(mgr, to, who);
        ServerGameLogic.onPlayerJoin(mgr, to, who);
    }

    @CommandHandler(value="event", description="Admin: spawn a realm event by id (no id = list). Usage: /event {EVENT_ID}")
    @AdminRestrictedCommand(provisions={AccountProvision.OPENREALM_MODERATOR})
    public static void invokeSpawnEvent(RealmManagerServer mgr, Player target, ServerCommandMessage message)
            throws Exception {
        if (message.getArgs() == null || message.getArgs().size() < 1) {
            final StringBuilder sb = new StringBuilder("Realm events:");
            if (GameDataManager.REALM_EVENTS != null) {
                final List<RealmEventModel> sorted =
                        new ArrayList<>(GameDataManager.REALM_EVENTS.values());
                sorted.sort(Comparator.comparingInt(
                        RealmEventModel::getEventId));
                for (final RealmEventModel ev : sorted) {
                    sb.append('\n').append("  ").append(ev.getEventId()).append(" — ").append(ev.getName());
                }
            }
            mgr.enqueueServerPacket(target,
                    TextPacket.from("SYSTEM", target.getName(), sb.toString()));
            return;
        }

        final int eventId;
        try {
            eventId = Integer.parseInt(message.getArgs().get(0));
        } catch (NumberFormatException nfe) {
            throw new IllegalArgumentException("EVENT_ID must be an integer (got '"
                    + message.getArgs().get(0) + "')");
        }

        if (GameDataManager.REALM_EVENTS == null) {
            throw new IllegalStateException("Realm event registry not loaded yet");
        }
        final RealmEventModel eventModel =
                GameDataManager.REALM_EVENTS.get(eventId);
        if (eventModel == null) {
            throw new IllegalArgumentException("No realm event with id " + eventId
                    + " — run /event with no args to list available ids");
        }

        // Overseer owns the spawn flow; static maps (nexus/vault) have none.
        final Realm playerRealm = mgr.findPlayerRealm(target.getId());
        if (playerRealm == null) {
            throw new IllegalStateException("No realm for player " + target.getName());
        }
        final RealmOverseer overseer = playerRealm.getOverseer();
        if (overseer == null) {
            throw new IllegalStateException(
                    "Current realm has no overseer (nexus/vault/static map) — run from an outdoor realm");
        }

        log.info("Player {} (admin) /event {} ({}) in realm {}",
                target.getName(), eventId, eventModel.getName(), playerRealm.getRealmId());
        // Spawn 6 tiles north so admin doesn't end up inside the setpiece.
        final int tileSize = GlobalConstants.BASE_TILE_SIZE;
        final Vector2f spawnAt = target.getPos().clone();
        spawnAt.y -= 6 * tileSize;
        final boolean ok = overseer.spawnRealmEvent(eventModel, spawnAt);
        if (!ok) {
            throw new IllegalStateException("Failed to spawn event " + eventId
                    + " — see server logs for the underlying reason");
        }
        mgr.enqueueServerPacket(target, TextPacket.from("SYSTEM", target.getName(),
                "Spawned event " + eventId + " — " + eventModel.getName()
                        + " at your position (check minimap for the boss pin)"));
    }

    @CommandHandler(value="seteffect", description="Add or remove Player stat effects")
	@AdminRestrictedCommand(provisions={AccountProvision.OPENREALM_MODERATOR})
    public static void invokeSetEffect(RealmManagerServer mgr, Player target, ServerCommandMessage message)
            throws Exception {
        if (message.getArgs() == null || message.getArgs().size() < 1)
            throw new IllegalArgumentException("Usage: /seteffect {add | clear} [EFFECT_ID] [DURATION_SEC]");
        log.info("Player {} set effect {}", target.getName(), message);
        final String sub = message.getArgs().get(0);
        switch (sub) {
        case "add": {
            if (message.getArgs().size() < 3)
                throw new IllegalArgumentException("Usage: /seteffect add {EFFECT_ID} {DURATION_SEC}");
            final short effectIdRaw;
            try {
                effectIdRaw = Short.parseShort(message.getArgs().get(1));
            } catch (NumberFormatException nfe) {
                throw new IllegalArgumentException("EFFECT_ID must be an integer (got '" + message.getArgs().get(1) + "')");
            }
            final StatusEffectType effect = StatusEffectType.valueOf(effectIdRaw);
            if (effect == null)
                throw new IllegalArgumentException("Unknown EFFECT_ID " + effectIdRaw + " — see StatusEffectType for valid ids");
            final long durationSec;
            try {
                durationSec = Long.parseLong(message.getArgs().get(2));
            } catch (NumberFormatException nfe) {
                throw new IllegalArgumentException("DURATION_SEC must be an integer (got '" + message.getArgs().get(2) + "')");
            }
            if (durationSec <= 0)
                throw new IllegalArgumentException("DURATION_SEC must be > 0");
            target.addEffect(effect, 1000L * durationSec);
            mgr.enqueueServerPacket(target, TextPacket.from("SYSTEM", target.getName(),
                    "Applied " + effect.name() + " for " + durationSec + "s"));
            break;
        }
        case "clear":
            target.resetEffects();
            mgr.enqueueServerPacket(target, TextPacket.from("SYSTEM", target.getName(),
                    "Cleared all status effects"));
            break;
        default:
            throw new IllegalArgumentException("Unknown subcommand '" + sub + "' — expected 'add' or 'clear'");
        }
    }

    @CommandHandler(value="fame", description="Admin: award ACCOUNT fame to self or another player. Usage: /fame {AMOUNT} [PLAYER_NAME]")
    @AdminRestrictedCommand(provisions={AccountProvision.OPENREALM_MODERATOR})
    public static void invokeGrantFame(RealmManagerServer mgr, Player target, ServerCommandMessage message)
            throws Exception {
        if (message.getArgs() == null || message.getArgs().size() < 1)
            throw new IllegalArgumentException("Usage: /fame {AMOUNT} [PLAYER_NAME]");

        final long amount;
        try {
            amount = Long.parseLong(message.getArgs().get(0));
        } catch (NumberFormatException nfe) {
            throw new IllegalArgumentException("AMOUNT must be a positive integer (got '" + message.getArgs().get(0) + "')");
        }
        if (amount <= 0)
            throw new IllegalArgumentException("AMOUNT must be > 0");

        // Resolve recipient: caller by default, or named player if provided.
        final Player recipient;
        if (message.getArgs().size() >= 2) {
            final String name = message.getArgs().get(1);
            recipient = mgr.findPlayerByName(name);
            if (recipient == null)
                throw new IllegalArgumentException("Player '" + name + "' is not online");
        } else {
            recipient = target;
        }

        final Long newTotal;
        try {
            newTotal = ServerGameLogic.DATA_SERVICE.executePost(
                    "/data/account/" + recipient.getAccountUuid() + "/fame/grant?amount=" + amount,
                    null, Long.class);
        } catch (Exception ex) {
            log.warn("[FAME] grant failed for {} ({} fame): {}", recipient.getName(), amount, ex.getMessage());
            throw new IllegalArgumentException("Grant failed: " + ex.getMessage());
        }
        if (newTotal != null) recipient.setCachedAccountFame(newTotal);

        log.info("[FAME] Player {} granted {} account fame to {} (now {})",
                target.getName(), amount, recipient.getName(),
                newTotal != null ? newTotal.toString() : "?");

        // Confirm to the granter.
        mgr.enqueueServerPacket(target, TextPacket.from("SYSTEM", target.getName(),
                "Granted " + amount + " account fame to "
                        + (recipient.getId() == target.getId() ? "yourself" : recipient.getName())
                        + (newTotal != null ? " (now " + newTotal + ")" : "")));
        // Notify the recipient if it's a different player.
        if (recipient.getId() != target.getId()) {
            mgr.enqueueServerPacket(recipient, TextPacket.from("SYSTEM", recipient.getName(),
                    target.getName() + " granted you " + amount + " account fame"
                            + (newTotal != null ? " (now " + newTotal + ")" : "")));
        }
    }

    @CommandHandler(value="tp", description="Teleport to a given Player name or X,Y coordinates")
    public static void invokeTeleport(RealmManagerServer mgr, Player target, ServerCommandMessage message)
            throws Exception {
        if (message.getArgs() == null || message.getArgs().size() < 1)
            throw new IllegalArgumentException("Usage: /tp {PLAYER_NAME}. /tp {X_CORD} {Y_CORD}");

        log.info("Player {} teleport {}", target.getName(), message);
        if (message.getArgs().size() == 2) {
            final float destX = Float.parseFloat(message.getArgs().get(0));
            final float destY = Float.parseFloat(message.getArgs().get(1));
            if (destX < GlobalConstants.PLAYER_SIZE || destY < GlobalConstants.PLAYER_SIZE) {
                throw new IllegalArgumentException("Invalid destination");
            }
            target.setPos(new Vector2f(destX, destY));
        } else {
            final Player destPlayer = mgr.searchRealmsForPlayer(message.getArgs().get(0));
            if (destPlayer == null) {
                throw new IllegalArgumentException("Player " + message.getArgs().get(0) + " is not online.");
            }
            // Cross-realm teleport would land at coordinates in the wrong map.
            final Realm targetRealm = mgr.findPlayerRealm(target.getId());
            final Realm destRealm = mgr.findPlayerRealm(destPlayer.getId());
            if (targetRealm == null || destRealm == null || targetRealm.getRealmId() != destRealm.getRealmId()) {
                throw new IllegalArgumentException("Cannot teleport to " + destPlayer.getName() + " — they are in a different area.");
            }
            if (destPlayer.hasEffect(StatusEffectType.INVISIBLE)
                    || destPlayer.hasEffect(StatusEffectType.STASIS)) {
                throw new IllegalArgumentException(destPlayer.getName() + " cannot be teleported to right now.");
            }
            target.setPos(destPlayer.getPos().clone());
            mgr.enqueueServerPacket(target,
                    TextPacket.from("SYSTEM", target.getName(),
                            "Teleported to " + destPlayer.getName()));
        }
    }

    @CommandHandler(value="item", description="Spawn a given Item by id OR name. Usage: /item {ITEM_ID_OR_NAME} [COUNT]")
	@AdminRestrictedCommand(provisions={AccountProvision.OPENREALM_MODERATOR})
    public static void invokeSpawnItem(RealmManagerServer mgr, Player target, ServerCommandMessage message)
            throws Exception {
        if (message.getArgs() == null || message.getArgs().size() < 1)
            throw new IllegalArgumentException("Usage: /item {ITEM_ID_OR_NAME} [COUNT]");
        log.info("Player {} spawn item {}", target.getName(), message);
        final Realm targetRealm = mgr.findPlayerRealm(target.getId());
        final int gameItemId = GameDataLookup.resolveItemId(message.getArgs().get(0));
        final GameItem itemTemplate = GameDataManager.GAME_ITEMS.get(gameItemId);
        if (itemTemplate == null) {
            throw new IllegalArgumentException("Item with ID " + gameItemId + " does not exist.");
        }

        // Stackables: COUNT becomes stackCount (capped at maxStack).
        if (itemTemplate.isStackable()) {
            int requested = 1;
            if (message.getArgs().size() >= 2) {
                requested = Math.max(1, Integer.parseInt(message.getArgs().get(1)));
            }
            final int stackSize = Math.min(itemTemplate.getMaxStack(), requested);
            final GameItem stack = itemTemplate.clone();
            stack.setStackCount(stackSize);
            final LootContainer lootDrop = new LootContainer(LootTier.BROWN,
                    target.getPos().clone(Realm.RANDOM.nextInt(48) - 24, Realm.RANDOM.nextInt(48) - 24),
                    new GameItem[] { stack });
            targetRealm.addLootContainer(lootDrop);
            return;
        }

        // Non-stackables: COUNT separate copies (cap 32), packed in bags of 8.
        int count = 1;
        if (message.getArgs().size() >= 2) {
            count = Math.min(32, Math.max(1, Integer.parseInt(message.getArgs().get(1))));
        }
        int spawned = 0;
        while (spawned < count) {
            int bagSize = Math.min(8, count - spawned);
            GameItem[] bagItems = new GameItem[bagSize];
            for (int i = 0; i < bagSize; i++) {
                bagItems[i] = GameDataManager.GAME_ITEMS.get(gameItemId);
            }
            final LootContainer lootDrop = new LootContainer(LootTier.BROWN,
                    target.getPos().clone(Realm.RANDOM.nextInt(48) - 24, Realm.RANDOM.nextInt(48) - 24),
                    bagItems);
            targetRealm.addLootContainer(lootDrop);
            spawned += bagSize;
        }
    }

    @CommandHandler(value="tierset", description="Bulk-spawn tiered weapons. Usage: /tierset {tier N | arch NAME | all}")
    @AdminRestrictedCommand(provisions={AccountProvision.OPENREALM_MODERATOR})
    public static void invokeSpawnTierSet(RealmManagerServer mgr, Player target, ServerCommandMessage message)
            throws Exception {
        if (message.getArgs() == null || message.getArgs().isEmpty())
            throw new IllegalArgumentException("Usage: /tierset {tier N | arch NAME | all}");
        final Realm targetRealm = mgr.findPlayerRealm(target.getId());
        final java.util.Map<String,Integer> ARCH_OFFSET = new java.util.HashMap<>();
        ARCH_OFFSET.put("sword", 0);
        ARCH_OFFSET.put("axe", 1);
        ARCH_OFFSET.put("hammer", 2);
        ARCH_OFFSET.put("dagger", 3);
        ARCH_OFFSET.put("bow", 4);
        ARCH_OFFSET.put("knife", 5);
        ARCH_OFFSET.put("chakram", 5);
        ARCH_OFFSET.put("kunai", 5);
        ARCH_OFFSET.put("tome", 6);
        ARCH_OFFSET.put("book", 6);
        ARCH_OFFSET.put("staff", 7);
        ARCH_OFFSET.put("wand", 8);
        final String mode = message.getArgs().get(0).toLowerCase();
        final java.util.List<Integer> ids = new java.util.ArrayList<>();
        if ("tier".equals(mode)) {
            if (message.getArgs().size() < 2)
                throw new IllegalArgumentException("Usage: /tierset tier {0-8}");
            final int tier = Math.max(0, Math.min(8, Integer.parseInt(message.getArgs().get(1))));
            for (int off = 0; off < 9; off++) ids.add(3000 + tier*100 + off);
        } else if ("arch".equals(mode)) {
            if (message.getArgs().size() < 2)
                throw new IllegalArgumentException("Usage: /tierset arch {sword|axe|hammer|dagger|bow|knife|tome|staff|wand}");
            final Integer off = ARCH_OFFSET.get(message.getArgs().get(1).toLowerCase());
            if (off == null)
                throw new IllegalArgumentException("Unknown archetype. Use sword/axe/hammer/dagger/bow/knife/tome/staff/wand");
            for (int t = 0; t < 9; t++) ids.add(3000 + t*100 + off);
        } else if ("all".equals(mode)) {
            for (int t = 0; t < 9; t++) for (int o = 0; o < 9; o++) ids.add(3000 + t*100 + o);
        } else {
            throw new IllegalArgumentException("Usage: /tierset {tier N | arch NAME | all}");
        }
        // Resolve up-front so missing IDs don't end up as nulls in LootContainers.
        final java.util.List<GameItem> resolved = new java.util.ArrayList<>();
        final java.util.List<Integer> missing = new java.util.ArrayList<>();
        for (final Integer iid : ids) {
            final GameItem tmpl = GameDataManager.GAME_ITEMS.get(iid);
            if (tmpl == null) { missing.add(iid); continue; }
            resolved.add(tmpl.clone());
        }
        if (!missing.isEmpty()) {
            log.warn("[/tierset] {} items missing from GAME_ITEMS: {}", missing.size(), missing);
        }
        int spawned = 0;
        while (spawned < resolved.size()) {
            int bagSize = Math.min(8, resolved.size() - spawned);
            GameItem[] bag = new GameItem[bagSize];
            for (int i = 0; i < bagSize; i++) bag[i] = resolved.get(spawned + i);
            targetRealm.addLootContainer(new LootContainer(LootTier.BROWN,
                    target.getPos().clone(Realm.RANDOM.nextInt(48) - 24, Realm.RANDOM.nextInt(48) - 24),
                    bag));
            spawned += bagSize;
        }
        final String msg = "Spawned " + resolved.size() + "/" + ids.size() + " tier weapons"
                + (missing.isEmpty() ? "." : ". Missing IDs: " + missing);
        mgr.enqueueServerPacket(target, TextPacket.from("SYSTEM", target.getName(), msg));
    }

    @CommandHandler(value="portal", description="Spawn a portal to a map by id OR name. Usage: /portal {MAP_ID_OR_NAME}")
	@AdminRestrictedCommand(provisions={AccountProvision.OPENREALM_MODERATOR})
    public static void invokeSpawnPortal(RealmManagerServer mgr, Player target, ServerCommandMessage message)
            throws Exception {
        if (message.getArgs() == null || message.getArgs().size() < 1)
            throw new IllegalArgumentException("Usage: /portal {MAP_ID_OR_NAME}");

        final String mapToken = String.join(" ", message.getArgs());
        log.info("Player {} spawning portal to map '{}'", target.getName(), mapToken);

        // Strict resolve (id or exact mapName) — partial-match fallback was non-deterministic.
        final MapModel targetMap;
        try {
            targetMap = GameDataLookup.resolveMap(mapToken);
        } catch (final IllegalArgumentException notFound) {
            final String available = GameDataManager.MAPS.values().stream()
                    .map(m -> m.getMapName() + " (" + m.getMapId() + ")")
                    .collect(Collectors.joining(", "));
            throw new IllegalArgumentException(notFound.getMessage() + " Available: " + available);
        }

        PortalModel portalModel = null;
        for (PortalModel pm : GameDataManager.PORTALS.values()) {
            if (pm.getMapId() == targetMap.getMapId()) {
                portalModel = pm;
                break;
            }
        }
        // Default: dungeon portal (portalId 6).
        if (portalModel == null) {
            portalModel = GameDataManager.PORTALS.get(6);
        }

        final Realm currentRealm = mgr.findPlayerRealm(target.getId());
        Realm destinationRealm = null;
        String targetNodeId = null;
        for (DungeonGraphNode node : GameDataManager.DUNGEON_GRAPH.values()) {
            if (node.getMapId() == targetMap.getMapId() && node.isShared()) {
                targetNodeId = node.getNodeId();
                Optional<Realm> existing = mgr.findRealmForNode(node.getNodeId());
                if (existing.isPresent()) {
                    destinationRealm = existing.get();
                }
                break;
            }
        }
        if (destinationRealm == null) {
            destinationRealm = new Realm(true, targetMap.getMapId(), targetNodeId);
            destinationRealm.spawnRandomEnemies(targetMap.getMapId());
            mgr.addRealm(destinationRealm);
        }

        final Portal portal = new Portal(
                Realm.RANDOM.nextLong(), (short) portalModel.getPortalId(), target.getPos().clone());
        portal.linkPortal(currentRealm, destinationRealm);
        portal.setNeverExpires();
        if (targetNodeId != null) portal.setTargetNodeId(targetNodeId);
        currentRealm.addPortal(portal);

        final String msg = "Portal to " + targetMap.getMapName() + " spawned!";
        mgr.enqueueServerPacket(target, TextPacket.from("SYSTEM", target.getName(), msg));
        log.info("Player {} spawned portal to {} (mapId={})", target.getName(), targetMap.getMapName(), targetMap.getMapId());
    }

    @CommandHandler(value="godmode", description="Toggle invincibility")
	@AdminRestrictedCommand(provisions={AccountProvision.OPENREALM_MODERATOR})
    public static void invokeGodMode(RealmManagerServer mgr, Player target, ServerCommandMessage message)
            throws Exception {
        if (target.hasEffect(StatusEffectType.INVINCIBLE)) {
            target.resetEffects();
            mgr.enqueueServerPacket(target, TextPacket.from("SYSTEM", target.getName(), "God mode OFF"));
        } else {
            target.addEffect(StatusEffectType.INVINCIBLE, 1000 * 60 * 60 * 24);
            mgr.enqueueServerPacket(target, TextPacket.from("SYSTEM", target.getName(), "God mode ON"));
        }
        log.info("Player {} toggled god mode", target.getName());
    }

    @CommandHandler(value="spawnbots", description="Spawn N bot players with real accounts. Usage: /spawnbots {COUNT} [spam]")
	@AdminRestrictedCommand(provisions={AccountProvision.OPENREALM_SYS_ADMIN})
    public static void invokeSpawnBots(RealmManagerServer mgr, Player target, ServerCommandMessage message)
            throws Exception {
        if (message.getArgs() == null || message.getArgs().size() < 1)
            throw new IllegalArgumentException("Usage: /spawnbots {COUNT} [spam]");

        final int count = Integer.parseInt(message.getArgs().get(0));
        if (count < 1 || count > 200)
            throw new IllegalArgumentException("Count must be between 1 and 200");

        final boolean spamMode = message.getArgs().size() >= 2
                && "spam".equalsIgnoreCase(message.getArgs().get(1));
        final String modeLabel = spamMode ? " (spam mode - wizards)" : " (walk mode)";

        mgr.enqueueServerPacket(target, TextPacket.from("SYSTEM", target.getName(),
                "Spawning " + count + " bot players" + modeLabel + "..."));

        final String serverHost = "127.0.0.1";
        final int serverPort = 2222;

        WorkerThread.doAsync(() -> {
            final int CREATE_PARALLELISM  = 32;
            final int CONNECT_PARALLELISM = 32;
            final long PHASE_TIMEOUT_SEC  = 60;
            final long startMs = System.currentTimeMillis();

            // Phase 1: parallel account + character creation.
            final List<String[]> botCredentials = Collections.synchronizedList(new ArrayList<>());
            log.info("[BOTS] Phase 1: Creating {} accounts ({}-way parallel)...", count, CREATE_PARALLELISM);
            final ExecutorService createPool = Executors.newFixedThreadPool(CREATE_PARALLELISM, r -> {
                Thread t = new Thread(r); t.setDaemon(true); t.setName("bot-create"); return t;
            });
            for (int i = 0; i < count; i++) {
                final int idx = i;
                createPool.submit(() -> {
                    try {
                        final String botId = "bot-" + UUID.randomUUID().toString();
                        final String email = botId + "@jrealm-bot.local";
                        final String password = "botpass-" + UUID.randomUUID().toString();
                        final String botName = "Bot_" + botId.substring(4, 12);

                        final AccountDto registerReq = AccountDto.builder()
                                .email(email).password(password).accountName(botName)
                                .accountProvisions(Arrays.asList(AccountProvision.OPENREALM_PLAYER))
                                .accountSubscriptions(Arrays.asList(AccountSubscription.TRIAL))
                                .build();
                        final JsonNode registered = ServerGameLogic.DATA_SERVICE.executePost(
                                "/admin/account/register", registerReq, JsonNode.class);
                        final String accountGuid = registered.get("accountGuid").asText();

                        final int classId = spamMode ? CharacterClass.WIZARD.classId : CharacterClass.ASSASSIN.classId;
                        final PlayerAccountDto account = ServerGameLogic.DATA_SERVICE.executePost(
                                "/data/account/" + accountGuid + "/character?classId=" + classId,
                                null, PlayerAccountDto.class);

                        String characterUuid = null;
                        if (account.getCharacters() != null && !account.getCharacters().isEmpty()) {
                            characterUuid = account.getCharacters().get(0).getCharacterUuid();
                        }
                        if (characterUuid == null) {
                            log.error("[BOTS] Failed to get character UUID for {}", botName);
                            return;
                        }
                        botCredentials.add(new String[]{email, password, characterUuid, accountGuid});
                        synchronized (BOT_ACCOUNT_GUIDS) { BOT_ACCOUNT_GUIDS.add(accountGuid); }
                    } catch (Exception e) {
                        log.error("[BOTS] Failed to create bot account {}: {}", idx, e.getMessage());
                    }
                });
            }
            createPool.shutdown();
            try { createPool.awaitTermination(PHASE_TIMEOUT_SEC, TimeUnit.SECONDS); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            log.info("[BOTS] Phase 1 done in {} ms — {} / {} accounts ready",
                    System.currentTimeMillis() - startMs, botCredentials.size(), count);

            // Phase 2: parallel connect + login + godmode + realm-transfer (mgr lock prevents realm-collection race).
            log.info("[BOTS] Phase 2: Connecting {} bots ({}-way parallel)...", botCredentials.size(), CONNECT_PARALLELISM);
            final long phase2Start = System.currentTimeMillis();
            final AtomicInteger success = new AtomicInteger(0);
            final ExecutorService connectPool = Executors.newFixedThreadPool(CONNECT_PARALLELISM, r -> {
                Thread t = new Thread(r); t.setDaemon(true); t.setName("bot-connect"); return t;
            });
            for (int i = 0; i < botCredentials.size(); i++) {
                final int idx = i;
                final String[] creds = botCredentials.get(i);
                connectPool.submit(() -> {
                    try {
                        final StressTestClient bot = new StressTestClient(idx, serverHost, serverPort,
                                creds[0], creds[1], creds[2], spamMode);
                        synchronized (ACTIVE_BOTS) { ACTIVE_BOTS.add(bot); }
                        Thread botThread = new Thread(bot, "bot-runner-" + idx);
                        botThread.setDaemon(true);
                        botThread.start();

                        // 10s ceiling — data+game svc queueing under 200-bot bursts can exceed 3s.
                        final long waitStart = System.currentTimeMillis();
                        while (!bot.isLoggedIn() && !bot.isShutdown()
                                && (System.currentTimeMillis() - waitStart) < 10000) {
                            Thread.sleep(25);
                        }
                        if (!bot.isLoggedIn()) {
                            log.warn("[BOTS] Bot {} did not log in within 10s", idx);
                            return;
                        }
                        success.incrementAndGet();
                        synchronized (mgr) {
                            try {
                                final Player botPlayer = mgr.getPlayers().stream()
                                        .filter(p -> p.getId() == bot.getAssignedPlayerId())
                                        .findFirst().orElse(null);
                                if (botPlayer == null) {
                                    log.warn("[BOTS] Bot {} player object not found after login", idx);
                                    return;
                                }
                                botPlayer.addEffect(StatusEffectType.INVINCIBLE, 1000L * 60 * 60 * 24);
                                final Realm casterRealm = mgr.findPlayerRealm(target.getId());
                                if (casterRealm == null) {
                                    log.warn("[BOTS] Caster {} not in any realm — leaving bot {} in nexus",
                                            target.getName(), idx);
                                    return;
                                }
                                final Realm botRealm = mgr.findPlayerRealm(botPlayer.getId());
                                final float ox = target.getPos().x + (Realm.RANDOM.nextFloat() * 60 - 30);
                                final float oy = target.getPos().y + (Realm.RANDOM.nextFloat() * 60 - 30);
                                if (botRealm != null && botRealm.getRealmId() == casterRealm.getRealmId()) {
                                    // Skip transferAdminToRealm when source==dest (would do unwanted vault-save/cleanup).
                                    botPlayer.setPos(new Vector2f(ox, oy));
                                } else {
                                    transferAdminToRealm(mgr, botPlayer, botRealm, casterRealm,
                                            new Vector2f(ox, oy));
                                }
                            } catch (Exception ex) {
                                log.warn("[BOTS] Bot {} godmode/realm setup failed: {}", idx, ex.getMessage());
                            }
                        }
                    } catch (Exception e) {
                        log.error("[BOTS] Failed to connect bot {}: {}", idx, e.getMessage());
                    }
                });
            }
            connectPool.shutdown();
            try { connectPool.awaitTermination(PHASE_TIMEOUT_SEC, TimeUnit.SECONDS); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            final long totalMs = System.currentTimeMillis() - startMs;
            log.info("[BOTS] Phase 2 done in {} ms (total {} ms) — {}/{} bots online",
                    System.currentTimeMillis() - phase2Start, totalMs, success.get(), count);
            try {
                mgr.enqueueServerPacket(target, TextPacket.from("SYSTEM", target.getName(),
                        "Spawned " + success.get() + "/" + count + " bot players in " + (totalMs / 1000) + "s"));
            } catch (Exception e) {
                // ignore
            }
        });
    }

    @CommandHandler(value="killbots", description="Disconnect all bot players and delete their accounts")
	@AdminRestrictedCommand(provisions={AccountProvision.OPENREALM_SYS_ADMIN})
    public static void invokeKillBots(RealmManagerServer mgr, Player target, ServerCommandMessage message)
            throws Exception {
        WorkerThread.doAsync(() -> {
            int disconnected = 0;
            int deleted = 0;
            int orphans = 0;

            // Scan all realms for orphan "Bot_"-prefixed players (ACTIVE_BOTS lost on JVM restart).
            try {
                final List<Player> allPlayers = mgr.getPlayers();
                for (final Player p : allPlayers) {
                    final String name = p.getName();
                    if (name != null && name.startsWith("Bot_")) {
                        try {
                            mgr.disconnectPlayer(p, "killbots cleanup");
                            orphans++;
                            if (p.getAccountUuid() != null) {
                                synchronized (BOT_ACCOUNT_GUIDS) {
                                    if (!BOT_ACCOUNT_GUIDS.contains(p.getAccountUuid())) {
                                        BOT_ACCOUNT_GUIDS.add(p.getAccountUuid());
                                    }
                                }
                            }
                        } catch (Exception e) {
                            log.error("[BOTS] Failed to disconnect orphan bot {}: {}", name, e.getMessage());
                        }
                    }
                }
                if (orphans > 0) {
                    log.info("[BOTS] Disconnected {} orphan bot players (untracked StressTestClient)", orphans);
                }
            } catch (Exception e) {
                log.error("[BOTS] Orphan scan failed: {}", e.getMessage());
            }

            synchronized (ACTIVE_BOTS) {
                for (StressTestClient bot : ACTIVE_BOTS) {
                    try {
                        bot.shutdown();
                        disconnected++;
                    } catch (Exception e) {
                        log.error("[BOTS] Failed to shutdown bot: {}", e.getMessage());
                    }
                }
                ACTIVE_BOTS.clear();
            }

            synchronized (BOT_ACCOUNT_GUIDS) {
                for (String accountGuid : BOT_ACCOUNT_GUIDS) {
                    try {
                        PlayerAccountDto account = ServerGameLogic.DATA_SERVICE.executeGet(
                                "/data/account/" + accountGuid, null, PlayerAccountDto.class);
                        if (account != null && account.getCharacters() != null) {
                            for (CharacterDto c : account.getCharacters()) {
                                ServerGameLogic.DATA_SERVICE.executeDelete(
                                        "/data/account/character/" + c.getCharacterUuid(), Object.class);
                            }
                        }
                        deleted++;
                    } catch (Exception e) {
                        log.error("[BOTS] Failed to delete bot account {}: {}", accountGuid, e.getMessage());
                    }
                }
                BOT_ACCOUNT_GUIDS.clear();
            }

            log.info("[BOTS] Killed {} bots ({} orphans), deleted {} accounts", disconnected, orphans, deleted);
            try {
                mgr.enqueueServerPacket(target, TextPacket.from("SYSTEM", target.getName(),
                        "Killed " + (disconnected + orphans) + " bots ("
                                + orphans + " orphans), deleted " + deleted + " accounts"));
            } catch (Exception e) {
                // ignore
            }
        });
    }

    @CommandHandler(value="realm", description="Move the player to the top realm (/realm up) or boss realm (/realm down, admin only)")
    public static void invokeRealmMove(RealmManagerServer mgr, Player target, ServerCommandMessage message)
            throws Exception {
        if (message.getArgs() == null || message.getArgs().size() < 1)
            throw new IllegalArgumentException("Usage: /realm {up | down}");

        final String direction = message.getArgs().get(0).toLowerCase();
        final Realm currentRealm = mgr.findPlayerRealm(target.getId());

        if (direction.equals("up")) {
            currentRealm.getPlayers().remove(target.getId());
            currentRealm.removePlayer(target);
            final Realm topRealm = mgr.getTopRealm();
            target.setPos(topRealm.getTileManager().getSafePosition());
            topRealm.addPlayer(target);
            mgr.clearPlayerState(target.getId());
            mgr.invalidateRealmLoadState(topRealm);
            ServerGameLogic.sendImmediateLoadMap(mgr, topRealm, target);
            ServerGameLogic.onPlayerJoin(mgr, topRealm, target);

            if (currentRealm.getPlayers().size() == 0 && currentRealm.getNodeId() != null) {
                final DungeonGraphNode node =
                        GameDataManager.DUNGEON_GRAPH.get(currentRealm.getNodeId());
                if (node != null && !node.isEntryPoint()) {
                    currentRealm.setShutdown(true);
                    mgr.getRealms().remove(currentRealm.getRealmId());
                }
            }
        } else if (direction.equals("down")) {
            boolean isAdmin = false;
            try {
                final AccountDto account = ServerGameLogic.DATA_SERVICE.executeGet(
                        "/admin/account/" + target.getAccountUuid(), null, AccountDto.class);
                isAdmin = account != null && account.isAdmin();
            } catch (Exception e) {
            }
            if (!isAdmin) {
                throw new IllegalStateException("Only administrators can use /realm down");
            }
            currentRealm.getPlayers().remove(target.getId());
            final PortalModel bossPortal = GameDataManager.PORTALS.get(5);
            final Realm generatedRealm = new Realm(true, bossPortal.getMapId());
            final Vector2f spawnPos = new Vector2f(GlobalConstants.BASE_TILE_SIZE * 12,
                    GlobalConstants.BASE_TILE_SIZE * 13);
            target.setPos(spawnPos);
            generatedRealm.addPlayer(target);
            mgr.addRealm(generatedRealm);
            mgr.clearPlayerState(target.getId());
            mgr.invalidateRealmLoadState(generatedRealm);
            ServerGameLogic.sendImmediateLoadMap(mgr, generatedRealm, target);
            ServerGameLogic.onPlayerJoin(mgr, generatedRealm, target);
        } else {
            throw new IllegalArgumentException("Usage: /realm {up | down}");
        }
    }

    @CommandHandler(value="rarity", description="Set the rarity of an equipped item. Usage: /rarity {SLOT 0-3} {0-5 or NAME}")
    @AdminRestrictedCommand(provisions={AccountProvision.OPENREALM_MODERATOR})
    public static void invokeSetRarity(RealmManagerServer mgr, Player target, ServerCommandMessage message)
            throws Exception {
        if (message.getArgs() == null || message.getArgs().size() < 2)
            throw new IllegalArgumentException("Usage: /rarity {SLOT 0-3} {0-5 | MUNDANE..LEGENDARY}");
        final int slot = Integer.parseInt(message.getArgs().get(0));
        if (slot < 0 || slot > 3) throw new IllegalArgumentException("Slot must be 0-3 (equipment).");
        final GameItem item = target.getInventory()[slot];
        if (item == null) throw new IllegalArgumentException("Slot " + slot + " is empty.");
        final String raw = message.getArgs().get(1).toUpperCase();
        Rarity r;
        try {
            r = Rarity.valueOf(raw);
        } catch (IllegalArgumentException e) {
            r = Rarity.fromOrdinal(Integer.parseInt(raw));
        }
        item.setRarity((byte) r.ordinal());
        log.info("[Admin] {} set rarity of {} to {}", target.getName(), item.getName(), r.displayName);
        mgr.enqueueServerPacket(target, TextPacket.from("SYSTEM", target.getName(),
                item.getName() + " is now " + r.displayName + " (" + r.crystalSlots + " crystal slots"
                        + (r.hasGemSocket() ? ", +1 gem socket" : "") + ")"));
        final UpdatePacket update =
                mgr.findPlayerRealm(target.getId()).getPlayerAsPacket(target.getId());
        if (update != null) mgr.enqueueServerPacket(target, update);
        mgr.persistPlayerAsync(target);
    }

    @CommandHandler(value="modifier", description="Add an attribute-modifier affix to an equipped item. Usage: /modifier {SLOT 0-3} {STAT 0-7} {DELTA -127..127}")
    @AdminRestrictedCommand(provisions={AccountProvision.OPENREALM_MODERATOR})
    public static void invokeAddModifier(RealmManagerServer mgr, Player target, ServerCommandMessage message)
            throws Exception {
        if (message.getArgs() == null || message.getArgs().size() < 3)
            throw new IllegalArgumentException("Usage: /modifier {SLOT 0-3} {STAT 0-7} {DELTA}");
        final int slot = Integer.parseInt(message.getArgs().get(0));
        final int statId = Integer.parseInt(message.getArgs().get(1));
        final int delta = Integer.parseInt(message.getArgs().get(2));
        if (slot < 0 || slot > 3) throw new IllegalArgumentException("Slot must be 0-3.");
        if (statId < 0 || statId > 7) throw new IllegalArgumentException("Stat must be 0-7.");
        if (delta < Byte.MIN_VALUE || delta > Byte.MAX_VALUE)
            throw new IllegalArgumentException("Delta out of byte range.");
        final GameItem item = target.getInventory()[slot];
        if (item == null) throw new IllegalArgumentException("Slot " + slot + " is empty.");
        if (item.getAttributeModifiers() == null) item.setAttributeModifiers(new ArrayList<>());
        item.getAttributeModifiers().add(new AttributeModifier((byte) statId, (byte) delta));
        log.info("[Admin] {} added modifier stat={} delta={} to {}", target.getName(), statId, delta, item.getName());
        mgr.enqueueServerPacket(target, TextPacket.from("SYSTEM", target.getName(),
                "Added affix to " + item.getName()));
        final UpdatePacket update =
                mgr.findPlayerRealm(target.getId()).getPlayerAsPacket(target.getId());
        if (update != null) mgr.enqueueServerPacket(target, update);
        mgr.persistPlayerAsync(target);
    }

    @CommandHandler(value="setench", description="Add a stat-delta enchantment to an equipped item. Usage: /setench {SLOT} {STAT 0-7} {DELTA -127..127}")
    @AdminRestrictedCommand(provisions={AccountProvision.OPENREALM_MODERATOR})
    public static void invokeSetEnchantment(RealmManagerServer mgr, Player target, ServerCommandMessage message)
            throws Exception {
        if (message.getArgs() == null || message.getArgs().size() < 3)
            throw new IllegalArgumentException(
                "Usage: /setench {SLOT 0-3} {STAT 0=VIT 1=WIS 2=HP 3=MP 4=STR 5=DEF 6=SPD 7=DEX} {DELTA}");
        final int slot = Integer.parseInt(message.getArgs().get(0));
        final int statId = Integer.parseInt(message.getArgs().get(1));
        final int delta = Integer.parseInt(message.getArgs().get(2));
        if (slot < 0 || slot > 3) throw new IllegalArgumentException("Slot must be 0-3.");
        if (statId < 0 || statId > 7) throw new IllegalArgumentException("Stat must be 0-7.");
        if (delta < Byte.MIN_VALUE || delta > Byte.MAX_VALUE)
            throw new IllegalArgumentException("Delta out of byte range.");
        final GameItem item = target.getInventory()[slot];
        if (item == null) throw new IllegalArgumentException("Slot " + slot + " is empty.");
        if (item.getEnchantments() == null) item.setEnchantments(new ArrayList<>());
        if (item.getEnchantments().size() >= item.getMaxEnchantments())
            throw new IllegalArgumentException("Item at rarity cap (" + item.getMaxEnchantments() + ").");
        final Enchantment e = new Enchantment((byte) statId, (byte) delta, (byte) 0, (byte) 0, 0xFFFFFFFF);
        item.getEnchantments().add(e);
        log.info("[Admin] {} added enchantment stat={} delta={} to {}",
                target.getName(), statId, delta, item.getName());
        mgr.enqueueServerPacket(target, TextPacket.from("SYSTEM", target.getName(),
                "Forged " + item.getName() + " (slots " + item.getEnchantments().size() + "/" + item.getMaxEnchantments() + ")"));
        final UpdatePacket update =
                mgr.findPlayerRealm(target.getId()).getPlayerAsPacket(target.getId());
        if (update != null) mgr.enqueueServerPacket(target, update);
        mgr.persistPlayerAsync(target);
    }

    @CommandHandler(value="setgem", description="Socket a gemstone into an equipped item. Usage: /setgem {SLOT 0-3} {GEMSTONE_TYPE} (0 = clear)")
    @AdminRestrictedCommand(provisions={AccountProvision.OPENREALM_MODERATOR})
    public static void invokeSetGem(RealmManagerServer mgr, Player target, ServerCommandMessage message)
            throws Exception {
        if (message.getArgs() == null || message.getArgs().size() < 2)
            throw new IllegalArgumentException(
                "Usage: /setgem {SLOT 0-3} {GEMSTONE_TYPE} — see GemstoneRegistry for ids. 0 clears.");
        final int slot = Integer.parseInt(message.getArgs().get(0));
        final int gType = Integer.parseInt(message.getArgs().get(1));
        if (slot < 0 || slot > 3) throw new IllegalArgumentException("Slot must be 0-3.");
        if (gType < 0 || gType > 127) throw new IllegalArgumentException("Gemstone type byte range.");
        final GameItem item = target.getInventory()[slot];
        if (item == null) throw new IllegalArgumentException("Slot " + slot + " is empty.");
        item.setGemstoneType((byte) gType);
        item.setGemPixelX((byte) 0);
        item.setGemPixelY((byte) 0);
        item.setGemPixelColor(0xFFFFFFFF);
        log.info("[Admin] {} set gemstoneType={} on {}", target.getName(), gType, item.getName());
        mgr.enqueueServerPacket(target, TextPacket.from("SYSTEM", target.getName(),
                gType == 0 ? "Cleared gem on " + item.getName()
                           : "Socketed gem " + gType + " into " + item.getName()));
        final UpdatePacket update =
                mgr.findPlayerRealm(target.getId()).getPlayerAsPacket(target.getId());
        if (update != null) mgr.enqueueServerPacket(target, update);
        mgr.persistPlayerAsync(target);
    }

}
