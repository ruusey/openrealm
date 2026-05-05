package com.openrealm.net.entity;

import java.io.DataInputStream;
import java.io.DataOutputStream;

import com.openrealm.net.Streamable;
import com.openrealm.net.core.SerializableFieldType;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Streamable
public class NetAttributeModifier extends SerializableFieldType<NetAttributeModifier> {
    private byte statId;
    private byte deltaValue;

    @Override
    public int write(NetAttributeModifier value, DataOutputStream stream) throws Exception {
        final NetAttributeModifier v = value == null ? new NetAttributeModifier() : value;
        stream.writeByte(v.statId);
        stream.writeByte(v.deltaValue);
        return 2;
    }

    @Override
    public NetAttributeModifier read(DataInputStream stream) throws Exception {
        final NetAttributeModifier v = new NetAttributeModifier();
        v.statId = stream.readByte();
        v.deltaValue = stream.readByte();
        return v;
    }
}
