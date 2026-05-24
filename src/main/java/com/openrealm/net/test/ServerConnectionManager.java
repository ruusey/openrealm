package com.openrealm.net.test;

import java.io.DataOutputStream;
import java.io.OutputStream;
import java.lang.invoke.MethodHandle;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;

import com.openrealm.game.contants.GlobalConstants;
import com.openrealm.game.contants.PacketType;
import com.openrealm.game.entity.Player;
import com.openrealm.net.Packet;
import com.openrealm.util.TimedWorkerThread;
import com.openrealm.util.WorkerThread;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Data
@EqualsAndHashCode(callSuper = false)
public class ServerConnectionManager implements Runnable {
	public static final String LOCALHOST = "127.0.0.1";

	private ServerSocket serverSocket;
	private boolean shutdownSocketAccept = false;
	private boolean shutdownProcessing = false;

	private volatile Map<String, ClientConnectionThread> clients = new ConcurrentHashMap<>();
	private volatile Map<String, Long> clientConnectTime = new ConcurrentHashMap<>();
	private TestServerMapper mapper;
	private long lastWriteSampleTime = Instant.now().toEpochMilli();
	private Map<String, Long> remoteAddresses = new HashMap<>();
	private long bytesWritten = 0;
	private volatile Queue<Packet> outboundPacketQueue = new ConcurrentLinkedQueue<>();
	private volatile Map<String, ConcurrentLinkedQueue<Packet>> addressOutboundPacketQueue = new HashMap<String, ConcurrentLinkedQueue<Packet>>();
	
	public ServerConnectionManager(int port) {
		ServerConnectionManager.log.info("Creating local server at port {}", port);
		try {
			this.serverSocket = new ServerSocket(port);
			this.mapper = new TestServerMapper();
		} catch (Exception e) {
			ServerConnectionManager.log.error("Failed to create server socket. Reason: {}", e.getMessage());
			System.exit(-1);
		}
	}	
	
	public void sendPackets() {
		final List<Packet> packetsToBroadcast = new ArrayList<>();
		// TODO: Possibly rework this queue as we dont usually send stuff globally
		while (!this.outboundPacketQueue.isEmpty()) {
			packetsToBroadcast.add(this.outboundPacketQueue.remove());
		}
		for (final Entry<String, ClientConnectionThread> client : this.getClients().entrySet()) {
			try {
				// Dequeue and send any player specific packets
				final List<Packet> playerPackets = new ArrayList<>();
				final ConcurrentLinkedQueue<Packet> playerPacketsToSend = this.addressOutboundPacketQueue
						.get(client.getKey());
				if(playerPacketsToSend==null) return;
				while ((playerPacketsToSend != null) && !playerPacketsToSend.isEmpty()) {
					playerPackets.add(playerPacketsToSend.remove());
				}

				final OutputStream toClientStream = client.getValue().getClientSocket().getOutputStream();
				final DataOutputStream dosToClient = new DataOutputStream(toClientStream);

				// Globally sent packets
				for (final Packet packet : packetsToBroadcast) {
					this.bytesWritten += packet.serializeWrite(dosToClient);
				}

				for (final Packet packet : playerPackets) {
					this.bytesWritten += packet.serializeWrite(dosToClient);
				}
			} catch (Exception e) {
				ServerConnectionManager.log.error("[SERVER] Failed to get OutputStream to Client. Reason: {}", e);
			}
		}
		
		if (Instant.now().toEpochMilli() - this.lastWriteSampleTime > 1000) {
			this.lastWriteSampleTime = Instant.now().toEpochMilli();
			ServerConnectionManager.log.info("[SERVER] current write rate = {} kbit/s",
					(float) (this.bytesWritten / 1024.0f) * 8.0f);
			this.bytesWritten = 0;

		}
	}
	
	public  void enqueueServerPacket(final String addr, final Packet packet) {
		if (addr == null || packet == null)
			return;
		if (this.addressOutboundPacketQueue.get(addr) == null) {
			final ConcurrentLinkedQueue<Packet> packets = new ConcurrentLinkedQueue<>();
			packets.add(packet);
			this.addressOutboundPacketQueue.put(addr, packets);
		} else {
			this.addressOutboundPacketQueue.get(addr).add(packet);
		}
	}
	
