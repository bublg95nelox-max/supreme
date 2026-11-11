package net.frontier.network.payloads;

import io.netty.buffer.ByteBuf;
import net.frontier.api.CaseStatus;
import net.frontier.api.LawStatus;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record SyncCasesAndLawsPayload(
        UUID stateId,
        List<ClientLawData> laws,
        List<ClientCaseData> cases
) implements CustomPacketPayload {

    public static final Type<SyncCasesAndLawsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("frontier", "sync_cases_laws"));

    public static final StreamCodec<ByteBuf, SyncCasesAndLawsPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> payload.write(buf),
            SyncCasesAndLawsPayload::read
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void write(ByteBuf buf) {
        buf.writeLong(stateId.getMostSignificantBits());
        buf.writeLong(stateId.getLeastSignificantBits());

        // Laws
        buf.writeInt(laws.size());
        for (ClientLawData l : laws) {
            buf.writeInt(l.id);
            ByteBufCodecs.STRING_UTF8.encode(buf, l.title);
            ByteBufCodecs.STRING_UTF8.encode(buf, l.content);
            ByteBufCodecs.STRING_UTF8.encode(buf, l.penalty);
            ByteBufCodecs.STRING_UTF8.encode(buf, l.authorName);
            buf.writeInt(l.status.ordinal());
        }

        // Cases
        buf.writeInt(cases.size());
        for (ClientCaseData c : cases) {
            buf.writeInt(c.id);
            ByteBufCodecs.STRING_UTF8.encode(buf, c.title);
            ByteBufCodecs.STRING_UTF8.encode(buf, c.accusedName);
            ByteBufCodecs.STRING_UTF8.encode(buf, c.plaintiffName);
            ByteBufCodecs.STRING_UTF8.encode(buf, c.judgeName);
            buf.writeInt(c.status.ordinal());
            ByteBufCodecs.STRING_UTF8.encode(buf, c.verdict);
        }
    }

    public static SyncCasesAndLawsPayload read(ByteBuf buf) {
        UUID stateId = new UUID(buf.readLong(), buf.readLong());

        int lawsCount = buf.readInt();
        List<ClientLawData> laws = new ArrayList<>(lawsCount);
        for (int i = 0; i < lawsCount; i++) {
            int id = buf.readInt();
            String title = ByteBufCodecs.STRING_UTF8.decode(buf);
            String content = ByteBufCodecs.STRING_UTF8.decode(buf);
            String penalty = ByteBufCodecs.STRING_UTF8.decode(buf);
            String authorName = ByteBufCodecs.STRING_UTF8.decode(buf);
            LawStatus status = LawStatus.values()[buf.readInt()];
            laws.add(new ClientLawData(id, title, content, penalty, authorName, status));
        }

        int casesCount = buf.readInt();
        List<ClientCaseData> cases = new ArrayList<>(casesCount);
        for (int i = 0; i < casesCount; i++) {
            int id = buf.readInt();
            String title = ByteBufCodecs.STRING_UTF8.decode(buf);
            String accusedName = ByteBufCodecs.STRING_UTF8.decode(buf);
            String plaintiffName = ByteBufCodecs.STRING_UTF8.decode(buf);
            String judgeName = ByteBufCodecs.STRING_UTF8.decode(buf);
            CaseStatus status = CaseStatus.values()[buf.readInt()];
            String verdict = ByteBufCodecs.STRING_UTF8.decode(buf);
            cases.add(new ClientCaseData(id, title, accusedName, plaintiffName, judgeName, status, verdict));
        }

        return new SyncCasesAndLawsPayload(stateId, laws, cases);
    }

    public record ClientLawData(int id, String title, String content, String penalty, String authorName, LawStatus status) {}
    public record ClientCaseData(int id, String title, String accusedName, String plaintiffName, String judgeName, CaseStatus status, String verdict) {}
}
