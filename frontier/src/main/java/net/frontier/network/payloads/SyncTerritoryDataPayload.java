package net.frontier.network.payloads;

import io.netty.buffer.ByteBuf;
import net.frontier.api.JurisdictionType;
import net.frontier.api.RelationType;
import net.frontier.api.Role;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.*;

public record SyncTerritoryDataPayload(
        List<ClientStateData> states,
        List<ClientClaimData> claims,
        List<ClientRelationData> relations,
        List<ClientVassalData> vassals,
        UUID playerStateId,
        Role playerRole
) implements CustomPacketPayload {

    public static final Type<SyncTerritoryDataPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("frontier", "sync_territory_data"));

    public static final StreamCodec<ByteBuf, SyncTerritoryDataPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> payload.write(buf),
            SyncTerritoryDataPayload::read
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void write(ByteBuf buf) {
        // States
        buf.writeInt(states.size());
        for (ClientStateData s : states) {
            buf.writeLong(s.id.getMostSignificantBits());
            buf.writeLong(s.id.getLeastSignificantBits());
            ByteBufCodecs.STRING_UTF8.encode(buf, s.name);
            ByteBufCodecs.STRING_UTF8.encode(buf, s.tag);
            buf.writeInt(s.color);
            ByteBufCodecs.STRING_UTF8.encode(buf, s.leaderName);
            ByteBufCodecs.STRING_UTF8.encode(buf, s.motd);
        }

        // Claims
        buf.writeInt(claims.size());
        for (ClientClaimData c : claims) {
            buf.writeInt(c.chunkX);
            buf.writeInt(c.chunkZ);
            buf.writeBoolean(c.stateId != null);
            if (c.stateId != null) {
                buf.writeLong(c.stateId.getMostSignificantBits());
                buf.writeLong(c.stateId.getLeastSignificantBits());
            }
            buf.writeInt(c.jurisdictionType.ordinal());
        }

        // Relations
        buf.writeInt(relations.size());
        for (ClientRelationData r : relations) {
            buf.writeLong(r.stateA.getMostSignificantBits());
            buf.writeLong(r.stateA.getLeastSignificantBits());
            buf.writeLong(r.stateB.getMostSignificantBits());
            buf.writeLong(r.stateB.getLeastSignificantBits());
            buf.writeInt(r.relation.ordinal());
        }

        // Vassals
        buf.writeInt(vassals.size());
        for (ClientVassalData v : vassals) {
            buf.writeLong(v.suzerainId.getMostSignificantBits());
            buf.writeLong(v.suzerainId.getLeastSignificantBits());
            buf.writeLong(v.vassalId.getMostSignificantBits());
            buf.writeLong(v.vassalId.getLeastSignificantBits());
            ByteBufCodecs.STRING_UTF8.encode(buf, v.title);
            buf.writeInt(v.tributePercent);
            buf.writeLong(v.pledgedAt);
        }

        // Player state & role
        buf.writeBoolean(playerStateId != null);
        if (playerStateId != null) {
            buf.writeLong(playerStateId.getMostSignificantBits());
            buf.writeLong(playerStateId.getLeastSignificantBits());
        }
        buf.writeInt(playerRole != null ? playerRole.ordinal() : -1);
    }

    public static SyncTerritoryDataPayload read(ByteBuf buf) {
        // States
        int statesSize = buf.readInt();
        List<ClientStateData> states = new ArrayList<>(statesSize);
        for (int i = 0; i < statesSize; i++) {
            UUID id = new UUID(buf.readLong(), buf.readLong());
            String name = ByteBufCodecs.STRING_UTF8.decode(buf);
            String tag = ByteBufCodecs.STRING_UTF8.decode(buf);
            int color = buf.readInt();
            String leaderName = ByteBufCodecs.STRING_UTF8.decode(buf);
            String motd = ByteBufCodecs.STRING_UTF8.decode(buf);
            states.add(new ClientStateData(id, name, tag, color, leaderName, motd));
        }

        // Claims
        int claimsSize = buf.readInt();
        List<ClientClaimData> claims = new ArrayList<>(claimsSize);
        for (int i = 0; i < claimsSize; i++) {
            int cx = buf.readInt();
            int cz = buf.readInt();
            UUID stateId = buf.readBoolean() ? new UUID(buf.readLong(), buf.readLong()) : null;
            JurisdictionType jType = JurisdictionType.values()[buf.readInt()];
            claims.add(new ClientClaimData(cx, cz, stateId, jType));
        }

        // Relations
        int relationsSize = buf.readInt();
        List<ClientRelationData> relations = new ArrayList<>(relationsSize);
        for (int i = 0; i < relationsSize; i++) {
            UUID a = new UUID(buf.readLong(), buf.readLong());
            UUID b = new UUID(buf.readLong(), buf.readLong());
            RelationType r = RelationType.values()[buf.readInt()];
            relations.add(new ClientRelationData(a, b, r));
        }

        // Vassals
        int vassalsSize = buf.readInt();
        List<ClientVassalData> vassals = new ArrayList<>(vassalsSize);
        for (int i = 0; i < vassalsSize; i++) {
            UUID suzerainId = new UUID(buf.readLong(), buf.readLong());
            UUID vassalId = new UUID(buf.readLong(), buf.readLong());
            String title = ByteBufCodecs.STRING_UTF8.decode(buf);
            int tribute = buf.readInt();
            long pledged = buf.readLong();
            vassals.add(new ClientVassalData(suzerainId, vassalId, title, tribute, pledged));
        }

        // Player state & role
        UUID playerStateId = buf.readBoolean() ? new UUID(buf.readLong(), buf.readLong()) : null;
        int roleOrd = buf.readInt();
        Role playerRole = roleOrd >= 0 ? Role.values()[roleOrd] : null;

        return new SyncTerritoryDataPayload(states, claims, relations, vassals, playerStateId, playerRole);
    }

    public record ClientStateData(UUID id, String name, String tag, int color, String leaderName, String motd) {}
    public record ClientClaimData(int chunkX, int chunkZ, UUID stateId, JurisdictionType jurisdictionType) {}
    public record ClientRelationData(UUID stateA, UUID stateB, RelationType relation) {}
    public record ClientVassalData(UUID suzerainId, UUID vassalId, String title, int tributePercent, long pledgedAt) {}
}
