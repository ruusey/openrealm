package com.openrealm.net;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

import com.openrealm.game.contants.PacketType;
import com.openrealm.net.core.IOService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class Packet {
    private byte id;
    private byte[] data;
    private String srcIp;

    public Packet() {
        this.id = -1;
        this.data = null;
        this.srcIp = null;
    }

    public Packet(byte id, byte[] data) {
        this.id = id;
        this.data = data;
        this.srcIp = null;
    }

    public Packet(byte id, byte[] data, String srcIp) {
        this.id = id;
        this.data = data;
        this.srcIp = srcIp;
    }
    
    public int serializeWrite(DataOutputStream stream) throws Exception {
		return IOService.writePacket(this, stream).length;
    }

    public byte[] serializeToBytes() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        IOService.writePacket(this, dos);
        return baos.toByteArray();
    }

    public void readData(byte[] data) throws Exception {
    	final Packet read = IOService.readPacket(getClass(), data);
    	this.assignData(this, read);
    }

    public byte getId() {
        return this.id;
    }

    public byte[] getData() {
        return this.data;
    }

    public String getSrcIp() {
        return this.srcIp;
    }

    public void setSrcIp(String srcIp) {
        this.srcIp = srcIp;
    }

    public void setId(byte id) {
		this.id = id;
	}

	public void addHeader(DataOutputStream stream) throws Exception {
        stream.writeByte(this.id);
        stream.writeInt(this.data.length + NetConstants.PACKET_HEADER_SIZE);
    }

    public void addHeader(DataOutputStream stream, byte packetId, int contentLength) throws Exception {
        stream.writeByte(packetId);
        stream.writeInt(contentLength + NetConstants.PACKET_HEADER_SIZE);
    }

    public Class<? extends Packet> getPacketType() {
        return PacketType.valueOf(id);
    }
    
    public void assignData(Object target, Object src) {
    	if(target==null || src==null) return;
    	target = src;
    }

    public static Packet newInstance(final byte packetId, final byte[] data) {
        final Class<? extends Packet> typeInfo = PacketType.valueOf(packetId);
        Packet packetObj = null;
        try {
            packetObj = typeInfo.getDeclaredConstructor(byte.class, byte[].class).newInstance(packetId, data);
        } catch (Exception e) {
            log.error("Failed to construct instance of {}. Reason: {}", typeInfo, e.getMessage());
        }
        return packetObj;
    }
}