	public void processServerPackets() {
		for (final Entry<String, ClientConnectionThread> thread : clients.entrySet()) {
			if (!thread.getValue().isShutdownProcessing()) {
				// Read all packets from the ProcessingThread (client's) queue
				while (!thread.getValue().getPacketQueue().isEmpty()) {
					final Packet packet = thread.getValue().getPacketQueue().remove();
					try {
						final Packet created = packet;
						created.setSrcIp(packet.getSrcIp());
						// Invoke packet callback
						final List<MethodHandle> packetHandles = this.mapper.getUserPacketCallbacksServer().get(packet.getId());
						long start = System.nanoTime();
						if (packetHandles != null) {
							for (MethodHandle handler : packetHandles) {
								try {
									final Packet result = (Packet) handler.invokeExact(this, created);
									//log.info("\nRequest = {}\nResponse = {}",  packet, result);
									this.enqueueServerPacket(created.getSrcIp(), result);
								} catch (Throwable e) {
									log.error("Failed to invoke packet callback. Reason: {}", e);
								}
							}
//							log.info("Invoked {} packet callbacks for PacketType {} using reflection in {} nanos",
//									packetHandles.size(), PacketType.valueOf(created.getId()).getY(),
//									(System.nanoTime() - start));
						}
						start = System.nanoTime();
						if (this.mapper.getPacketCallbacksServer().get(created.getId()) == null) {
							final List<MethodHandle> callBacHandles = this.mapper.getUserPacketCallbacksServer().get(created.getId());
							if (callBacHandles != null) {
								callBacHandles.forEach(callBack -> {
									try {
										final Packet result = (Packet) callBack.invokeExact(this, created);
										//log.info("\nRequest = {}\nResponse = {}",  packet, result);
										this.enqueueServerPacket(created.getSrcIp(), result);
									} catch (Throwable e) {
										log.error(
												"Failed to invoke user server packet callback for packet id {}. Callback: {}. Reason: {}",
												created.getId(), callBack, e.getMessage());
									}
								});
							}
						} else {
							final List<Function<Packet, Packet>> callbacks = this.mapper.getPacketCallbacksServer().get(created.getId());
							for(Function<Packet, Packet> fun : callbacks) {
								final Packet result = (Packet) fun.apply(created);
								log.info("\nRequest = {}\nResponse = {}",  packet, result);
								this.enqueueServerPacket(created.getSrcIp(), result);
							}
						}
						log.debug("Invoked callback for PacketType {} using map in {} nanos",
								PacketType.valueOf(created.getId()), (System.nanoTime() - start));
					} catch (Exception e) {
						ServerConnectionManager.log.error("Failed to process server packets {}", e);
						thread.getValue().setShutdownProcessing(true);
					}
				}
			} else {
				// Player Disconnect routine
				final Long dcPlayerId = this.getRemoteAddresses().get(thread.getKey());
				if (dcPlayerId == null) {
					thread.getValue().setShutdownProcessing(true);
					return;
				}
				this.getClients().remove(thread.getKey());
			}
		}
	}

	
	public Map.Entry<String, ClientConnectionThread> getPlayerProcessingThreadEntry(Player player) {
		Map.Entry<String, ClientConnectionThread> result = null;
		for (final Entry<String, ClientConnectionThread> client : this.getClients().entrySet()) {
			if (this.remoteAddresses.get(client.getKey()) == player.getId()) {
				result = client;
			}
		}
		return result;
	}

	public ClientConnectionThread getPlayerProcessingThread(Player player) {
		return getPlayerProcessingThreadEntry(player).getValue();
	}

	public String getPlayerRemoteAddress(Player player) {
		return getPlayerProcessingThreadEntry(player).getKey();
	}

	public void disconnectPlayer(Player player) {
		final ClientConnectionThread playerThread = this.getPlayerProcessingThread(player);
		final String playerRemoteAddr = this.getPlayerRemoteAddress(player);
		try {
			log.info("Disconnecting Player {}", player.getName());
			playerThread.setShutdownProcessing(true);
			this.getClients().remove(playerRemoteAddr);
		} catch (Exception e) {
			log.error("Failed to disconnect player. Reason:  {}", e);
		}
	}

	@Override
	public void run() {
		final Runnable socketAccept = () -> {
			ServerConnectionManager.log.info("Server now accepting inbound connections...");
			while (!this.shutdownSocketAccept) {
				try {
					final Socket socket = this.serverSocket.accept();
					socket.setTcpNoDelay(true);
					socket.setSoTimeout((int) GlobalConstants.SOCKET_READ_TIMEOUT);
					final String remoteAddr = socket.getInetAddress().getHostAddress();
					final ClientConnectionThread processingThread = new ClientConnectionThread(socket, this);
					this.clients.put(remoteAddr, processingThread);
					processingThread.start();
					ServerConnectionManager.log.info("Server accepted new connection from Remote Address {}",
							remoteAddr);
					this.clientConnectTime.put(remoteAddr, Instant.now().toEpochMilli());
					Thread.sleep(50);
				} catch (Exception e) {
					ServerConnectionManager.log.error("Failed to accept incoming socket connection, exiting...", e);
				}
			}
		};
		final Runnable sendResponses = () ->{
			try {
				this.sendPackets();
			}catch(Exception e) {
				log.error("Failed to send Server response objects. Reason: {}", e.getMessage());
			}
		};

		// Expire connections if the handshake is not complete after 2.5 seconds
//		final Runnable timeoutCheck = () -> {
//			ServerConnectionManager.log.info("Beginning connection timeout check...");
//			while (!this.shutdownSocketAccept) {
//				try {
//					final Set<String> toRemove = new HashSet<>();
//					for (Entry<String, ClientConnectionThread> entry : this.clients.entrySet()) {
//						final long timeSinceConnect = Instant.now().toEpochMilli()
//								- this.clientConnectTime.get(entry.getKey());
//						if (timeSinceConnect > GlobalConstants.SOCKET_READ_TIMEOUT
//								&& !entry.getValue().isHandshakeComplete()) {
//							toRemove.add(entry.getKey());
//						}
//					}
//
//					for (String remove : toRemove) {
//						ClientConnectionThread thread = this.clients.remove(remove);
//						thread.setShutdownProcessing(true);
//						ServerConnectionManager.log.info("Removed expired connection {}", thread.getClientSocket());
//					}
//					Thread.sleep(250);
//				} catch (Exception e) {
//					ServerConnectionManager.log.error("Failed to check expired connections. Reason: {}", e);
//				}
//			}
//		};
		
		Runnable invokeCallbacks = () -> {
			try {
				this.processServerPackets();
			}catch(Exception e) {
				log.error("Failed to process server packets. Reason: {}", e.getMessage());
			}
		};
		
		TimedWorkerThread callbackThread = new TimedWorkerThread(invokeCallbacks, 32);
		TimedWorkerThread sendResponseThread = new TimedWorkerThread(sendResponses, 32);

		WorkerThread.submitAndForkRun(callbackThread, sendResponseThread);
		WorkerThread.submitAndForkRun(socketAccept);
	}

}
