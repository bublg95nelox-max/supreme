package net.frontier.network.payloads;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record SyncAdminPetitionsPayload(List<AdminPetitionData> petitions) implements CustomPacketPayload {
    public static final Type<SyncAdminPetitionsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("frontier", "sync_admin_petitions"));

    public static final StreamCodec<ByteBuf, SyncAdminPetitionsPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> payload.write(buf),
            SyncAdminPetitionsPayload::read
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void write(ByteBuf buf) {
        buf.writeInt(petitions.size());
        for (AdminPetitionData p : petitions) {
            buf.writeInt(p.id);
            buf.writeLong(p.requesterUuid.getMostSignificantBits());
            buf.writeLong(p.requesterUuid.getLeastSignificantBits());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.requesterName);
            ByteBufCodecs.STRING_UTF8.encode(buf, p.stateName);
            ByteBufCodecs.STRING_UTF8.encode(buf, p.stateTag);
            buf.writeInt(p.color);
            ByteBufCodecs.STRING_UTF8.encode(buf, p.dimension);
            buf.writeInt(p.capitalChunkX);
            buf.writeInt(p.capitalChunkZ);
            ByteBufCodecs.STRING_UTF8.encode(buf, p.status);
            buf.writeLong(p.submittedAt);
        }
    }

    public static SyncAdminPetitionsPayload read(ByteBuf buf) {
        int count = buf.readInt();
        List<AdminPetitionData> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int id = buf.readInt();
            UUID reqUuid = new UUID(buf.readLong(), buf.readLong());
            String reqName = ByteBufCodecs.STRING_UTF8.decode(buf);
            String stateName = ByteBufCodecs.STRING_UTF8.decode(buf);
            String stateTag = ByteBufCodecs.STRING_UTF8.decode(buf);
            int color = buf.readInt();
            String dim = ByteBufCodecs.STRING_UTF8.decode(buf);
            int cx = buf.readInt();
            int cz = buf.readInt();
            String status = ByteBufCodecs.STRING_UTF8.decode(buf);
            long submittedAt = buf.readLong();
            list.add(new AdminPetitionData(id, reqUuid, reqName, stateName, stateTag, color, dim, cx, cz, status, submittedAt));
        }
        return new SyncAdminPetitionsPayload(list);
    }

    public record AdminPetitionData(
            int id,
            UUID requesterUuid,
            String requesterName,
            String stateName,
            String stateTag,
            int color,
            String dimension,
            int capitalChunkX,
            int capitalChunkZ,
            String status,
            long submittedAt
    ) {}
}
