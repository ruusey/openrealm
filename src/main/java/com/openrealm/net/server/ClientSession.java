package com.openrealm.net.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import com.openrealm.game.contants.PacketType;
import com.openrealm.net.NetConstants;
import com.openrealm.net.Packet;
import com.openrealm.net.core.IOService;
import com.openrealm.net.core.PacketCompression;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Data
public class ClientSession {
    private static final int BUFFER_CAPACITY = 65536 * 10;
    private static final int READ_BUFFER_SIZE = 8192;

    private SocketChannel channel;
    private String clientKey;
    private boolean shutdownProcessing = false;
    private boolean handshakeComplete = false;
    private byte[] remoteBuffer = new byte[ClientSession.BUFFER_CAPACITY];
    private int remoteBufferIndex = 0;
    private ByteBuffer readBuffer = ByteBuffer.allocateDirect(READ_BUFFER_SIZE);
    private final Queue<Packet> packetQueue = new ConcurrentLinkedQueue<>();
    private final Queue<byte[]> writeQueue = new ConcurrentLinkedQueue<>();
    private final Queue<Packet> pendingSerialize = new ConcurrentLinkedQueue<>();

    // Shared bandwidth counters — set by RealmManagerServer, updated by
    // write thread (out) and NIO read thread (in). All counters track the
    // POST-COMPRESSION byte counts — i.e. true on-the-wire size — not the
    // decompressed payload, so the rate logged matches actual network use.
    private AtomicLong sharedBytesWritten;
    private AtomicLong sharedBytesRead;
    private ConcurrentHashMap<String, AtomicLong> sharedBytesPerType;
    private ConcurrentHashMap<String, AtomicLong> sharedBytesReadPerType;
    private ByteBuffer pendingWrite = null;

    public ClientSession(SocketChannel channel, String clientKey) {
        this.channel = channel;
        this.clientKey = clientKey;
    }

    public void readFromChannel() throws IOException {
        this.readBuffer.clear();
        int bytesRead = this.channel.read(this.readBuffer);
        if (bytesRead == -1) {
            this.shutdownProcessing = true;
            return;
        }
        if (bytesRead > 0) {
            // Track true on-the-wire inbound bytes — TCP delivers the
            // compressed framing as-is, so this counter measures real
            // network usage. Per-packet-type accounting happens inside
            // parsePackets() once the packet type is known.
            if (this.sharedBytesRead != null) {
                this.sharedBytesRead.addAndGet(bytesRead);
            }
            this.readBuffer.flip();
            int remaining = this.readBuffer.remaining();
            if (this.remoteBufferIndex + remaining > this.remoteBuffer.length) {
                log.warn("[NIO] Buffer overflow for client {}, discarding data", this.clientKey);
                this.remoteBufferIndex = 0;
                return;
            }
            this.readBuffer.get(this.remoteBuffer, this.remoteBufferIndex, remaining);
            this.remoteBufferIndex += remaining;
            this.parsePackets();
        }
    }

    /**
     * Inject raw bytes into the receive buffer and trigger packet parsing.
     * Used by WebSocket bridge to feed data into the standard packet pipeline.
     */
    public void injectData(byte[] data, int offset, int length) {
        if (this.remoteBufferIndex + length > this.remoteBuffer.length) {
            log.warn("[NIO] Buffer overflow for client {} during data injection, discarding", this.clientKey);
            this.remoteBufferIndex = 0;
            return;
        }
        System.arraycopy(data, offset, this.remoteBuffer, this.remoteBufferIndex, length);
        this.remoteBufferIndex += length;
        this.parsePackets();
    }

