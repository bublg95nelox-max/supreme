package net.frontier.client;

import net.frontier.api.JurisdictionType;
import net.frontier.api.RelationType;
import net.frontier.api.Role;
import net.frontier.network.payloads.SyncCasesAndLawsPayload;
import net.frontier.network.payloads.SyncTerritoryDataPayload;

import java.util.*;

public class FrontierClientCache {
    private static FrontierClientCache instance = new FrontierClientCache();

    private final Map<UUID, SyncTerritoryDataPayload.ClientStateData> states = new HashMap<>();
    private final Map<Long, SyncTerritoryDataPayload.ClientClaimData> claims = new HashMap<>();
    private final Map<String, RelationType> relations = new HashMap<>();
    private final Map<UUID, SyncTerritoryDataPayload.ClientVassalData> vassalsByVassalId = new HashMap<>();
    private UUID playerStateId;
    private Role playerRole;

    // Cases and laws per state
    private final Map<UUID, List<SyncCasesAndLawsPayload.ClientLawData>> laws = new HashMap<>();
    private final Map<UUID, List<SyncCasesAndLawsPayload.ClientCaseData>> cases = new HashMap<>();

    // Admin petitions
    private final List<net.frontier.network.payloads.SyncAdminPetitionsPayload.AdminPetitionData> adminPetitions = new ArrayList<>();

    public static FrontierClientCache get() {
        return instance;
    }

    public synchronized void updateTerritoryData(SyncTerritoryDataPayload payload) {
        states.clear();
        for (SyncTerritoryDataPayload.ClientStateData s : payload.states()) {
            states.put(s.id(), s);
        }

        claims.clear();
        for (SyncTerritoryDataPayload.ClientClaimData c : payload.claims()) {
            long key = (((long) c.chunkX()) << 32) | (c.chunkZ() & 0xFFFFFFFFL);
            claims.put(key, c);
        }

        relations.clear();
        for (SyncTerritoryDataPayload.ClientRelationData r : payload.relations()) {
            String key = getRelationKey(r.stateA(), r.stateB());
            relations.put(key, r.relation());
        }

        vassalsByVassalId.clear();
        for (SyncTerritoryDataPayload.ClientVassalData v : payload.vassals()) {
            vassalsByVassalId.put(v.vassalId(), v);
        }

        this.playerStateId = payload.playerStateId();
        this.playerRole = payload.playerRole();

        net.frontier.client.compat.XaeroMapCompat.refreshXaeroHighlights();
    }

    public synchronized void updateCasesAndLaws(SyncCasesAndLawsPayload payload) {
        laws.put(payload.stateId(), new ArrayList<>(payload.laws()));
        cases.put(payload.stateId(), new ArrayList<>(payload.cases()));
    }

    private static String getRelationKey(UUID a, UUID b) {
        if (a.compareTo(b) <= 0) {
            return a.toString() + "_" + b.toString();
        } else {
            return b.toString() + "_" + a.toString();
        }
    }

    public SyncTerritoryDataPayload.ClientClaimData getClaim(int cx, int cz) {
        long key = (((long) cx) << 32) | (cz & 0xFFFFFFFFL);
        return claims.get(key);
    }

    public SyncTerritoryDataPayload.ClientStateData getState(UUID id) {
        if (id == null) return null;
        return states.get(id);
    }

    public Collection<SyncTerritoryDataPayload.ClientStateData> getAllStates() {
        return states.values();
    }

    public Map<Long, SyncTerritoryDataPayload.ClientClaimData> getAllClaims() {
        return claims;
    }

    public RelationType getRelation(UUID stateA, UUID stateB) {
        if (stateA == null || stateB == null || stateA.equals(stateB)) {
            return RelationType.NEUTRAL;
        }
        return relations.getOrDefault(getRelationKey(stateA, stateB), RelationType.NEUTRAL);
    }

    public UUID getPlayerStateId() {
        return playerStateId;
    }

    public Role getPlayerRole() {
        return playerRole;
    }

    public List<SyncCasesAndLawsPayload.ClientLawData> getLaws(UUID stateId) {
        return laws.getOrDefault(stateId, Collections.emptyList());
    }

    public List<SyncCasesAndLawsPayload.ClientCaseData> getCases(UUID stateId) {
        return cases.getOrDefault(stateId, Collections.emptyList());
    }

    public SyncTerritoryDataPayload.ClientVassalData getSuzerainPact(UUID vassalId) {
        if (vassalId == null) return null;
        return vassalsByVassalId.get(vassalId);
    }

    public List<SyncTerritoryDataPayload.ClientVassalData> getDirectVassals(UUID suzerainId) {
        if (suzerainId == null) return Collections.emptyList();
        List<SyncTerritoryDataPayload.ClientVassalData> list = new ArrayList<>();
        for (SyncTerritoryDataPayload.ClientVassalData v : vassalsByVassalId.values()) {
            if (suzerainId.equals(v.suzerainId())) {
                list.add(v);
            }
        }
        return list;
    }

    public Collection<SyncTerritoryDataPayload.ClientVassalData> getAllVassals() {
        return vassalsByVassalId.values();
    }

    public synchronized void updateAdminPetitions(net.frontier.network.payloads.SyncAdminPetitionsPayload payload) {
        adminPetitions.clear();
        adminPetitions.addAll(payload.petitions());
    }

    public List<net.frontier.network.payloads.SyncAdminPetitionsPayload.AdminPetitionData> getAdminPetitions() {
        return Collections.unmodifiableList(adminPetitions);
    }
}
