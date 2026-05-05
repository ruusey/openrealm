package com.openrealm.net.server;

import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.stream.Collectors;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.openrealm.account.dto.AccountDto;
import com.openrealm.account.dto.AccountProvision;
import com.openrealm.account.dto.AccountSubscription;
import com.openrealm.account.dto.CharacterDto;
import com.openrealm.account.dto.PlayerAccountDto;
import com.openrealm.net.test.StressTestClient;
import com.openrealm.game.contants.StatusEffectType;
import com.openrealm.game.contants.GlobalConstants;
import com.openrealm.game.contants.LootTier;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.entity.Player;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.entity.item.LootContainer;
import com.openrealm.game.entity.Enemy;
import com.openrealm.game.math.Vector2f;
import java.util.Random;
import com.openrealm.game.contants.CharacterClass;
import com.openrealm.game.model.CharacterClassModel;
import com.openrealm.game.model.DungeonGraphNode;
import com.openrealm.game.model.MapModel;
import com.openrealm.game.model.PortalModel;
import com.openrealm.game.tile.Tile;
import com.openrealm.net.messaging.CommandType;
import com.openrealm.net.messaging.ServerCommandMessage;
import com.openrealm.net.realm.Realm;
import com.openrealm.net.realm.RealmManagerServer;
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
    
    // Handler methods are passed a reference to the current RealmManager, the
    // invoking Player object
    // and the ServerCommand message object.
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
        // Look up this players account to see if they are allowed
        // to run Admin server commands
        try {
        	final AccountProvision[] requiredProvisions = RESTRICTED_COMMAND_PROVISIONS.get(message.getCommand().toLowerCase());
        	if (requiredProvisions != null) {
        	    // Check cached provisions first, then fetch from API if not cached
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

    // Surface command errors to the player two ways: a CommandPacket
    // (commandId=4 SERVER_ERROR) for any client that cares, AND a TextPacket
    // so the failure shows up in chat as "[SYSTEM] Error: ...". Sending both
    // is cheap and guarantees the user sees feedback even if the CommandPacket
    // path is filtered or unhandled by a particular client.
    private static void sendCommandError(RealmManagerServer mgr, Player fromPlayer, int code, String reason) {
        try {
            final CommandPacket errorResponse = CommandPacket.createError(fromPlayer, code, reason);
            mgr.enqueueServerPacket(fromPlayer, errorResponse);
        } catch (Exception ignored) {}
        try {
            final TextPacket text = TextPacket.create("SYSTEM", fromPlayer.getName(), "Error: " + reason);
            mgr.enqueueServerPacket(fromPlayer, text);
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
			// Clear provision cache so changes take effect immediately
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
        case "att":
            target.getStats().setAtt(valueToSet);
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

    @CommandHandler(value="spawn", description="Spawn enemies by id. Usage: /spawn {ENEMY_ID} [COUNT]")
	@AdminRestrictedCommand(provisions={AccountProvision.OPENREALM_MODERATOR})
    public static void invokeEnemySpawn(RealmManagerServer mgr, Player target, ServerCommandMessage message)
            throws Exception {
        if (message.getArgs() == null || message.getArgs().isEmpty() || message.getArgs().size() > 2)
            throw new IllegalArgumentException("Usage: /spawn {ENEMY_ID} [COUNT]");

        final int enemyId = Integer.parseInt(message.getArgs().get(0));
        int count = 1;
        if (message.getArgs().size() == 2) {
            count = Integer.parseInt(message.getArgs().get(1));
            if (count < 1) {
                throw new IllegalArgumentException("COUNT must be >= 1");
            }
            // Cap to keep a single command from accidentally OOMing the box
            // — 5000 enemies × ~200 bytes each + collision/AI bookkeeping
            // is enough to stress-test a 2-vCPU instance.
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
        // Spawn N copies inside a fixed confined disc around the caller —
        // the radius does NOT grow with count so 1000+ enemies pile up in
        // the same testable space (the whole point of the stress-test
        // command). Random angle + sqrt(rand) radius gives a uniform-area
        // distribution within the disc instead of clumping at the edge.
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
            from.addEnemy(GameObjectUtils.getEnemyFromId(enemyId, spawnPos));
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

        // Snapshot first — we mutate the enemies map while iterating, so do
        // a separate pass to collect IDs and a second pass to remove. Skip
        // the heavy enemyDeath() flow (XP, loot, overseer notify, level-up
        // text) since this is intended for stress-test cleanup, not gameplay.
        // Skip INVINCIBLE entities so static NPCs (nexus healers, vault
        // healer, lobby bosses) don't get wiped by /kill stress-test cleanup
        // — they're tagged with permanentEffects:[6] and have no respawn path.
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

        // Explicit UnloadPacket: the per-player LoadPacket diff only knows
        // about the last 500 enemies it sent (MAX_ENEMIES_PER_LOAD cap), so
        // entities trimmed out of earlier ticks pile up on the client and
        // survive /kill. Broadcasting an unload with every killed id ensures
        // the client drops them regardless of what's in playerLoadState.
        if (killedIds.length > 0) {
            final com.openrealm.net.client.packet.UnloadPacket unload =
                com.openrealm.net.client.packet.UnloadPacket.from(
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

    @CommandHandler(value="event", description="Admin: spawn a realm event by id (no id = list). Usage: /event {EVENT_ID}")
    @AdminRestrictedCommand(provisions={AccountProvision.OPENREALM_MODERATOR})
    public static void invokeSpawnEvent(RealmManagerServer mgr, Player target, ServerCommandMessage message)
            throws Exception {
        // No args: print the event catalog so the admin can pick one without
        // grepping the data files.
        if (message.getArgs() == null || message.getArgs().size() < 1) {
            final StringBuilder sb = new StringBuilder("Realm events:");
            if (GameDataManager.REALM_EVENTS != null) {
                final java.util.List<com.openrealm.game.model.RealmEventModel> sorted =
                        new ArrayList<>(GameDataManager.REALM_EVENTS.values());
                sorted.sort(java.util.Comparator.comparingInt(
                        com.openrealm.game.model.RealmEventModel::getEventId));
                for (final com.openrealm.game.model.RealmEventModel ev : sorted) {
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
        final com.openrealm.game.model.RealmEventModel eventModel =
                GameDataManager.REALM_EVENTS.get(eventId);
        if (eventModel == null) {
            throw new IllegalArgumentException("No realm event with id " + eventId
                    + " — run /event with no args to list available ids");
        }

        // The overseer owns the spawn flow (setpiece stamp, boss spawn,
        // active-event tracking, minion-wave thresholds, minimap markers).
        // Static maps (nexus, vault) don't have an overseer — bail with a
        // clear error so the admin retries from a regular zone.
        final Realm playerRealm = mgr.findPlayerRealm(target.getId());
        if (playerRealm == null) {
            throw new IllegalStateException("No realm for player " + target.getName());
        }
        final com.openrealm.net.realm.RealmOverseer overseer = playerRealm.getOverseer();
        if (overseer == null) {
            throw new IllegalStateException(
                    "Current realm has no overseer (nexus/vault/static map) — run from an outdoor realm");
        }

        log.info("Player {} (admin) /event {} ({}) in realm {}",
                target.getName(), eventId, eventModel.getName(), playerRealm.getRealmId());
        // Drop the encounter ~6 tiles NORTH of the admin so the player
        // doesn't end up standing on the boss / inside the setpiece.
        // Setpieces terraform freely under whatever's there, so the
        // spawn cannot fail for placement reasons.
        final int tileSize = com.openrealm.game.contants.GlobalConstants.BASE_TILE_SIZE;
        final com.openrealm.game.math.Vector2f spawnAt = target.getPos().clone();
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
        // Refresh the cached fame total so the next /fame-store open reflects it.
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
            // Only allow teleport within the same realm — cross-realm teleport
            // would place the player at coordinates in the wrong map
            final Realm targetRealm = mgr.findPlayerRealm(target.getId());
            final Realm destRealm = mgr.findPlayerRealm(destPlayer.getId());
            if (targetRealm == null || destRealm == null || targetRealm.getRealmId() != destRealm.getRealmId()) {
                throw new IllegalArgumentException("Cannot teleport to " + destPlayer.getName() + " — they are in a different area.");
            }
            // Check teleportable (not invisible/stasis)
            if (destPlayer.hasEffect(com.openrealm.game.contants.StatusEffectType.INVISIBLE)
                    || destPlayer.hasEffect(com.openrealm.game.contants.StatusEffectType.STASIS)) {
                throw new IllegalArgumentException(destPlayer.getName() + " cannot be teleported to right now.");
            }
            target.setPos(destPlayer.getPos().clone());
            mgr.enqueueServerPacket(target,
                    com.openrealm.net.server.packet.TextPacket.from("SYSTEM", target.getName(),
                            "Teleported to " + destPlayer.getName()));
        }
    }

    @CommandHandler(value="item", description="Spawn a given Item by its id. Usage: /item {ITEM_ID} [COUNT]")
	@AdminRestrictedCommand(provisions={AccountProvision.OPENREALM_MODERATOR})
    public static void invokeSpawnItem(RealmManagerServer mgr, Player target, ServerCommandMessage message)
            throws Exception {
        if (message.getArgs() == null || message.getArgs().size() < 1)
            throw new IllegalArgumentException("Usage: /item {ITEM_ID} [COUNT]");
        log.info("Player {} spawn item {}", target.getName(), message);
        final Realm targetRealm = mgr.findPlayerRealm(target.getId());
        final int gameItemId = Integer.parseInt(message.getArgs().get(0));
        final GameItem itemTemplate = GameDataManager.GAME_ITEMS.get(gameItemId);
        if (itemTemplate == null) {
            throw new IllegalArgumentException("Item with ID " + gameItemId + " does not exist.");
        }

        // Stackables (shards, essence, potions): COUNT is the requested stack
        // size, capped at the item's maxStack. Spawns one item with that
        // stackCount rather than COUNT separate copies.
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

        // Non-stackables: COUNT is the number of separate copies (capped at 32),
        // packed into loot bags of 8.
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

    @CommandHandler(value="portal", description="Spawn a portal to a map by name. Usage: /portal {MAP_NAME}")
	@AdminRestrictedCommand(provisions={AccountProvision.OPENREALM_MODERATOR})
    public static void invokeSpawnPortal(RealmManagerServer mgr, Player target, ServerCommandMessage message)
            throws Exception {
        if (message.getArgs() == null || message.getArgs().size() < 1)
            throw new IllegalArgumentException("Usage: /portal {MAP_NAME}");

        final String mapName = String.join(" ", message.getArgs());
        log.info("Player {} spawning portal to map '{}'", target.getName(), mapName);

        // Find map by name (case-insensitive, supports partial match)
        MapModel targetMap = null;
        for (MapModel m : GameDataManager.MAPS.values()) {
            if (m.getMapName().equalsIgnoreCase(mapName)) {
                targetMap = m;
                break;
            }
        }
        // Fallback: partial match
        if (targetMap == null) {
            for (MapModel m : GameDataManager.MAPS.values()) {
                if (m.getMapName().toLowerCase().contains(mapName.toLowerCase())) {
                    targetMap = m;
                    break;
                }
            }
        }
        if (targetMap == null) {
            // List available maps in error message
            final String available = GameDataManager.MAPS.values().stream()
                    .map(m -> m.getMapName() + " (" + m.getMapId() + ")")
                    .collect(Collectors.joining(", "));
            throw new IllegalArgumentException("Map '" + mapName + "' not found. Available: " + available);
        }

        // Find a portal model that targets this map, or fall back to a generic portal
        PortalModel portalModel = null;
        for (PortalModel pm : GameDataManager.PORTALS.values()) {
            if (pm.getMapId() == targetMap.getMapId()) {
                portalModel = pm;
                break;
            }
        }
        // Fall back to dungeon portal (portalId 6) if no matching portal model
        if (portalModel == null) {
            portalModel = GameDataManager.PORTALS.get(6);
        }

        // Check if a shared dungeon graph node exists for this map
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

        // Create and link portal at player position
        final com.openrealm.game.entity.Portal portal = new com.openrealm.game.entity.Portal(
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
        if (count < 1 || count > 50)
            throw new IllegalArgumentException("Count must be between 1 and 50");

        final boolean spamMode = message.getArgs().size() >= 2
                && "spam".equalsIgnoreCase(message.getArgs().get(1));
        final String modeLabel = spamMode ? " (spam mode - wizards)" : " (walk mode)";

        mgr.enqueueServerPacket(target, TextPacket.from("SYSTEM", target.getName(),
                "Spawning " + count + " bot players" + modeLabel + "..."));

        final String serverHost = "127.0.0.1";
        final int serverPort = 2222;
        final float spawnX = target.getPos().x;
        final float spawnY = target.getPos().y;

        WorkerThread.doAsync(() -> {
            // Phase 1: Pre-create ALL accounts and characters in parallel batches of 10
            final List<String[]> botCredentials = java.util.Collections.synchronizedList(new ArrayList<>());
            log.info("[BOTS] Phase 1: Creating {} accounts (10 at a time)...", count);
            for (int batch = 0; batch < count; batch += 10) {
                final int batchEnd = Math.min(batch + 10, count);
                final List<Thread> batchThreads = new ArrayList<>();
                for (int i = batch; i < batchEnd; i++) {
                    final int idx = i;
                    Thread t = new Thread(() -> {
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
                            log.info("[BOTS] Pre-created {} (class={}, uuid={})", botName, classId, characterUuid);
                            botCredentials.add(new String[]{email, password, characterUuid, accountGuid});

                            synchronized (BOT_ACCOUNT_GUIDS) {
                                BOT_ACCOUNT_GUIDS.add(accountGuid);
                            }
                        } catch (Exception e) {
                            log.error("[BOTS] Failed to create bot account {}: {}", idx, e.getMessage());
                        }
                    }, "bot-create-" + idx);
                    t.setDaemon(true);
                    t.start();
                    batchThreads.add(t);
                }
                // Wait for this batch to finish before starting the next
                for (Thread t : batchThreads) {
                    try { t.join(10000); } catch (InterruptedException e) { break; }
                }
                log.info("[BOTS] Batch complete: {}/{} accounts created", botCredentials.size(), count);
            }

            // Phase 2: Connect bots one at a time with stagger (fast, just TCP + login)
            log.info("[BOTS] Phase 2: Connecting {} bots...", botCredentials.size());
            int success = 0;
            for (int i = 0; i < botCredentials.size(); i++) {
                try {
                    final String[] creds = botCredentials.get(i);
                    final StressTestClient bot = new StressTestClient(i, serverHost, serverPort,
                            creds[0], creds[1], creds[2], spamMode);
                    bot.setSpawnNear(spawnX, spawnY);
                    synchronized (ACTIVE_BOTS) {
                        ACTIVE_BOTS.add(bot);
                    }
                    Thread botThread = new Thread(bot, "bot-runner-" + i);
                    botThread.setDaemon(true);
                    botThread.start();

                    // Wait for this bot to log in (2s timeout — should take <500ms)
                    long waitStart = System.currentTimeMillis();
                    while (!bot.isLoggedIn() && !bot.isShutdown()
                            && (System.currentTimeMillis() - waitStart) < 2000) {
                        Thread.sleep(50);
                    }
                    if (bot.isLoggedIn()) {
                        success++;
                        // Give bot godmode (INVINCIBLE 24h) so it doesn't die during stress test
                        try {
                            final Player botPlayer = mgr.getPlayers().stream()
                                    .filter(p -> p.getId() == bot.getAssignedPlayerId())
                                    .findFirst().orElse(null);
                            if (botPlayer != null) {
                                botPlayer.addEffect(StatusEffectType.INVINCIBLE, 1000L * 60 * 60 * 24);
                            }
                        } catch (Exception ex) {
                            log.warn("[BOTS] Failed to set bot {} godmode: {}", i, ex.getMessage());
                        }
                        log.info("[BOTS] Bot {} logged in successfully (godmode ON), connecting next...", i);
                    } else {
                        log.warn("[BOTS] Bot {} failed to log in within 5s, continuing...", i);
                    }
                } catch (Exception e) {
                    log.error("[BOTS] Failed to connect bot {}: {}", i, e.getMessage());
                }
            }
            log.info("[BOTS] Spawned {}/{} bot players", success, count);
            try {
                mgr.enqueueServerPacket(target, TextPacket.from("SYSTEM", target.getName(),
                        "Spawned " + success + "/" + count + " bot players"));
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

            // Step 1: Scan ALL realms for orphan bot Players whose StressTestClient
            // is no longer tracked (server was restarted, client crashed, etc.).
            // The in-memory ACTIVE_BOTS list is JVM-static and gets wiped on
            // restart while the bot Player objects + DB accounts persist —
            // before this scan, /killbots would report "0 bots" even though
            // ghost bots were visible standing around in the realm. Bots are
            // identified by the canonical "Bot_" name prefix (see spawnbots
            // line ~531) which never collides with real players.
            try {
                final java.util.List<Player> allPlayers = mgr.getPlayers();
                for (final Player p : allPlayers) {
                    final String name = p.getName();
                    if (name != null && name.startsWith("Bot_")) {
                        try {
                            mgr.disconnectPlayer(p, "killbots cleanup");
                            orphans++;
                            // Track the account guid for deletion below.
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

            // Step 2: Shutdown all tracked bot clients
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

            // Delete bot accounts from database
            synchronized (BOT_ACCOUNT_GUIDS) {
                for (String accountGuid : BOT_ACCOUNT_GUIDS) {
                    try {
                        // Get account to find characters
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
            // Anyone can go up to the overworld
            currentRealm.getPlayers().remove(target.getId());
            currentRealm.removePlayer(target);
            final Realm topRealm = mgr.getTopRealm();
            target.setPos(topRealm.getTileManager().getSafePosition());
            topRealm.addPlayer(target);
            mgr.clearPlayerState(target.getId());
            mgr.invalidateRealmLoadState(topRealm);
            ServerGameLogic.sendImmediateLoadMap(mgr, topRealm, target);
            ServerGameLogic.onPlayerJoin(mgr, topRealm, target);

            // Clean up empty dungeon when last player leaves
            if (currentRealm.getPlayers().size() == 0 && currentRealm.getNodeId() != null) {
                final com.openrealm.game.model.DungeonGraphNode node =
                        GameDataManager.DUNGEON_GRAPH.get(currentRealm.getNodeId());
                if (node != null && !node.isEntryPoint()) {
                    currentRealm.setShutdown(true);
                    mgr.getRealms().remove(currentRealm.getRealmId());
                }
            }
        } else if (direction.equals("down")) {
            // Admin only — check inline
            boolean isAdmin = false;
            try {
                final AccountDto account = ServerGameLogic.DATA_SERVICE.executeGet(
                        "/admin/account/" + target.getAccountUuid(), null, AccountDto.class);
                isAdmin = account != null && account.isAdmin();
            } catch (Exception e) {
                // Failed to check — deny
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

}
