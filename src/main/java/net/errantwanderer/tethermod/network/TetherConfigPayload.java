package net.errantwanderer.tethermod.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.errantwanderer.tethermod.TetherMod;

public record TetherConfigPayload(String key, int type, Object value) implements CustomPayload {
    public static final Identifier ID = Identifier.of(TetherMod.MOD_ID, "tether_config");
    public static final CustomPayload.Id<TetherConfigPayload> PACKET_ID = new CustomPayload.Id<>(ID);

    @Override
    public Id<? extends CustomPayload> getId() {
        return PACKET_ID;
    }

    public static TetherConfigPayload read(PacketByteBuf buf) {
        String key = buf.readString();
        int type = buf.readByte();

        return switch (type) {
            case 0 -> new TetherConfigPayload(key, type, buf.readBoolean());
            case 1 -> new TetherConfigPayload(key, type, buf.readFloat());
            case 2 -> new TetherConfigPayload(key, type, new int[] {
                    buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt()
            });
            default -> throw new IllegalArgumentException("Unknown type: " + type);
        };
    }

    public void write(PacketByteBuf buf) {
        buf.writeString(key);
        buf.writeByte(type);
        switch (type) {
            case 0 -> buf.writeBoolean((Boolean) value);
            case 1 -> buf.writeFloat((Float) value);
            case 2 -> {
                int[] arr = (int[]) value;
                for (int i : arr) buf.writeInt(i);
            }
        }
    }
}