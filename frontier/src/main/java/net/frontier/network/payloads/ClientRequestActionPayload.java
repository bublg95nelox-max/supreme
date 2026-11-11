package net.frontier.network.payloads;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

public record ClientRequestActionPayload(
        int actionType,
        int intParam1,
        int intParam2,
        String strParam1,
        String strParam2,
        String strParam3,
        UUID uuidParam1
) implements CustomPacketPayload {

    public static final Type<ClientRequestActionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("frontier", "client_request_action"));

    public static final StreamCodec<ByteBuf, ClientRequestActionPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> payload.write(buf),
            ClientRequestActionPayload::read
    );

    // Action constants
    public static final int ACTION_CREATE_PETITION = 1;
    public static final int ACTION_APPROVE_PETITION = 2;
    public static final int ACTION_CREATE_LAW = 3;
    public static final int ACTION_ENACT_LAW = 4;
    public static final int ACTION_REPEAL_LAW = 5;
    public static final int ACTION_SET_RELATION = 6;
    public static final int ACTION_CREATE_CASE = 7;
    public static final int ACTION_SET_VERDICT = 8;
    public static final int ACTION_CLAIM_CHUNK = 9;
    public static final int ACTION_UNCLAIM_CHUNK = 10;
    public static final int ACTION_REQUEST_CASES_LAWS = 11;
    public static final int ACTION_PLEDGE_VASSALAGE = 12;
    public static final int ACTION_REBEL_VASSALAGE = 13;
    public static final int ACTION_RELEASE_VASSAL = 14;
    public static final int ACTION_REJECT_PETITION = 15;
    public static final int ACTION_ADMIN_ASSIGN_CHUNK = 16;

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void write(ByteBuf buf) {
        buf.writeInt(actionType);
        buf.writeInt(intParam1);
        buf.writeInt(intParam2);
        ByteBufCodecs.STRING_UTF8.encode(buf, strParam1 != null ? strParam1 : "");
        ByteBufCodecs.STRING_UTF8.encode(buf, strParam2 != null ? strParam2 : "");
        ByteBufCodecs.STRING_UTF8.encode(buf, strParam3 != null ? strParam3 : "");
        buf.writeBoolean(uuidParam1 != null);
        if (uuidParam1 != null) {
            buf.writeLong(uuidParam1.getMostSignificantBits());
            buf.writeLong(uuidParam1.getLeastSignificantBits());
        }
    }

    public static ClientRequestActionPayload read(ByteBuf buf) {
        int actionType = buf.readInt();
        int intParam1 = buf.readInt();
        int intParam2 = buf.readInt();
        String strParam1 = ByteBufCodecs.STRING_UTF8.decode(buf);
        String strParam2 = ByteBufCodecs.STRING_UTF8.decode(buf);
        String strParam3 = ByteBufCodecs.STRING_UTF8.decode(buf);
        UUID uuidParam1 = buf.readBoolean() ? new UUID(buf.readLong(), buf.readLong()) : null;
        return new ClientRequestActionPayload(actionType, intParam1, intParam2, strParam1, strParam2, strParam3, uuidParam1);
    }
}
