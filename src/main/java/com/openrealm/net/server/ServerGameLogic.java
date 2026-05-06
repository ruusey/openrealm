package com.openrealm.net.server;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.openrealm.account.dto.AccountDto;
import com.openrealm.account.dto.CharacterDto;
import com.openrealm.account.dto.ChestDto;
import com.openrealm.account.dto.GameItemRefDto;
import com.openrealm.account.dto.LoginRequestDto;
import com.openrealm.account.dto.PlayerAccountDto;
import com.openrealm.account.dto.SessionTokenDto;
import com.openrealm.account.service.OpenRealmServerDataService;
import com.openrealm.game.contants.CharacterClass;
import com.openrealm.game.contants.EntityType;
import com.openrealm.game.contants.StatusEffectType;
import com.openrealm.game.contants.TextEffect;
import com.openrealm.game.contants.GlobalConstants;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.entity.Bullet;
import com.openrealm.game.entity.Enemy;
import com.openrealm.game.entity.Player;
import com.openrealm.game.entity.Portal;
import com.openrealm.game.entity.item.CombatModifiers;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.math.Vector2f;
import com.openrealm.game.model.DungeonGenerationParams;
import com.openrealm.game.model.DungeonGraphNode;
import com.openrealm.game.model.MapModel;
import com.openrealm.game.model.PortalModel;
import com.openrealm.util.GameObjectUtils;
import com.openrealm.game.model.Projectile;
import com.openrealm.game.model.ProjectileEffect;
import com.openrealm.game.model.ProjectileGroup;
import com.openrealm.net.Packet;
import com.openrealm.net.messaging.CommandType;
import com.openrealm.net.messaging.LoginRequestMessage;
import com.openrealm.net.messaging.LoginResponseMessage;
import com.openrealm.net.realm.Realm;
import com.openrealm.net.realm.RealmManagerServer;
import com.openrealm.net.server.packet.CommandPacket;
import com.openrealm.net.server.packet.DeathAckPacket;
import com.openrealm.net.server.packet.HeartbeatPacket;
import com.openrealm.net.server.packet.LoginAckPacket;
import com.openrealm.net.server.packet.MoveItemPacket;
import com.openrealm.net.server.packet.PlayerMovePacket;
import com.openrealm.net.server.packet.PlayerShootPacket;
import com.openrealm.net.server.packet.TextPacket;
import com.openrealm.net.server.packet.UseAbilityPacket;
import com.openrealm.net.server.packet.UsePortalPacket;
import com.openrealm.net.client.packet.LoadMapPacket;
import com.openrealm.net.client.packet.LoadPacket;
import com.openrealm.net.client.packet.ObjectMovePacket;
import com.openrealm.net.client.packet.UpdatePacket;
import com.openrealm.net.core.IOService;
import com.openrealm.net.entity.NetBullet;
import com.openrealm.net.entity.NetEnemy;
import com.openrealm.net.entity.NetLootContainer;
import com.openrealm.net.entity.NetPlayer;
import com.openrealm.net.entity.NetPortal;
import com.openrealm.net.entity.NetTile;
import com.openrealm.game.entity.item.LootContainer;
import com.openrealm.game.entity.GameObject;
import com.openrealm.util.PacketHandlerServer;
import com.openrealm.util.WorkerThread;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ServerGameLogic {
	public static final String GAME_VERSION = "0.6.0";
	public static final int WINDOW_WIDTH = 1920;
	public static final int WINDOW_HEIGHT = 1080;

	/**
	 * As of release 0.3.0 DATA_SERVICE static class variable is required for Game
	 * functionality
	 */
	public static OpenRealmServerDataService DATA_SERVICE = null;

	public static void sendImmediateLoadMap(RealmManagerServer mgr, Realm realm, Player player) {
		try {
			final NetTile[] tiles = realm.getTileManager().getLoadMapTiles(player);
			final LoadMapPacket loadMap = LoadMapPacket.from(realm.getRealmId(),
					(short) realm.getMapId(), realm.getTileManager().getMapWidth(),
					realm.getTileManager().getMapHeight(), tiles);
			mgr.getPlayerLoadMapState().put(player.getId(), loadMap);
			mgr.enqueueServerPacket(player, loadMap);

			// Send player's position so the client knows where they are in the new realm
			final ObjectMovePacket posPacket = ObjectMovePacket.from(new GameObject[]{ player });
			mgr.enqueueServerPacket(player, posPacket);
		} catch (Exception e) {
			log.error("[SERVER] Failed to send immediate LoadMap on realm transition. Reason: {}", e.getMessage());
		}
	}

	public static void onPlayerJoin(RealmManagerServer mgr, Realm realm, Player player) {
		// Show dungeon graph node name if available, otherwise fallback to realm ID
		String zoneName;
		if (realm.getNodeId() != null && GameDataManager.DUNGEON_GRAPH != null) {
			DungeonGraphNode node = GameDataManager.DUNGEON_GRAPH.get(realm.getNodeId());
			zoneName = (node != null) ? node.getDisplayName() : realm.getNodeId();
		} else {
			zoneName = "Realm " + realm.getRealmId();
		}
		TextPacket text = TextPacket.create("SYSTEM", player.getName(), zoneName);
		mgr.enqueueServerPacket(player, text);

		// Overseer welcome message
		if (realm.getOverseer() != null) {
			realm.getOverseer().welcomePlayer(player);
		}

		// Show online player list when joining the overworld (beach entry node)
		if (realm.getNodeId() != null && realm.getNodeId().startsWith("beach")) {
			final StringBuilder sb = new StringBuilder("Players online: ");
			int count = 0;
			for (Player p : mgr.getPlayers()) {
				if (p.getId() == player.getId()) continue;
				if (count > 0) sb.append(", ");
				sb.append(p.getName());
				count++;
			}
			if (count == 0) {
				sb.append("(none)");
			}
			mgr.enqueueServerPacket(player, TextPacket.create("SYSTEM", player.getName(), sb.toString()));
		}
	}

	public static void handleUsePortalServer(RealmManagerServer mgr, Packet packet) {
		final UsePortalPacket usePortalPacket = (UsePortalPacket) packet;
		if (!validateCallingPlayer(mgr, packet, usePortalPacket.getPlayerId())) {
			return;
		}
		// CRITICAL: wrap entire body in try/finally so the realm lock is always
		// released. Previously any HTTP exception (executePost to /data/...),
		// realm generation failure, or NPE would leak the lock and deadlock the
		// next tick's acquireRealmLock() call — server freeze, requires restart.
		mgr.acquireRealmLock();
		try {

		if (usePortalPacket.isToVault()) {
			final Realm currentRealm = mgr.getRealms().get(usePortalPacket.getFromRealmId());
			if (currentRealm == null || currentRealm.getMapId() == 1) {
				return;
			}

			final Player user = currentRealm.getPlayers().remove(usePortalPacket.getPlayerId());

			final MapModel mapModel = GameDataManager.MAPS.get(1);
			final Realm generatedRealm = new Realm(true, 1, "vault");
			final Vector2f chestLoc = new Vector2f((0 + (1920 / 2)) - 450, (0 + (1080 / 2)) - 300);
			final Portal exitPortal = new Portal(Realm.RANDOM.nextLong(), (short) 3, chestLoc);
			exitPortal.setNeverExpires();
			final Realm vaultExitTarget = mgr.getTopRealm();
			exitPortal.linkPortal(generatedRealm, vaultExitTarget != null ? vaultExitTarget : currentRealm);
			generatedRealm.setupChests(user);
			user.setPos(mapModel.getCenter());
			generatedRealm.addPortal(exitPortal);
			mgr.addRealm(generatedRealm);
			user.addEffect(StatusEffectType.INVINCIBLE, 4000);
			mgr.broadcastTextEffect(EntityType.PLAYER, user, TextEffect.PLAYER_INFO, "Invincible");
			generatedRealm.addPlayer(user);
			mgr.clearPlayerState(user.getId());
			mgr.invalidateRealmLoadState(generatedRealm);
			sendImmediateLoadMap(mgr, generatedRealm, user);
			onPlayerJoin(mgr, generatedRealm, user);
			return;
		}

		if (usePortalPacket.isToNexus()) {
			final Realm currentRealm = mgr.getRealms().get(usePortalPacket.getFromRealmId());
			if (currentRealm == null) { return; }
			final Realm nexus = mgr.getTopRealm();
			if (nexus == null || nexus.getRealmId() == currentRealm.getRealmId()) {
				return;
			}
			final Player user = currentRealm.getPlayers().remove(usePortalPacket.getPlayerId());
			if (user == null) { return; }
			currentRealm.removePlayer(user);

			// Save vault chests if leaving vault. Use serializeChestsForSave
			// so a setupChests-not-yet-complete race can't push an empty
			// list and wipe the persisted vault.
			if (currentRealm.getMapId() == 1) {
				try {
					List<ChestDto> chestsToSave = currentRealm.serializeChestsForSave();
					if (chestsToSave != null) {
						ServerGameLogic.DATA_SERVICE.executePost(
								"/data/account/" + user.getAccountUuid() + "/chest", chestsToSave, PlayerAccountDto.class);
					}
				} catch (Exception e) {
					log.error("[SERVER] Failed to save vault chests: {}", e.getMessage());
				}
				currentRealm.setShutdown(true);
				mgr.getRealms().remove(currentRealm.getRealmId());
			}

			// Clean up empty dungeons
			if (currentRealm.getPlayers().isEmpty() && currentRealm.getNodeId() != null
					&& !"nexus".equals(currentRealm.getNodeId())) {
				DungeonGraphNode node = GameDataManager.DUNGEON_GRAPH.get(currentRealm.getNodeId());
				if (node != null && !node.isShared()) {
					currentRealm.setShutdown(true);
					mgr.getRealms().remove(currentRealm.getRealmId());
				}
			}

			final MapModel nexusMap = GameDataManager.MAPS.get(nexus.getMapId());
			user.setPos(nexusMap != null ? nexusMap.getRandomSpawnPoint() : nexus.getTileManager().getSafePosition());
			user.addEffect(StatusEffectType.INVINCIBLE, 4000);
			mgr.broadcastTextEffect(EntityType.PLAYER, user, TextEffect.PLAYER_INFO, "Invincible");
			nexus.addPlayer(user);
			mgr.clearPlayerState(user.getId());
			mgr.invalidateRealmLoadState(nexus);
			sendImmediateLoadMap(mgr, nexus, user);
			onPlayerJoin(mgr, nexus, user);
			return;
		}

		final Realm currentRealm = mgr.getRealms().get(usePortalPacket.getFromRealmId());
		if (currentRealm == null) { return; }
		final Player user = currentRealm.getPlayers().remove(usePortalPacket.getPlayerId());
		if (user == null) { return; }
		final Portal used = currentRealm.getPortals().get(usePortalPacket.getPortalId());
		if (used == null) { return; }
		Realm targetRealm = mgr.getRealms().get(used.getToRealmId());
		final PortalModel portalUsed = GameDataManager.PORTALS.get((int) used.getPortalId());
		currentRealm.removePlayer(user);

		// Save + remove vault realm if leaving a vault (regardless of target).
		// serializeChestsForSave returns null if setupChests hasn't completed
		// — skip the POST so we don't bulk-replace the persisted vault with []
		// and wipe the player's chests.
		if (currentRealm.getMapId() == 1) {
			try {
				List<ChestDto> chestsToSave = currentRealm.serializeChestsForSave();
				if (chestsToSave != null) {
					ServerGameLogic.DATA_SERVICE.executePost(
							"/data/account/" + user.getAccountUuid() + "/chest", chestsToSave, PlayerAccountDto.class);
					log.info("[SERVER] Saved vault chests for {} on portal exit", user.getName());
				}
			} catch (Exception e) {
				log.error("[SERVER] Failed to save vault chests on portal exit: {}", e.getMessage());
			}
			currentRealm.setShutdown(true);
			mgr.getRealms().remove(currentRealm.getRealmId());
		}

		// Resolve target node from dungeon graph
		final String targetNodeId = used.getTargetNodeId();
		final DungeonGraphNode targetNode = (targetNodeId != null)
				? GameDataManager.DUNGEON_GRAPH.get(targetNodeId) : null;

		// Shared nodes (e.g., overworld): all portals route to the same realm instance.
		// Non-shared nodes (dungeons): each portal creates a unique instance.
		// Also check by mapId for legacy portals that lack a targetNodeId.
		DungeonGraphNode resolvedNode = targetNode;
		if (resolvedNode == null && portalUsed != null) {
			for (DungeonGraphNode node : GameDataManager.DUNGEON_GRAPH.values()) {
				if (node.getMapId() == portalUsed.getMapId() && node.isShared()) {
					resolvedNode = node;
					break;
				}
			}
		}
		if (targetRealm == null && resolvedNode != null && resolvedNode.isShared()) {
			Optional<Realm> existing = mgr.findRealmForNode(resolvedNode.getNodeId());
			if (existing.isPresent()) {
				targetRealm = existing.get();
				used.setToRealmId(targetRealm.getRealmId());
			}
		}

		if (targetRealm == null) {
			// Non-shared: each portal creates its own dungeon instance (1:1 portal-to-dungeon).
			// Realm generation is CPU-heavy (terrain, enemies, dungeon layout), so we run
			// it on a worker thread to avoid blocking the tick loop for other players.
			final String resolvedNodeId = (resolvedNode != null) ? resolvedNode.getNodeId() : targetNodeId;
			final int mapId = (resolvedNode != null) ? resolvedNode.getMapId() : (targetNode != null ? targetNode.getMapId() : portalUsed.getMapId());
			final DungeonGraphNode finalTargetNode = targetNode;
			final Realm finalCurrentRealm = currentRealm;
			final Portal finalUsedPortal = used;

			log.info("[SERVER] Starting async realm generation for player {} (mapId={}, node={})",
				user.getName(), mapId, resolvedNodeId);

			WorkerThread.doAsync(() -> {
				try {
					final Realm generatedRealm = new Realm(true, mapId, resolvedNodeId);
					generatedRealm.setSourceRealmId(finalCurrentRealm.getRealmId());

					final boolean isBossNode = (finalTargetNode != null && finalTargetNode.isBossNode());
					Vector2f entrySpawnPos = null;

					if (isBossNode || generatedRealm.getMapId() == 5) {
						entrySpawnPos = new Vector2f(GlobalConstants.BASE_TILE_SIZE * 16,
								GlobalConstants.BASE_TILE_SIZE * 12);
					} else {
						generatedRealm.spawnRandomEnemies(generatedRealm.getMapId());
						final Vector2f spawnPos = generatedRealm.getTileManager().getPlayerSpawnPos();
						entrySpawnPos = (spawnPos != null) ? spawnPos : generatedRealm.getTileManager().getSafePosition();
					}
					user.setPos(entrySpawnPos.clone());

					final Vector2f bossSpawnPos = generatedRealm.getTileManager().getBossSpawnPos();
					final MapModel mapModel = GameDataManager.MAPS.get(mapId);
					if (bossSpawnPos != null && mapModel != null && mapModel.getDungeonParams() != null) {
						final DungeonGenerationParams dungeonParams = mapModel.getDungeonParams();
						final int bossEnemyId = dungeonParams.getBossEnemyId();
						if (bossEnemyId > 0) {
							final Enemy boss = GameObjectUtils.getEnemyFromId(bossEnemyId, bossSpawnPos.clone());
							final int bossMult = (finalTargetNode != null) ? (int) finalTargetNode.getDifficulty() : 4;
							boss.setHealth(boss.getHealth() * bossMult);
							boss.getStats().setHp((short) (boss.getStats().getHp() * bossMult));
							generatedRealm.addEnemy(boss);
							generatedRealm.setDungeonBossEnemyId(bossEnemyId);
						}
					}

					if (entrySpawnPos != null) {
						final Vector2f cowardicePos = entrySpawnPos.clone(GlobalConstants.BASE_TILE_SIZE, 0);
						final Portal cowardicePortal = new Portal(Realm.RANDOM.nextLong(), (short) 3, cowardicePos);
						cowardicePortal.linkPortal(generatedRealm, finalCurrentRealm);
						cowardicePortal.setNeverExpires();
						generatedRealm.addPortal(cowardicePortal);
					}

					log.info("[SERVER] Async realm generation complete for player {} (mapId={}, node={})",
						user.getName(), mapId, resolvedNodeId);

					mgr.enqueuePendingTransition(new RealmManagerServer.PendingRealmTransition(
						generatedRealm, user, finalCurrentRealm, finalUsedPortal));
				} catch (Exception e) {
					log.error("[SERVER] Async realm generation failed for player {}. Reason: {}",
						user.getName(), e.getMessage(), e);
				}
			});
			// Player is removed from current realm but not yet in the new one.
			// They'll be added on the next tick when processPendingTransitions() runs.
			return;
		} else {
			// Target realm already exists — spawn at the dungeon's fixed entry point
			final Vector2f entryPos = targetRealm.getTileManager().getPlayerSpawnPos();
			if (entryPos != null) {
				// Spawn at dungeon entry room with slight random offset to avoid stacking
				user.setPos(entryPos.clone(
						Realm.RANDOM.nextInt(32) - 16,
						Realm.RANDOM.nextInt(32) - 16));
			} else if (targetRealm.getTileManager().getTerrainParams() != null) {
				// Overworld: spawn in beach zone
				user.setPos(targetRealm.getTileManager().getSafePosition());
			} else {
				// Static map: use spawn points if defined, otherwise safe position
				user.setPos(targetRealm.getTileManager().getSafePosition());
			}

			// Dungeon cleanup: remove non-shared realms when last player leaves via portal.
			if (currentRealm.getPlayers().size() == 0 && !currentRealm.isShared()) {
				ServerGameLogic.log.info("[SERVER] Removing empty dungeon realm {} (mapId={}, node={})",
						currentRealm.getRealmId(), currentRealm.getMapId(), currentRealm.getNodeId());
				currentRealm.setShutdown(true);
				mgr.getRealms().remove(currentRealm.getRealmId());
			}
		}
		user.addEffect(StatusEffectType.INVINCIBLE, 4000);
		mgr.broadcastTextEffect(EntityType.PLAYER, user, TextEffect.PLAYER_INFO, "Invincible");
		targetRealm.addPlayer(user);
		mgr.clearPlayerState(user.getId());
		mgr.invalidateRealmLoadState(targetRealm);
		sendImmediateLoadMap(mgr, targetRealm, user);
		onPlayerJoin(mgr, targetRealm, user);
		} catch (Exception e) {
			log.error("[SERVER] Portal transition failed. Reason: {}", e.getMessage(), e);
		} finally {
			mgr.releaseRealmLock();
		}
	}

	public static void handleHeartbeatServer(RealmManagerServer mgr, Packet packet) {
		final HeartbeatPacket heartbeatPacket = (HeartbeatPacket) packet;
		final Player player = mgr.getPlayerByRemoteAddress(packet.getSrcIp());
		if(player==null) {
			log.warn("[SERVER] Heartbeat from unknown player (srcIp={}). Known addresses: {}", packet.getSrcIp(), mgr.getRemoteAddresses().keySet());
			return;
		}
		// Store SERVER time when heartbeat was received (not client timestamp which may be clock-skewed)
		mgr.getPlayerLastHeartbeatTime().put(player.getId(), Instant.now().toEpochMilli());
		// Echo heartbeat back with the ORIGINAL client timestamp so the client
		// can measure true round-trip time (not first-random-packet latency).
		try {
			mgr.enqueueServerPacket(player, HeartbeatPacket.from(player.getId(), heartbeatPacket.getTimestamp()));
		} catch (Exception e) {
			log.debug("Failed to echo heartbeat: {}", e.getMessage());
		}
	}

	@PacketHandlerServer(LoginAckPacket.class)
	public static void handleLoginAckServer(RealmManagerServer mgr, Packet packet) {
		final LoginAckPacket ackPacket = (LoginAckPacket) packet;
		final Player player = mgr.getPlayerByRemoteAddress(packet.getSrcIp());
		if (player == null) {
			log.debug("Failed to process LoginAck. Player does not exist for {}", packet.getSrcIp());
			return;
		}
		final Realm playerRealm = mgr.findPlayerRealm(player.getId());
		if (playerRealm != null) {
			sendImmediateLoadMap(mgr, playerRealm, player);
			log.info("[SERVER] Sent initial LoadMap to {} on LoginAck", player.getName());
		}
	}

	public static void handlePlayerMoveServer(RealmManagerServer mgr, Packet packet) {
		final PlayerMovePacket playerMovePacket = (PlayerMovePacket) packet;
		if (!validateCallingPlayer(mgr, packet, playerMovePacket.getEntityId())) {
			return;
		}
		final Realm realm = mgr.findPlayerRealm(playerMovePacket.getEntityId());
		if (realm == null) {
			ServerGameLogic.log.error("Failed to get realm for player {}", playerMovePacket.getEntityId());
			return;
		}
		final Player toMove = realm.getPlayer(playerMovePacket.getEntityId());
		// Queue the input for processing in movePlayer() on the next tick
		toMove.queueInput(playerMovePacket.getSeq(), playerMovePacket.getVx(), playerMovePacket.getVy());
	}

	public static void handleUseAbilityServer(RealmManagerServer mgr, Packet packet) {
		final UseAbilityPacket useAbilityPacket = (UseAbilityPacket) packet;
		if (!validateCallingPlayer(mgr, packet, useAbilityPacket.getPlayerId())) {
			return;
		}
		final Realm realm = mgr.findPlayerRealm(useAbilityPacket.getPlayerId());
		mgr.useAbility(realm.getRealmId(), useAbilityPacket.getPlayerId(),
				new Vector2f(useAbilityPacket.getPosX(), useAbilityPacket.getPosY()));
		ServerGameLogic.log.debug("[SERVER] Recieved UseAbility Packet For Player {}", useAbilityPacket.getPlayerId());
	}

	@PacketHandlerServer(TextPacket.class)
	public static void handleText0(RealmManagerServer mgr, Packet packet) {
		final TextPacket textPacket = (TextPacket) packet;
		final long fromPlayerId = mgr.getRemoteAddresses().get(textPacket.getSrcIp());
		if (!validateCallingPlayer(mgr, packet, fromPlayerId)) {
			return;
		}
		final Player player = mgr.searchRealmsForPlayer(fromPlayerId);
		final Realm realm = mgr.findPlayerRealm(fromPlayerId);

		log.info("Player {} says {} from Realm {}", player.getName(), textPacket.getMessage(), realm.getRealmId());
	}

	public static void handlePlayerShootServer(RealmManagerServer mgr, Packet packet) {
		final PlayerShootPacket shootPacket = (PlayerShootPacket) packet;
		if (!validateCallingPlayer(mgr, packet, shootPacket.getEntityId())) {
			return;
		}

		final Realm realm = mgr.findPlayerRealm(shootPacket.getEntityId());
		if (realm == null) {
			ServerGameLogic.log.error("Failed to get realm for player {}", shootPacket.getEntityId());
			return;
		}
		final Player player = realm.getPlayer(shootPacket.getEntityId());
		boolean canShoot = false;
		if (realm.getPlayerLastShotTime().get(player.getId()) != null) {
			double dex = (int) ((6.5 * (player.getComputedStats().getDex() + 17.3)) / 75);
			if (player.hasEffect(StatusEffectType.SPEEDY)) {
				dex = dex * 1.5;
			}else if(player.hasEffect(StatusEffectType.DAZED)) {
				dex = 1.0;
			}
			canShoot = ((Instant.now().toEpochMilli() - realm.getPlayerLastShotTime().get(player.getId())) > (1000
					/ dex));
			if (canShoot && !player.hasEffect(StatusEffectType.STUNNED)) {
				realm.getPlayerLastShotTime().put(player.getId(), Instant.now().toEpochMilli());
			} else {
				canShoot = false;
			}
		} else {
			realm.getPlayerLastShotTime().put(player.getId(), Instant.now().toEpochMilli());
			canShoot = true;
		}
		if (canShoot) {
			// Trigger attack animation so other clients see the firing pose
			player.triggerAttackAnimation();
			player.setAimX(shootPacket.getDestX());
			player.setAimY(shootPacket.getDestY());
			final Vector2f dest = new Vector2f(shootPacket.getDestX(), shootPacket.getDestY());
			final Vector2f source = player.getCenteredPosition();
			final int weaponPgId = player.getWeaponId();
			final ProjectileGroup group = GameDataManager.PROJECTILE_GROUPS.get(weaponPgId);
			if (group == null) {
				log.error("No projectile group {} for weapon. Loaded groups: {}",
					weaponPgId, GameDataManager.PROJECTILE_GROUPS.keySet().stream().sorted()
					.map(String::valueOf).reduce((a,b)->a+","+b).orElse("none"));
				return;
			}
			final CombatModifiers cm =
					CombatModifiers.fromItem(player.getInventory()[0]);
			float angle = Bullet.getAngle(source, dest);
			for (Projectile proj : group.getProjectiles()) {
				short offset = (short) (player.getSize() / (short) 2);
				short rolledDamage = player.getInventory()[0].getDamage().getInRange();
				float shootAngle = angle + Float.parseFloat(proj.getAngle());
				rolledDamage += player.getComputedStats().getAtt();
				rolledDamage = applyCombatDamageMods(rolledDamage, cm);
				spawnPlayerBullet(mgr, realm.getRealmId(), player, weaponPgId, proj,
						source.clone(-offset, -offset), shootAngle, rolledDamage, cm);
				// Extra-projectile gems: fire N additional copies fanned around the base angle.
				for (int extra = 1; extra <= cm.getExtraProjectiles(); extra++) {
					final float side = (extra % 2 == 0) ? 1f : -1f;
					final float deltaA = side * 0.12f * ((extra + 1) / 2);
					final short bonusDmg = applyCombatDamageMods(rolledDamage, cm);
					spawnPlayerBullet(mgr, realm.getRealmId(), player, weaponPgId, proj,
							source.clone(-offset, -offset), shootAngle + deltaA, bonusDmg, cm);
				}
			}
		}
	}

	private static short applyCombatDamageMods(short base, CombatModifiers cm) {
		int dmg = base;
		if (cm.getDamagePct() != 0) dmg = dmg + (dmg * cm.getDamagePct()) / 100;
		if (cm.getCritChancePct() > 0) {
			final int roll = (int) (Math.random() * 100);
			if (roll < cm.getCritChancePct()) dmg = dmg * 2;
		}
		if (dmg < 0) dmg = 0;
		if (dmg > Short.MAX_VALUE) dmg = Short.MAX_VALUE;
		return (short) dmg;
	}

	private static void spawnPlayerBullet(RealmManagerServer mgr, long realmId, Player player, int weaponPgId,
			Projectile proj, Vector2f src, float angle, short damage,
			CombatModifiers cm) {
		final Bullet b = mgr.addProjectile(realmId, Realm.RANDOM.nextLong(), player.getId(), weaponPgId,
				proj.getProjectileId(), src, angle, proj.getSize(),
				proj.getMagnitude(), proj.getRange(), damage, false, proj.getFlags(), proj.getAmplitude(),
				proj.getFrequency(), player.getId());
		if (b == null) return;
		mergeProjectileEffects(b, proj, cm);
	}

	private static void mergeProjectileEffects(Bullet b, Projectile proj,
			CombatModifiers cm) {
		// Merge base projectile effects with any gem on-hit effects on the firing item.
		final List<ProjectileEffect> merged = new ArrayList<>();
		if (proj != null && proj.getEffects() != null) merged.addAll(proj.getEffects());
		if (cm != null) {
			for (CombatModifiers.OnHitEffect oh : cm.getOnHitEffects()) {
				final ProjectileEffect pe = new ProjectileEffect();
				pe.setEffectId((short) oh.getEffectId());
				pe.setDuration(oh.getDurationMs());
				merged.add(pe);
			}
		}
		if (!merged.isEmpty()) b.setEffects(merged);
	}

	public static void handleTextServer(RealmManagerServer mgr, Packet packet) {
		final TextPacket textPacket = (TextPacket) packet;
		final long fromPlayerId = mgr.getRemoteAddresses().get(textPacket.getSrcIp());
		if (!validateCallingPlayer(mgr, packet, fromPlayerId)) {
			return;
		}
		final Player fromPlayer = mgr.searchRealmsForPlayer(fromPlayerId);
		final Realm from = mgr.findPlayerRealm(fromPlayerId);
		try {
//            ServerGameLogic.log.info("[SERVER] Recieved Text Packet \nTO: {}\nFROM: {}\nMESSAGE: {}\nSrcIp: {}",
//                    textPacket.getTo(), textPacket.getFrom(), textPacket.getMessage(), textPacket.getSrcIp());

			String chatTo = fromPlayer.getChatRole() != null ? fromPlayer.getChatRole() : "";
			TextPacket toBroadcast = TextPacket.create(fromPlayer.getName(), chatTo, textPacket.getMessage());
			mgr.enqueueServerPacket(toBroadcast);
			ServerGameLogic.log.info("[SERVER] Broadcasted player chat message from {}", fromPlayer.getName());
		} catch (Exception e) {
			ServerGameLogic.log.error("Failed to send welcome message. Reason: {}", e);
		}
	}
	
	private static final byte LOGIN_REQUEST_MSG_CODE = 1;
	private static final byte SERVER_COMMAND_MSG_CODE = 3;
	
	public static void handleCommandServer(RealmManagerServer mgr, Packet packet) {
		final CommandPacket commandPacket = (CommandPacket) packet;
		if (commandPacket.getCommandId() != 1 && !validateCallingPlayer(mgr, packet, commandPacket.getPlayerId())) {
			return;
		}
		ServerGameLogic.log.info("[SERVER] Recieved Command Packet For Player {}. Command={}. SrcIp={}",
				commandPacket.getPlayerId(), commandPacket.getCommand(), commandPacket.getSrcIp());
		try {
			switch (commandPacket.getCommandId()) {
			case LOGIN_REQUEST_MSG_CODE:
				ServerGameLogic.doLogin(mgr, CommandType.fromPacket(commandPacket), commandPacket);
				break;
			case SERVER_COMMAND_MSG_CODE:
				ServerCommandHandler.invokeCommand(mgr, commandPacket);
				break;
			}
		} catch (Exception e) {
			ServerGameLogic.log.error("Failed to perform Server command for Player {}. Reason: {}",
					commandPacket.getPlayerId(), e.getMessage());
		}
	}

	public static void handleMoveItemServer(RealmManagerServer mgr, Packet packet) {
		final MoveItemPacket moveItemPacket = (MoveItemPacket) packet;
		if (!validateCallingPlayer(mgr, packet, moveItemPacket.getPlayerId())) {
			return;
		}
		try {
			ServerItemHelper.handleMoveItemPacket(mgr, packet);
			// Immediately send updated inventory AND container state so the client
			// sees the change without waiting for the next scheduled tick
			final Realm realm = mgr.findPlayerRealm(moveItemPacket.getPlayerId());
			if (realm != null) {
				final Player player = realm.getPlayer(moveItemPacket.getPlayerId());
				if (player != null) {
					// Send inventory update
					final UpdatePacket update = realm.getPlayerAsPacket(player.getId());
					if (update != null) {
						mgr.enqueueServerPacket(player, update);
					}
					// Send container update immediately to all nearby players.
					// Without this, container changes wait for the next LoadPacket tick
					// (up to 62ms at 16Hz), causing visible desync.
					final LootContainer nearLoot = mgr.getClosestLootContainer(
						realm.getRealmId(), player.getPos(), 32);
					if (nearLoot != null && nearLoot.getContentsChanged()) {
						try {
							final NetLootContainer netContainer = IOService.mapModel(nearLoot, NetLootContainer.class);
							final LoadPacket containerUpdate = new LoadPacket(
								new NetPlayer[0], new NetEnemy[0], new NetBullet[0],
								new NetLootContainer[] { netContainer },
								new NetPortal[0], (byte) 0);
							// Send to all players near the container, not just the mover
							for (final Map.Entry<Long, Player> p : realm.getPlayers().entrySet()) {
								if (p.getValue().isHeadless()) continue;
								float dx = p.getValue().getPos().x - nearLoot.getPos().x;
								float dy = p.getValue().getPos().y - nearLoot.getPos().y;
								if (dx * dx + dy * dy <= 640 * 640) {
									mgr.enqueueServerPacket(p.getValue(), containerUpdate);
								}
							}
						} catch (Exception ex) {
							ServerGameLogic.log.error("Failed to send immediate container update: {}", ex.getMessage());
						}
					}
				}
			}
		} catch (Exception e) {
			ServerGameLogic.log.error("Failed to handle MoveItem packet. Reason: {}", e);
		}
	}
	
	public static void handleConsumeShardStackServer(RealmManagerServer mgr, Packet packet) {
		try {
			ServerForgeHelper.handleConsumeShardStack(mgr, packet);
		} catch (Exception e) {
			ServerGameLogic.log.error("Failed to handle ConsumeShardStack packet. Reason: {}", e);
		}
	}

	public static void handleInteractTileServer(RealmManagerServer mgr, Packet packet) {
		try {
			ServerForgeHelper.handleInteractTile(mgr, packet);
		} catch (Exception e) {
			ServerGameLogic.log.error("Failed to handle InteractTile packet. Reason: {}", e);
		}
	}

	public static void handleForgeEnchantServer(RealmManagerServer mgr, Packet packet) {
		try {
			ServerForgeHelper.handleForgeEnchant(mgr, packet);
		} catch (Exception e) {
			ServerGameLogic.log.error("Failed to handle ForgeEnchant packet. Reason: {}", e);
		}
	}

	public static void handleForgeDisenchantServer(RealmManagerServer mgr, Packet packet) {
		try {
			ServerForgeHelper.handleForgeDisenchant(mgr, packet);
		} catch (Exception e) {
			ServerGameLogic.log.error("Failed to handle ForgeDisenchant packet. Reason: {}", e);
		}
	}

	public static void handleBuyFameItemServer(RealmManagerServer mgr, Packet packet) {
		try {
			ServerFameStoreHelper.handleBuy(mgr, packet);
		} catch (Exception e) {
			ServerGameLogic.log.error("Failed to handle BuyFameItem packet. Reason: {}", e);
		}
	}

	@PacketHandlerServer(DeathAckPacket.class)
	public static void handleDeathAckServer(RealmManagerServer mgr, Packet packet) {
		final DeathAckPacket deathPacket = (DeathAckPacket) packet;
		final Player real = mgr.getPlayerByRemoteAddress(packet.getSrcIp());
		if (!validateCallingPlayer(mgr, packet, deathPacket.getPlayerId())) {
			log.error("**DEATH ACK PLAYER ID DID NOT MATCH, REAL={}, attempted={}, BAN THEM**", real, deathPacket.getPlayerId());
			return;
		}
		final Player player = mgr.getPlayerById(deathPacket.getPlayerId());

		log.info("Handling death ack for player {}", player.getName());
		//final Realm playerRealm = mgr.findPlayerRealm(deathPacket.getPlayerId());
		mgr.disconnectPlayer(player, "death ack received (permadeath)");

	}

//	public static void handleLoadMapServer(RealmManagerServer mgr, Packet packet) {
//		@SuppressWarnings("unused")
//		final LoadMapPacket loadMapPacket = (LoadMapPacket) packet;
//	}

	private static void doLogin(RealmManagerServer mgr, LoginRequestMessage request, CommandPacket command) {
		log.info("[SERVER] Recieved login command {}", request);
		// Run the slow HTTP authentication on a worker thread so the tick loop isn't blocked
		WorkerThread.doAsync(() -> {
			CommandPacket commandResponse = null;
			long assignedId = -1l;
			Player player = null;
			try {
				SessionTokenDto loginToken;
				if (request.getToken() != null && !request.getToken().isEmpty()) {
					// Token-based auth: resolve the token to get account info
					loginToken = new SessionTokenDto();
					loginToken.setToken(request.getToken());
					AccountDto resolved = ServerGameLogic.DATA_SERVICE.executeGetWithToken(
						"/admin/account/token/resolve", request.getToken(), AccountDto.class);
					loginToken.setAccountGuid(resolved.getAccountGuid());
				} else {
					// Legacy email+password auth
					loginToken = ServerGameLogic.doLoginRemote(request.getEmail(), request.getPassword());
				}
				PlayerAccountDto account = ServerGameLogic.DATA_SERVICE.executeGet(
						"/data/account/" + loginToken.getAccountGuid(), null, PlayerAccountDto.class);
				String accountName = account.getAccountName();
				String accountUuid = account.getAccountUuid();
				Optional<CharacterDto> characterClass = account.getCharacters().stream()
						.filter(character -> character.getCharacterUuid().equals(request.getCharacterUuid())).findAny();
				if (characterClass.isEmpty()) {
					throw new Exception("Player character with UUID " + request.getCharacterUuid() + " does not exist");
				}

				final CharacterDto targetCharacter = characterClass.get();
				// Force disconnect any existing session for this account before proceeding.
				// This handles ghost players stuck in-game after a dirty disconnect.
				final boolean isBotAccount = request.getEmail() != null && request.getEmail().endsWith("@jrealm-bot.local");
				if (!isBotAccount) {
					boolean disconnectedExisting = false;
					for (Player existing : mgr.getPlayers()) {
						if (existing.getAccountUuid() != null && existing.getAccountUuid().equals(accountUuid)) {
							log.info("[SERVER] Force-disconnecting previous session for account {} (re-login). "
									+ "Existing player id={} name={}, new login srcIp={}",
								accountUuid, existing.getId(), existing.getName(), command.getSrcIp());
							mgr.disconnectPlayer(existing, "force-disconnected: same account re-logged in");
							disconnectedExisting = true;
							break;
						}
					}
					if (disconnectedExisting) {
						// Brief pause to let realm state settle after force-disconnect
						try { Thread.sleep(250); } catch (InterruptedException ignored) {}
						// Safety net: if the player is STILL in a realm after disconnect
						// (e.g. disconnectPlayer partially failed), forcibly remove them
						for (Player ghost : mgr.getPlayers()) {
							if (ghost.getAccountUuid() != null && ghost.getAccountUuid().equals(accountUuid)) {
								log.warn("[SERVER] Ghost player {} still present after disconnect, forcibly removing", ghost.getName());
								final Realm ghostRealm = mgr.findPlayerRealm(ghost.getId());
								if (ghostRealm != null) {
									ghostRealm.getExpiredPlayers().add(ghost.getId());
									ghostRealm.removePlayer(ghost);
								}
								mgr.clearPlayerState(ghost.getId());
							}
						}
					}
				}

				final Map<Integer, GameItem> loadedEquipment = new HashMap<>();
				for (final GameItemRefDto item : targetCharacter.getItems()) {
					loadedEquipment.put(item.getSlotIdx(), GameItem.fromGameItemRef(item));
				}

				assignedId = Realm.RANDOM.nextLong();
				Integer resolvedClassId = targetCharacter.getCharacterClass();
				if ((resolvedClassId == null || resolvedClassId == 0) && targetCharacter.getStats() != null
						&& targetCharacter.getStats().getClassId() != null && targetCharacter.getStats().getClassId() > 0) {
					resolvedClassId = targetCharacter.getStats().getClassId();
				}
				final CharacterClass cls = CharacterClass.valueOf(resolvedClassId != null ? resolvedClassId : 0);
				final Vector2f playerPos = new Vector2f((0 + (WINDOW_WIDTH / 2)) - GlobalConstants.PLAYER_SIZE - 350,
						(0 + (WINDOW_HEIGHT / 2)) - GlobalConstants.PLAYER_SIZE);
				player = new Player(assignedId, playerPos, GlobalConstants.PLAYER_SIZE, cls);
				final Realm targetRealm = mgr.getTopRealm();
				if (targetRealm == null) {
					throw new Exception("No top-level realm available. Server may still be starting up.");
				}
				final ClientSession userSession = mgr.getServer().getClients().get(command.getSrcIp());
				if (userSession == null) {
					throw new Exception("Client session not found for " + command.getSrcIp());
				}
				player.setAccountUuid(accountUuid);
				player.setCharacterUuid(targetCharacter.getCharacterUuid());
				player.equipSlots(loadedEquipment);
				// Relocate any equipped items that don't actually match their
				// slot type or class. This cleans up legacy-bad state on
				// every login so a single-time bug doesn't persist forever.
				ServerItemHelper.reconcileEquipment(player);
				player.applyStats(targetCharacter.getStats());
				player.setName(accountName);
				player.setHeadless(false);
				// Cache chat role from auth provisions for name coloring
				try {
					AccountDto authAccount = ServerGameLogic.DATA_SERVICE.executeGet(
						"/admin/account/" + loginToken.getAccountGuid(), null, AccountDto.class);
					// Order matters: highest-tier provision wins so an account
					// holding both ADMIN and EDITOR shows the admin badge.
					if (authAccount != null && authAccount.isSysAdmin()) player.setChatRole("sysadmin");
					else if (authAccount != null && authAccount.isAdmin()) player.setChatRole("admin");
					else if (authAccount != null && authAccount.isModerator()) player.setChatRole("mod");
					else if (authAccount != null && authAccount.isEditor()) player.setChatRole("editor");
					else if (authAccount != null && authAccount.isDemo()) player.setChatRole("demo");
					log.info("[SERVER] chatRole resolved for {}: role={}, provisions={}",
						accountName,
						player.getChatRole(),
						authAccount != null ? authAccount.getAccountProvisions() : "null");
				} catch (Exception roleEx) {
					log.warn("[SERVER] Could not fetch auth role for {}: {}", accountName, roleEx.getMessage());
				}
				if (isBotAccount) {
					player.setBot(true);
				}
				player.addEffect(StatusEffectType.INVINCIBLE, 4000);
				mgr.broadcastTextEffect(EntityType.PLAYER, player, TextEffect.PLAYER_INFO, "Invincible");
				player.setPos(targetRealm.getTileManager().getSafePosition());
				log.info("[SERVER] Queuing player {} for realm join. bot={}, headless={}, accountUuid={}",
						player.getName(), player.isBot(), player.isHeadless(), player.getAccountUuid());

				final LoginResponseMessage message = LoginResponseMessage.builder()
						.classId(resolvedClassId != null ? resolvedClassId : 0)
						.spawnX(player.getPos().x).spawnY(player.getPos().y)
						.playerId(player.getId()).success(true).account(account).token(loginToken.getToken())
						.chatRole(player.getChatRole()).build();

				commandResponse = CommandPacket.create(player, CommandType.LOGIN_RESPONSE, message);

				// Defer realm join to the tick thread so addPlayer + invalidateRealmLoadState
				// run atomically with the LoadPacket delta logic (no race condition).
				mgr.enqueuePendingJoin(new RealmManagerServer.PendingRealmJoin(
						targetRealm, player, command.getSrcIp(), userSession, commandResponse));
				log.info("[SERVER] Player {} login queued for tick-thread processing", player);
			} catch (Exception e) {
				ServerGameLogic.log.error("Failed to perform Client Login. Reason: {}", e);
				commandResponse = CommandPacket.createError(assignedId, 503,
						"Failed to perform Client Login. Reason: " + e.getMessage());
				// Only send error responses directly — successful logins are sent by processPendingJoins
				if (player != null && commandResponse != null) {
					mgr.enqueueServerPacket(player, commandResponse);
				}
			}
		});
	}

	private static SessionTokenDto doLoginRemote(final String userName, final String password) throws Exception {
		final LoginRequestDto loginReq = new LoginRequestDto(userName, password);
		final SessionTokenDto response = ServerGameLogic.DATA_SERVICE.executePost("/admin/account/login", loginReq,
				SessionTokenDto.class);
		return response;
	}

	// Looks up a player by source IP, which is determined by the server and less likely to be
	// vulnerable to spoofing assuming someone reverse engineers the packet protocol
	private static boolean validateCallingPlayer(RealmManagerServer mgr, Packet packet, Long declaredPlayerId) {
		final Long actualPlayerId = mgr.getRemoteAddresses().get(packet.getSrcIp());
		final Player actualPlayer = mgr.searchRealmsForPlayer(actualPlayerId);
		if (actualPlayer == null) {
			return false;
		}
		if (actualPlayer.getId() != declaredPlayerId) {
			// Logged with full identity so a re-login that immediately
			// disconnects with "player ID mismatch" is debuggable from a
			// single log line — previously we only had the raw ids.
			log.warn("[SERVER] validateCallingPlayer mismatch — packet={} from srcIp={}, "
					+ "actualPlayer={} (id={}), declaredId={}, accountUuid={}",
				packet.getClass().getSimpleName(), packet.getSrcIp(),
				actualPlayer.getName(), actualPlayerId, declaredPlayerId, actualPlayer.getAccountUuid());
			mgr.disconnectPlayer(actualPlayer, "player ID mismatch (actual=" + actualPlayerId + " declared=" + declaredPlayerId + ")");
			return false;
		}
		return true;
	}
}
