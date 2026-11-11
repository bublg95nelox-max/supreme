package net.frontier.network.payloads;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record OpenFrontierScreenPayload(int defaultTab) implements CustomPacketPayload {
    public static final Type<OpenFrontierScreenPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("frontier", "open_screen"));

    public static final StreamCodec<ByteBuf, OpenFrontierScreenPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> buf.writeInt(payload.defaultTab),
            buf -> new OpenFrontierScreenPayload(buf.readInt())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
