package net.frontier.network.payloads;

import net.frontier.Frontier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record SyncAuthLockPayload(boolean locked) implements CustomPacketPayload {
    public static final Type<SyncAuthLockPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Frontier.MODID, "sync_auth_lock"));

    public static final StreamCodec<FriendlyByteBuf, SyncAuthLockPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL,
            SyncAuthLockPayload::locked,
            SyncAuthLockPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