    protected void parsePackets() {
        while (this.remoteBufferIndex >= 5) {
            int packetLength = ((this.remoteBuffer[1] & 0xFF) << 24)
                             | ((this.remoteBuffer[2] & 0xFF) << 16)
                             | ((this.remoteBuffer[3] & 0xFF) << 8)
                             |  (this.remoteBuffer[4] & 0xFF);
            if (this.remoteBufferIndex < packetLength) {
                break;
            }
            byte packetId = this.remoteBuffer[0];
            int dataLength = packetLength - NetConstants.PACKET_HEADER_SIZE;
            byte[] packetBytes = new byte[dataLength];
            System.arraycopy(this.remoteBuffer, NetConstants.PACKET_HEADER_SIZE, packetBytes, 0, dataLength);
            if (this.remoteBufferIndex > packetLength) {
                System.arraycopy(this.remoteBuffer, packetLength, this.remoteBuffer, 0,
                        this.remoteBufferIndex - packetLength);
            }
            this.remoteBufferIndex -= packetLength;
            try {
                // The on-wire frame length is `packetLength` regardless of
                // compression — both compressed and uncompressed framings
                // include the 5-byte header. Capture before mutating the
                // packet ID for per-type accounting.
                final int wireLen = packetLength;
                // Decompress if compression flag is set
                if (PacketCompression.isCompressed(packetId)) {
                    packetId = PacketCompression.getRealPacketId(packetId);
                    packetBytes = PacketCompression.decompressPayload(packetBytes);
                }
                final Class<?> packetClass = PacketType.valueOf(packetId);
                final Packet nPacket = IOService.readStream(packetClass, packetBytes);
                nPacket.setId(packetId);
                nPacket.setSrcIp(this.clientKey);
                if (this.sharedBytesReadPerType != null && packetClass != null) {
                    this.sharedBytesReadPerType
                            .computeIfAbsent(packetClass.getSimpleName(), k -> new AtomicLong(0))
                            .addAndGet(wireLen);
                }
                this.packetQueue.add(nPacket);
            } catch (Exception e) {
                log.error("[NIO] Failed to parse packet from client {}. Reason: {}", this.clientKey, e.getMessage());
            }
        }
    }

    public void enqueueWrite(byte[] frame) {
        // Track the COMPRESSED frame length — that's the true wire cost.
        // Was tracking the pre-compression frame size, which over-reported
        // the actual on-wire bandwidth by ~2× (deflate compresses our
        // packet payloads by 40-60%).
        final byte[] wireFrame = PacketCompression.compressFrame(frame);
        this.writeQueue.add(wireFrame);
        if (this.sharedBytesWritten != null) {
            this.sharedBytesWritten.addAndGet(wireFrame.length);
        }
    }

    /**
     * Enqueue a packet for deferred serialization on the write thread.
     * Avoids blocking the game tick thread with serialization + compression.
     */
    public void enqueuePacket(Packet packet) {
        this.pendingSerialize.add(packet);
    }

    /**
     * Serialize all pending packets into the write queue. Called by the write thread
     * before flushing, NOT on the game tick thread.
     */
    public void drainPendingSerialize() {
        Packet packet;
        while ((packet = this.pendingSerialize.poll()) != null) {
            try {
                final byte[] frame = packet.serializeToBytes();
                // Track the post-compression length — that's what actually
                // hits the wire. compressFrame returns the original frame
                // unchanged when below threshold or when compression doesn't
                // save space, so the count is accurate either way.
                final byte[] wireFrame = PacketCompression.compressFrame(frame);
                this.writeQueue.add(wireFrame);
                final int len = wireFrame.length;
                if (this.sharedBytesWritten != null) {
                    this.sharedBytesWritten.addAndGet(len);
                }
                if (this.sharedBytesPerType != null) {
                    this.sharedBytesPerType
                            .computeIfAbsent(packet.getClass().getSimpleName(), k -> new AtomicLong(0))
                            .addAndGet(len);
                }
            } catch (Exception e) {
                log.error("[SESSION] Failed to serialize packet {} for client {}. Reason: {}",
                    packet.getClass().getSimpleName(), this.clientKey, e.getMessage(), e);
            }
        }
    }

    public boolean flushWrites() {
        try {
            // Serialize any pending packets (deferred from tick thread)
            this.drainPendingSerialize();
            // First try to finish any pending partial write
            if (this.pendingWrite != null) {
                this.channel.write(this.pendingWrite);
                if (this.pendingWrite.hasRemaining()) {
                    return false;
                }
                this.pendingWrite = null;
            }

            // Drain the write queue
            byte[] frame;
            while ((frame = this.writeQueue.poll()) != null) {
                ByteBuffer buf = ByteBuffer.wrap(frame);
                this.channel.write(buf);
                if (buf.hasRemaining()) {
                    this.pendingWrite = buf;
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            log.error("[NIO] Write failed for client {}. Reason: {}", this.clientKey, e.getMessage());
            this.shutdownProcessing = true;
            return true;
        }
    }

    public boolean isConnected() {
        return this.channel != null && this.channel.isOpen() && this.channel.isConnected();
    }

    public void close() {
        try {
            if (this.channel != null && this.channel.isOpen()) {
                this.channel.close();
            }
        } catch (IOException e) {
            log.error("[NIO] Failed to close channel for client {}. Reason: {}", this.clientKey, e.getMessage());
        }
        this.remoteBuffer = null;
        this.packetQueue.clear();
        this.pendingSerialize.clear();
        this.writeQueue.clear();
        this.pendingWrite = null;
    }
}
