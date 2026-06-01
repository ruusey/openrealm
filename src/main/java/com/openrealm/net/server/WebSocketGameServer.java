package com.openrealm.net.server;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class WebSocketGameServer extends WebSocketServer {

    private final NioServer nioServer;

    // Monotonic, never-reused connection sequence. The previous count-based index
    // collided when a freed low index was handed to a reconnecting client while
    // another live client still held it — which, behind the reverse proxy (all WS
    // clients share one source address), aliased two players onto one clientKey and
    // merged their input routing. A global counter guarantees uniqueness for the
    // server's lifetime.
    private static final AtomicLong CONNECTION_SEQ = new AtomicLong();

    public WebSocketGameServer(int port, NioServer nioServer) {
        super(new InetSocketAddress(port));
        this.nioServer = nioServer;
        this.setReuseAddr(true);
        this.setTcpNoDelay(true);
        log.info("[WS-SERVER] WebSocket game server created on port {}", port);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        final String remoteAddr = conn.getRemoteSocketAddress().getAddress().getHostAddress();
        final String clientKey = "ws:" + remoteAddr + "/" + CONNECTION_SEQ.incrementAndGet();
        conn.setAttachment(clientKey);

        final WebSocketClientSession session = new WebSocketClientSession(conn, clientKey);
        this.nioServer.getClients().put(clientKey, session);
        this.nioServer.getClientConnectTime().put(clientKey, Instant.now().toEpochMilli());

        log.info("[WS-SERVER] WebSocket client connected: {}", clientKey);
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        final String clientKey = conn.getAttachment();
        if (clientKey != null) {
            final String initiator = remote ? "CLIENT" : "SERVER";
            final String reasonStr = (reason == null || reason.isEmpty()) ? "none" : reason;
            log.info("[WS-SERVER] WebSocket closed: {} — initiator={}, code={}, reason={}", clientKey, initiator, code, reasonStr);
            final ClientSession session = this.nioServer.getClients().get(clientKey);
            if (session != null) {
                session.setShutdownProcessing(true);
            }
        }
    }

    @Override
    public void onMessage(WebSocket conn, ByteBuffer message) {
        final String clientKey = conn.getAttachment();
        if (clientKey == null) return;

        final ClientSession session = this.nioServer.getClients().get(clientKey);
        if (session instanceof WebSocketClientSession) {
            ((WebSocketClientSession) session).handleWebSocketMessage(message);
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        // Ignore text messages - game uses binary protocol
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        if (conn != null) {
            final String clientKey = conn.getAttachment();
            log.error("[WS-SERVER] WebSocket error for client {}: {}", clientKey, ex.getMessage());
            if (clientKey != null) {
                final ClientSession session = this.nioServer.getClients().get(clientKey);
                if (session != null) {
                    session.setShutdownProcessing(true);
                }
            }
        } else {
            log.error("[WS-SERVER] WebSocket server error: {}", ex.getMessage());
        }
    }

    @Override
    public void onStart() {
        log.info("[WS-SERVER] WebSocket game server started on port {}", this.getPort());
    }
}
