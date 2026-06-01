package com.openrealm.net.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.time.Instant;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.openrealm.game.contants.GlobalConstants;
import com.openrealm.util.WorkerThread;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Data
@EqualsAndHashCode(callSuper = false)
public class NioServer implements Runnable {

    public static final String LOCALHOST = "127.0.0.1";

    // Monotonic, never-reused connection sequence. A count-based index reused a
    // freed slot while another live client still held it, aliasing two players
    // onto one clientKey (see WebSocketGameServer for the full write-up).
    private static final AtomicLong CONNECTION_SEQ = new AtomicLong();

    private ServerSocketChannel serverChannel;
    private Selector selector;
    private boolean shutdownSocketAccept = false;
    private boolean shutdownProcessing = false;

    private volatile Map<String, ClientSession> clients = new ConcurrentHashMap<>();
    private volatile Map<String, Long> clientConnectTime = new ConcurrentHashMap<>();

    public NioServer(int port) {
        NioServer.log.info("[SERVER] Creating NIO server at port {}", port);
        try {
            this.serverChannel = ServerSocketChannel.open();
            this.serverChannel.configureBlocking(false);
            this.serverChannel.bind(new InetSocketAddress(port));
            this.selector = Selector.open();
            this.serverChannel.register(this.selector, SelectionKey.OP_ACCEPT);
        } catch (Exception e) {
            NioServer.log.error("[SERVER] Failed to create NIO server. Reason: {}", e.getMessage());
            System.exit(-1);
        }
    }

    @Override
    public void run() {
        // Start the write thread
        final Runnable writeThread = () -> {
            NioServer.log.info("[SERVER] NIO write thread started");
            while (!this.shutdownProcessing) {
                try {
                    boolean didWork = false;
                    for (ClientSession session : this.clients.values()) {
                        if (session.isShutdownProcessing()) continue;
                        if (!session.getPendingSerialize().isEmpty()
                                || !session.getWriteQueue().isEmpty()
                                || session.getPendingWrite() != null) {
                            session.flushWrites(); // drainPendingSerialize() is called inside
                            didWork = true;
                        }
                    }
                    if (!didWork) {
                        Thread.sleep(1);
                    }
                } catch (Exception e) {
                    NioServer.log.error("[SERVER] Write thread error. Reason: {}", e.getMessage());
                }
            }
        };
        WorkerThread.submitAndForkRun(writeThread);

        // Start the timeout check thread
        final Runnable timeoutCheck = () -> {
            NioServer.log.info("[SERVER] Beginning connection timeout check...");
            while (!this.shutdownSocketAccept) {
                try {
                    final Set<String> toRemove = new HashSet<>();
                    for (Map.Entry<String, ClientSession> entry : this.clients.entrySet()) {
                        final Long connectTime = this.clientConnectTime.get(entry.getKey());
                        if (connectTime == null) continue;
                        final long timeSinceConnect = Instant.now().toEpochMilli() - connectTime;
                        if (timeSinceConnect > GlobalConstants.SOCKET_READ_TIMEOUT
                                && !entry.getValue().isHandshakeComplete()) {
                            toRemove.add(entry.getKey());
                        }
                    }

                    for (String remove : toRemove) {
                        ClientSession session = this.clients.remove(remove);
                        session.setShutdownProcessing(true);
                        session.close();
                        this.clientConnectTime.remove(remove);
                        NioServer.log.info("[SERVER] Removed expired connection {}", remove);
                    }
                    Thread.sleep(250);
                } catch (Exception e) {
                    NioServer.log.error("[SERVER] Failed to check expired connections. Reason: {}", e);
                }
            }
        };
        WorkerThread.submitAndForkRun(timeoutCheck);

        // Selector loop (main thread for this Runnable)
        NioServer.log.info("[SERVER] NIO server now accepting inbound connections...");
        while (!this.shutdownSocketAccept) {
            try {
                this.selector.select(50);
                final Iterator<SelectionKey> keys = this.selector.selectedKeys().iterator();
                while (keys.hasNext()) {
                    final SelectionKey key = keys.next();
                    keys.remove();

                    if (!key.isValid()) continue;

                    if (key.isAcceptable()) {
                        this.handleAccept(key);
                    } else if (key.isReadable()) {
                        this.handleRead(key);
                    }
                }
            } catch (Exception e) {
                NioServer.log.error("[SERVER] Selector loop error. Reason: {}", e.getMessage());
            }
        }

        // Cleanup
        try {
            this.selector.close();
            this.serverChannel.close();
        } catch (IOException e) {
            NioServer.log.error("[SERVER] Failed to close server channel. Reason: {}", e.getMessage());
        }
    }

    private void handleAccept(SelectionKey key) throws IOException {
        final SocketChannel clientChannel = this.serverChannel.accept();
        if (clientChannel == null) return;

        clientChannel.configureBlocking(false);
        clientChannel.setOption(StandardSocketOptions.TCP_NODELAY, true);

        final String remoteAddr = clientChannel.socket().getInetAddress().getHostAddress();
        final String clientKey = remoteAddr + "/" + CONNECTION_SEQ.incrementAndGet();
        final ClientSession session = new ClientSession(clientChannel, clientKey);

        this.clients.put(clientKey, session);
        clientChannel.register(this.selector, SelectionKey.OP_READ, session);
        this.clientConnectTime.put(clientKey, Instant.now().toEpochMilli());

        NioServer.log.info("[SERVER] Server accepted new connection from Remote Address {}, clientKey = {}",
                remoteAddr, clientKey);
    }

    private void handleRead(SelectionKey key) {
        final ClientSession session = (ClientSession) key.attachment();
        if (session == null || session.isShutdownProcessing()) {
            key.cancel();
            return;
        }
        try {
            session.readFromChannel();
            if (session.isShutdownProcessing()) {
                key.cancel();
            }
        } catch (IOException e) {
            NioServer.log.error("[SERVER] Read error for client {}. Reason: {}", session.getClientKey(), e.getMessage());
            session.setShutdownProcessing(true);
            key.cancel();
        }
    }

}
