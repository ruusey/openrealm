package com.openrealm.net.server;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

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
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.entity.item.Stats;
import com.openrealm.game.entity.item.gem.Gemstone;
import com.openrealm.game.entity.item.gem.GemstoneRegistry;
import com.openrealm.game.entity.item.gem.ShotContext;
import com.openrealm.game.math.Vector2f;
import com.openrealm.game.model.DungeonGenerationParams;
import com.openrealm.game.model.DungeonGraphNode;
import com.openrealm.game.model.MapModel;
import com.openrealm.game.model.WeaponArchetypeModel;
import com.openrealm.game.model.ability.AbilityScaling;
import com.openrealm.game.model.ability.PassiveAbility;
import com.openrealm.game.model.ability.PassiveTrigger;
import com.openrealm.game.contants.ProjectileFlag;
import com.openrealm.net.client.packet.CreateEffectPacket;
import java.util.Set;
import com.openrealm.game.model.PortalModel;
import com.openrealm.util.GameObjectUtils;
import com.openrealm.game.model.Projectile;
import com.openrealm.game.model.ProjectileEffect;
import com.openrealm.game.model.ProjectileGroup;
import com.openrealm.net.Packet;
import com.openrealm.net.messaging.CommandType;
import com.openrealm.net.messaging.LoginRequestMessage;
import com.openrealm.net.messaging.LoginResponseMessage;
import com.openrealm.net.realm.PendingRealmJoin;
import com.openrealm.net.realm.PendingRealmTransition;
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
import com.openrealm.net.server.packet.InvestSkillPointPacket;
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

	/** Per-player throttle for the "invalid weapon equipped" notice (one per 5s). */
	private static final Map<Long, Long> INVALID_WEAPON_WARN_AT = new ConcurrentHashMap<>();
	private static final long INVALID_WEAPON_WARN_INTERVAL_MS = 5000L;
	public static final int WINDOW_WIDTH = 1920;
	public static final int WINDOW_HEIGHT = 1080;

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
		// MUST release the realm lock in finally — leaked locks deadlock the next tick.
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

			// serializeChestsForSave returns null if setupChests hasn't completed — skip save
			// so we don't wipe persisted vault with [].
			if (currentRealm.getMapId() == 1) {
				final List<ChestDto> chestsToSave = currentRealm.serializeChestsForSave();
				if (chestsToSave != null) {
					final String acctUuid = user.getAccountUuid();
					ServerGameLogic.DATA_SERVICE
							.executePostAsync("/data/account/" + acctUuid + "/chest",
									chestsToSave, PlayerAccountDto.class)
							.exceptionally(ex -> {
								log.error("[SERVER] Failed to save vault chests for {}: {}",
										acctUuid, ex.getMessage());
								return null;
							});
				}
				final List<ChestDto> storageToSave = currentRealm.serializePotionStorageForSave(user.getId());
				if (storageToSave != null) {
					final String acctUuid = user.getAccountUuid();
					ServerGameLogic.DATA_SERVICE
							.executePostAsync("/data/account/" + acctUuid + "/potion-storage",
									storageToSave, PlayerAccountDto.class)
							.exceptionally(ex -> {
								log.error("[SERVER] Failed to save potion storage for {}: {}",
										acctUuid, ex.getMessage());
								return null;
							});
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

		// Save + remove vault realm if leaving a vault. Skip POST when
		// serializeChestsForSave returns null (setupChests incomplete).
		if (currentRealm.getMapId() == 1) {
			final List<ChestDto> chestsToSave = currentRealm.serializeChestsForSave();
			if (chestsToSave != null) {
				final String acctUuid = user.getAccountUuid();
				final String userName = user.getName();
				ServerGameLogic.DATA_SERVICE
						.executePostAsync("/data/account/" + acctUuid + "/chest",
								chestsToSave, PlayerAccountDto.class)
						.thenAccept(resp -> log.info("[SERVER] Saved vault chests for {} on portal exit", userName))
						.exceptionally(ex -> {
							log.error("[SERVER] Failed to save vault chests on portal exit for {}: {}",
									userName, ex.getMessage());
							return null;
						});
			}
			final List<ChestDto> storageToSave = currentRealm.serializePotionStorageForSave(user.getId());
			if (storageToSave != null) {
				final String acctUuid = user.getAccountUuid();
				final String userName = user.getName();
				ServerGameLogic.DATA_SERVICE
						.executePostAsync("/data/account/" + acctUuid + "/potion-storage",
								storageToSave, PlayerAccountDto.class)
						.thenAccept(resp -> log.info("[SERVER] Saved potion storage for {} on portal exit", userName))
						.exceptionally(ex -> {
							log.error("[SERVER] Failed to save potion storage on portal exit for {}: {}",
									userName, ex.getMessage());
							return null;
						});
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
			// Non-shared dungeon instance — generate on worker thread (CPU-heavy).
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

					// Party-size bonus locked at entry: +0.5/member (cap +2.0); teammate joins later don't shift it.
					final long enteringPid = mgr.getPartyManager().getPartyId(user.getId());
					final int  partySize   = (enteringPid != 0L)
							? mgr.getPartyManager().getPartyMembers(user.getId()).size() : 1;
					final float partyBonus = Math.min(2.0f, 0.5f * Math.max(0, partySize - 1));
					generatedRealm.setPartyDifficultyBonus(partyBonus);
					generatedRealm.getDungeonPartyIds().add(
							enteringPid != 0L ? enteringPid : -user.getId());

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

					mgr.enqueuePendingTransition(new PendingRealmTransition(
						generatedRealm, user, finalCurrentRealm, finalUsedPortal));
				} catch (Exception e) {
					log.error("[SERVER] Async realm generation failed for player {}. Reason: {}",
						user.getName(), e.getMessage(), e);
				}
			});
			return;
		} else {
			// Single-party dungeon gate. Solo players use -playerId as a synthetic key.
			final MapModel targetMap = GameDataManager.MAPS.get(targetRealm.getMapId());
			if (targetMap != null && targetMap.getMaxPartyCount() > 0) {
				final long enteringPid = mgr.getPartyManager().getPartyId(user.getId());
				final long claimKey = enteringPid != 0L ? enteringPid : -user.getId();
				final Set<Long> claims = targetRealm.getDungeonPartyIds();
				if (!claims.contains(claimKey)) {
					if (claims.size() >= targetMap.getMaxPartyCount()) {
						currentRealm.addPlayer(user);
						mgr.enqueueServerPacket(user, TextPacket.from(
								"SYSTEM", user.getName(),
								"Dungeon is full — wait for the current party to clear it or finish."));
						return;
					}
					claims.add(claimKey);
				}
			}
			final Vector2f entryPos = targetRealm.getTileManager().getPlayerSpawnPos();
			if (entryPos != null) {
				// Small random offset prevents stacking at the dungeon entry tile.
				user.setPos(entryPos.clone(
						Realm.RANDOM.nextInt(32) - 16,
						Realm.RANDOM.nextInt(32) - 16));
			} else if (targetRealm.getTileManager().getTerrainParams() != null) {
				user.setPos(targetRealm.getTileManager().getSafePosition());
			} else {
				user.setPos(targetRealm.getTileManager().getSafePosition());
			}

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
		// Targeted invalidation: only the transitioning player needs a full Load snapshot.
		mgr.invalidateLoadStateForPlayer(user.getId());
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
		// Use server time (client timestamp may be clock-skewed).
		mgr.getPlayerLastHeartbeatTime().put(player.getId(), Instant.now().toEpochMilli());
		// Echo with original client timestamp so client can measure true RTT.
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
				new Vector2f(useAbilityPacket.getPosX(), useAbilityPacket.getPosY()),
				useAbilityPacket.getAbilityIndex());
		ServerGameLogic.log.info("[SERVER] Recieved UseAbility Packet For Player {}", useAbilityPacket.getPlayerId());
	}

	@PacketHandlerServer(InvestSkillPointPacket.class)
	public static void handleInvestSkillPointServer(RealmManagerServer mgr, Packet packet) {
		final InvestSkillPointPacket pkt = (InvestSkillPointPacket) packet;
		if (!validateCallingPlayer(mgr, packet, pkt.getPlayerId())) {
			return;
		}
		final Realm realm = mgr.findPlayerRealm(pkt.getPlayerId());
		if (realm == null) return;
		final Player player = realm.getPlayer(pkt.getPlayerId());
		if (player == null) return;
		final int slot = pkt.getSlot() & 0xff;
		if (slot < 0 || slot >= 4) return;
		final int abilityId = player.getHotbarId(slot);
		if (abilityId <= 0) return;
		final boolean ok = player.investSkillPoint(abilityId);
		if (ok && player.getMetrics() != null) {
			player.getMetrics().recordSkillPointSpent();
		}
		if (!ok) {
			ServerGameLogic.log.info("[SKILL-POINTS] player {} invest REJECTED slot={} abilityId={} (pool={} current={})",
					player.getId(), slot, abilityId, player.getAvailableSkillPoints(),
					player.getSkillLevel(abilityId));
			return;
		}
		ServerGameLogic.log.info("[SKILL-POINTS] player {} invest slot={} abilityId={} -> level={} (pool={})",
				player.getId(), slot, abilityId, player.getSkillLevel(abilityId),
				player.getAvailableSkillPoints());
		try {
			mgr.enqueueServerPacket(player, UpdatePacket.from(player));
		} catch (Exception e) {
			ServerGameLogic.log.warn("[SKILL-POINTS] failed to send UpdatePacket: {}", e.getMessage());
		}
	}

	// DO NOT add a @PacketHandlerServer(TextPacket.class) stub here — would shadow the
	// explicitly-registered handleTextServer and silently break chat broadcasting.

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
			// Archetype fire-rate multiplier applied before status buffs.
			final WeaponArchetypeModel archForRate = archetypeFor(player.getInventory()[0]);
			if (archForRate != null && archForRate.getAttackSpeedMul() > 0f) {
				dex = dex * archForRate.getAttackSpeedMul();
			}
			// BERSERK = +50% fire rate; SPEEDY is movement-only.
			if (player.hasEffect(StatusEffectType.BERSERK)) {
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
			// Reject shots from any weapon-slot item whose targetSlot != 0 (legacy ability-item exploit guard).
			final GameItem weaponItem = player.getInventory()[0];
			if (weaponItem != null && weaponItem.getTargetSlot() != 0) {
				final long now = Instant.now().toEpochMilli();
				final Long lastWarn = INVALID_WEAPON_WARN_AT.get(player.getId());
				if (lastWarn == null || (now - lastWarn) > INVALID_WEAPON_WARN_INTERVAL_MS) {
					INVALID_WEAPON_WARN_AT.put(player.getId(), now);
					try {
						mgr.enqueueServerPacket(player, TextPacket.from("SYSTEM", player.getName(),
								"Your equipped weapon (" + weaponItem.getName() + ") is no longer "
								+ "a valid weapon — class abilities replaced the legacy ability "
								+ "slot. Move it to your inventory and equip a real weapon."));
					} catch (Exception ignore) { }
				}
				return;
			}
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
			final GameItem weapon = player.getInventory()[0];
			final ShotContext ctx = new ShotContext();
			final Gemstone weaponGem = GemstoneRegistry.forItem(weapon);
			if (weaponGem != null) {
				weaponGem.modifyShot(ctx, player, weapon);
				weaponGem.onBasicAttack(player, weapon);
			}
			if (player.getMetrics() != null) {
				final int bullets = 1 + ctx.getExtraProjectiles();
				for (int b = 0; b < bullets; b++) {
					player.getMetrics().recordProjectileFired();
				}
			}

			// ON_BASIC_ATTACK passive trigger (Wizard Arcane Surge): every Nth shot is empowered ×1.5.
			boolean empoweredShot = false;
			final PassiveAbility classPassive = player.getClassPassive();
			if (classPassive != null) {
				for (PassiveTrigger trig : classPassive.triggerList()) {
					if (!"ON_BASIC_ATTACK".equalsIgnoreCase(trig.getEvent())) continue;
					int contribution = 0;
					if (trig.getScalings() != null) {
						for (AbilityScaling sc : trig.getScalings()) {
							if ("EMPOWER_FREQUENCY".equalsIgnoreCase(sc.getTarget())
									&& "WIS".equalsIgnoreCase(sc.getStat())) {
								contribution = (int) (player.getComputedStats().getWis() * sc.getCoeff());
							}
						}
					}
					final int N = Math.max(2, 5 - contribution);
					player.setBasicAttackCounter(player.getBasicAttackCounter() + 1);
					if (player.getBasicAttackCounter() >= N) {
						empoweredShot = true;
						player.setBasicAttackCounter(0);
						final Vector2f pcenter = player.getPos()
								.clone(player.getSize() / 2, player.getSize() / 2);
						mgr.enqueueServerPacketToRealm(realm,
								CreateEffectPacket.aoeEffect(
										CreateEffectPacket.EFFECT_WIZARD_BURST,
										pcenter.x, pcenter.y, 32f, (short) 450, (byte) 6));
					}
					break;
				}
			}

			float angle = Bullet.getAngle(source, dest);
			final WeaponArchetypeModel arch = archetypeFor(player.getInventory()[0]);
			for (Projectile proj : group.getProjectiles()) {
				short offset = (short) (player.getSize() / (short) 2);
				short rolledDamage = player.getInventory()[0].getDamage().getInRange();
				float shootAngle = angle + Float.parseFloat(proj.getAngle());
				rolledDamage += statByIndex(player.getComputedStats(),
						player.getInventory()[0].getScalingStat());
				if (arch != null && arch.getDamageMul() != 1.0f) {
					rolledDamage = (short) Math.min(Short.MAX_VALUE,
							Math.max(0, (int) (rolledDamage * arch.getDamageMul())));
				}
				rolledDamage = CombatMath.applyShotDamageMods(rolledDamage, ctx);
				if (empoweredShot) {
					rolledDamage = (short) Math.min(Short.MAX_VALUE, (int) (rolledDamage * 1.5f));
				}
				final int archCount = (arch == null) ? 1 : Math.max(1, arch.getProjectileCount());
				final int totalBullets = archCount + ctx.getExtraProjectiles();
				final float SPREAD = ((arch != null && arch.getSpreadRad() > 0f)
						? arch.getSpreadRad() : 0.12f) + ctx.getExtraSpread();
				for (int i = 0; i < totalBullets; i++) {
					final float deltaA = (i - (totalBullets - 1) / 2f) * SPREAD;
					final short dmg = (i == 0) ? rolledDamage : CombatMath.applyShotDamageMods(rolledDamage, ctx);
					spawnPlayerBullet(mgr, realm.getRealmId(), player, weaponPgId, proj,
							source.clone(-offset, -offset), shootAngle + deltaA, dmg, ctx, arch);
				}
			}
		}
	}

	/** Stat lookup: 0=VIT 1=WIS 2=HP 3=MP 4=STR 5=DEF 6=SPD 7=DEX. */
	private static int statByIndex(Stats s, int idx) {
		if (s == null) return 0;
		switch (idx) {
			case 0: return s.getVit();
			case 1: return s.getWis();
			case 2: return s.getHp();
			case 3: return s.getMp();
			case 4: return s.getStr();
			case 5: return s.getDef();
			case 6: return s.getSpd();
			case 7: return s.getDex();
			default: return s.getStr();
		}
	}

	private static void spawnPlayerBullet(RealmManagerServer mgr, long realmId, Player player, int weaponPgId,
			Projectile proj, Vector2f src, float angle, short damage,
			ShotContext ctx, WeaponArchetypeModel arch) {
		float range = proj.getRange();
		if (arch != null && arch.getRangeMul() != 1.0f) {
			range = range * arch.getRangeMul();
		}
		// Piercing flag: archetype OR gemstone, dedup against projectile's existing flags.
		List<Short> flags = proj.getFlags();
		final boolean wantPierce = (arch != null && arch.isPiercing()) || ctx.isPiercing();
		if (wantPierce) {
			final short pierceFlag = ProjectileFlag.PASS_THROUGH_ENEMIES.flagId;
			boolean already = false;
			if (flags != null) {
				for (Short f : flags) { if (f != null && f.shortValue() == pierceFlag) { already = true; break; } }
			}
			if (!already) {
				flags = (flags == null) ? new ArrayList<>() : new ArrayList<>(flags);
				flags.add(pierceFlag);
			}
		}
		final Bullet b = mgr.addProjectile(realmId, Realm.RANDOM.nextLong(), player.getId(), weaponPgId,
				proj.getProjectileId(), src, angle, proj.getSize(),
				proj.getMagnitude(), range, damage, false, flags, proj.getAmplitude(),
				proj.getFrequency(), player.getId());
		if (b == null) return;
		mergeProjectileEffects(b, proj, ctx);
	}

	private static WeaponArchetypeModel archetypeFor(GameItem item) {
		if (item == null) return null;
		final byte a = item.getArchetypeId();
		if (a <= 0) return null;
		if (GameDataManager.WEAPON_ARCHETYPES == null) return null;
		return GameDataManager.WEAPON_ARCHETYPES.get(a);
	}

	private static void mergeProjectileEffects(Bullet b, Projectile proj, ShotContext ctx) {
		final List<ProjectileEffect> merged = new ArrayList<>();
		if (proj != null && proj.getEffects() != null) merged.addAll(proj.getEffects());
		if (ctx != null) {
			for (ShotContext.OnHitStatus oh : ctx.getOnHitStatuses()) {
				final ProjectileEffect pe = new ProjectileEffect();
				pe.setEffectId(oh.getEffectId());
				pe.setDuration(oh.getDurationMs());
				merged.add(pe);
			}
		}
		if (!merged.isEmpty()) b.setEffects(merged);
	}

	public static void handleTextServer(RealmManagerServer mgr, Packet packet) {
		final TextPacket textPacket = (TextPacket) packet;
		log.info("[SERVER] handleTextServer ENTRY srcIp={} from={} to={} msg={}",
				textPacket.getSrcIp(), textPacket.getFrom(), textPacket.getTo(), textPacket.getMessage());
		final Long fromPlayerId = mgr.getRemoteAddresses().get(textPacket.getSrcIp());
		if (fromPlayerId == null) {
			log.warn("[SERVER] handleTextServer: no remote-address mapping for srcIp={} — dropping",
					textPacket.getSrcIp());
			return;
		}
		// Skip validateCallingPlayer for chat — silent drops cause "my chat vanished" reports.
		final Player fromPlayer = mgr.searchRealmsForPlayer(fromPlayerId);
		if (fromPlayer == null) {
			log.warn("[SERVER] handleTextServer: no player in any realm for id={} (srcIp={}) — dropping",
					fromPlayerId, textPacket.getSrcIp());
			return;
		}
		try {
			final String chatTo = fromPlayer.getChatRole() != null ? fromPlayer.getChatRole() : "";
			final TextPacket toBroadcast = TextPacket.create(fromPlayer.getName(), chatTo, textPacket.getMessage());
			mgr.enqueueServerPacket(toBroadcast);
			if (fromPlayer.getMetrics() != null) {
				fromPlayer.getMetrics().recordChatMessage();
			}
			log.info("[SERVER] Broadcasted player chat message from {} (role={}): {}",
					fromPlayer.getName(), chatTo, textPacket.getMessage());
		} catch (Exception e) {
			log.error("[SERVER] handleTextServer broadcast failed: {}", e.getMessage(), e);
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
			// Push inventory + container update immediately so client doesn't wait up to 62ms for next tick.
			final Realm realm = mgr.findPlayerRealm(moveItemPacket.getPlayerId());
			if (realm != null) {
				final Player player = realm.getPlayer(moveItemPacket.getPlayerId());
				if (player != null) {
					final UpdatePacket update = realm.getPlayerAsPacket(player.getId());
					if (update != null) {
						mgr.enqueueServerPacket(player, update);
					}
					final LootContainer nearLoot = mgr.getClosestLootContainer(
						realm.getRealmId(), player.getPos(), 32);
					if (nearLoot != null && nearLoot.getContentsChanged()) {
						try {
							final NetLootContainer netContainer = IOService.mapModel(nearLoot, NetLootContainer.class);
							final LoadPacket containerUpdate = new LoadPacket(
								new NetPlayer[0], new NetEnemy[0], new NetBullet[0],
								new NetLootContainer[] { netContainer },
								new NetPortal[0], (byte) 0);
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

	public static void handlePotionStorageMoveServer(RealmManagerServer mgr, Packet packet) {
		ServerPotionStorageHelper.handleMove(mgr, packet);
	}

	public static void handleSplitStackServer(RealmManagerServer mgr, Packet packet) {
		ServerItemHelper.handleSplitStackPacket(mgr, packet);
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

	private static void doLogin(RealmManagerServer mgr, LoginRequestMessage request, CommandPacket command) {
		log.info("[SERVER] Recieved login command {}", request);
		// HTTP auth runs on a worker thread so the tick loop isn't blocked.
		WorkerThread.doAsync(() -> {
			CommandPacket commandResponse = null;
			long assignedId = -1l;
			Player player = null;
			try {
				SessionTokenDto loginToken;
				if (request.getToken() != null && !request.getToken().isEmpty()) {
					loginToken = new SessionTokenDto();
					loginToken.setToken(request.getToken());
					AccountDto resolved = ServerGameLogic.DATA_SERVICE.executeGetWithToken(
						"/admin/account/token/resolve", request.getToken(), AccountDto.class);
					loginToken.setAccountGuid(resolved.getAccountGuid());
				} else {
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
				// Force-disconnect any existing session for this account (handles ghosts from dirty disconnects).
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
						// Let realm state settle after force-disconnect.
						try { Thread.sleep(250); } catch (InterruptedException ignored) {}
						// Safety net: forcibly remove any player still in a realm.
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

				Map<Integer, GameItem> loadedEquipment = new HashMap<>();
				for (final GameItemRefDto item : targetCharacter.getItems()) {
					loadedEquipment.put(item.getSlotIdx(), GameItem.fromGameItemRef(item));
				}
				// One-shot migration for pre-rework 20-slot saves (slot 1 = ability item).
				loadedEquipment = ServerItemHelper.migrateLegacySlotLayout(loadedEquipment);

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
				// Relocate items in wrong equip slots — cleans up legacy-bad state on every login.
				ServerItemHelper.reconcileEquipment(player);
				player.applyStats(targetCharacter.getStats());
				player.setName(accountName);
				player.setHeadless(false);
				try {
					AccountDto authAccount = ServerGameLogic.DATA_SERVICE.executeGet(
						"/admin/account/" + loginToken.getAccountGuid(), null, AccountDto.class);
					// Order matters: highest-tier provision wins (admin > editor, etc.).
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

				// Defer realm join to tick thread so addPlayer + invalidate run atomically with LoadPacket delta logic.
				mgr.enqueuePendingJoin(new PendingRealmJoin(
						targetRealm, player, command.getSrcIp(), userSession, commandResponse));
				log.info("[SERVER] Player {} login queued for tick-thread processing", player);
			} catch (Exception e) {
				ServerGameLogic.log.error("Failed to perform Client Login. Reason: {}", e);
				commandResponse = CommandPacket.createError(assignedId, 503,
						"Failed to perform Client Login. Reason: " + e.getMessage());
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

	// Server-derived srcIp is harder to spoof than declared playerId. Reject mismatches.
	private static boolean validateCallingPlayer(RealmManagerServer mgr, Packet packet, Long declaredPlayerId) {
		final Long actualPlayerId = mgr.getRemoteAddresses().get(packet.getSrcIp());
		final Player actualPlayer = mgr.searchRealmsForPlayer(actualPlayerId);
		if (actualPlayer == null) {
			return false;
		}
		if (actualPlayer.getId() != declaredPlayerId) {
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
