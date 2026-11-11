package net.frontier.core;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.frontier.api.*;
import net.frontier.data.db.DatabaseManager;
import net.frontier.data.model.*;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class FrontierServerCache {
    private static final Logger LOGGER = LoggerFactory.getLogger(FrontierServerCache.class);
    private static FrontierServerCache instance;

    private final DatabaseManager databaseManager;

    // Dimension -> (ChunkKey -> TerritoryChunk)
    private final Map<String, Long2ObjectMap<TerritoryChunk>> territoryGrids = new ConcurrentHashMap<>();

    // StateId -> FrontierState
    private final Map<UUID, FrontierState> states = new ConcurrentHashMap<>();

    // StateId -> (PlayerUuid -> StateMember)
    private final Map<UUID, Map<UUID, StateMember>> stateMembers = new ConcurrentHashMap<>();

    // PlayerUuid -> StateId
    private final Map<UUID, UUID> playerToState = new ConcurrentHashMap<>();

    // StateId -> List of Laws
    private final Map<UUID, List<Law>> stateLaws = new ConcurrentHashMap<>();

    // Sorted pair key (UUID1_UUID2) -> DiplomacyRelation
    private final Map<String, DiplomacyRelation> relations = new ConcurrentHashMap<>();

    // StateId -> List of CourtCases
    private final Map<UUID, List<CourtCase>> stateCases = new ConcurrentHashMap<>();

    // StateId -> Set of Wanted Player UUIDs
    private final Map<UUID, Set<UUID>> stateOutlaws = new ConcurrentHashMap<>();

    // Petitions
    private final List<StatePetition> petitions = new CopyOnWriteArrayList<>();

    // VassalId -> VassalPact
    private final Map<UUID, VassalPact> vassalPacts = new ConcurrentHashMap<>();

    // Territory Zones (Block-level regions)
    private final Map<UUID, TerritoryZone> zones = new ConcurrentHashMap<>();
    private final Map<String, Long2ObjectMap<List<TerritoryZone>>> dimensionZones = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerSelection> playerSelections = new ConcurrentHashMap<>();

    public FrontierServerCache(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
        instance = this;
    }

    public static FrontierServerCache get() {
        return instance;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public synchronized void loadFromDatabase() throws SQLException {
        LOGGER.info("[Frontier] Loading world data into RAM cache...");

        // Clear every derived index before rebuilding it, so reloads cannot retain
        // entries that were deleted from the database since the previous load.
        states.clear();
        stateMembers.clear();
        playerToState.clear();
        stateLaws.clear();
        stateCases.clear();
        stateOutlaws.clear();
        territoryGrids.clear();
        relations.clear();
        petitions.clear();
        vassalPacts.clear();
        zones.clear();
        dimensionZones.clear();
        for (FrontierState state : databaseManager.loadAllStates()) {
            states.put(state.getId(), state);
            stateMembers.put(state.getId(), new ConcurrentHashMap<>());
            stateLaws.put(state.getId(), new CopyOnWriteArrayList<>());
            stateCases.put(state.getId(), new CopyOnWriteArrayList<>());
            stateOutlaws.put(state.getId(), ConcurrentHashMap.newKeySet());
        }

        // Members
        for (StateMember member : databaseManager.loadAllMembers()) {
            Map<UUID, StateMember> membersMap = stateMembers.computeIfAbsent(member.getStateId(), k -> new ConcurrentHashMap<>());
            membersMap.put(member.getPlayerUuid(), member);
            playerToState.put(member.getPlayerUuid(), member.getStateId());
        }

        // Claims
        territoryGrids.clear();
        for (TerritoryChunk claim : databaseManager.loadAllClaims()) {
            Long2ObjectMap<TerritoryChunk> grid = territoryGrids.computeIfAbsent(claim.getDimension(), k -> new Long2ObjectOpenHashMap<>());
            grid.put(claim.getChunkKey(), claim);
        }

        // Laws
        for (Law law : databaseManager.loadAllLaws()) {
            List<Law> laws = stateLaws.computeIfAbsent(law.getStateId(), k -> new CopyOnWriteArrayList<>());
            laws.add(law);
        }

        // Relations
        relations.clear();
        for (DiplomacyRelation rel : databaseManager.loadAllRelations()) {
            relations.put(getRelationKey(rel.getStateA(), rel.getStateB()), rel);
        }

        // Cases & Outlaws
        for (CourtCase c : databaseManager.loadAllCases()) {
            List<CourtCase> list = stateCases.computeIfAbsent(c.getStateId(), k -> new CopyOnWriteArrayList<>());
            list.add(c);
            if (c.getStatus() == CaseStatus.GUILTY && c.getAccusedUuid() != null) {
                Set<UUID> outlaws = stateOutlaws.computeIfAbsent(c.getStateId(), k -> ConcurrentHashMap.newKeySet());
                outlaws.add(c.getAccusedUuid());
            }
        }

        // Petitions
        petitions.clear();
        petitions.addAll(databaseManager.loadAllPetitions());

        // Vassals
        vassalPacts.clear();
        for (VassalPact pact : databaseManager.loadAllVassals()) {
            vassalPacts.put(pact.getVassalId(), pact);
        }

        // Zones
        zones.clear();
        dimensionZones.clear();
        for (TerritoryZone z : databaseManager.loadAllZones()) {
            zones.put(z.getId(), z);
            indexZone(z);
        }

        LOGGER.info("[Frontier] RAM cache loaded: {} states, {} claims, {} zones, {} laws, {} relations, {} vassals.",
                states.size(), getTotalClaimsCount(), zones.size(), stateLaws.values().stream().mapToInt(List::size).sum(), relations.size(), vassalPacts.size());
    }

    public static String getRelationKey(UUID a, UUID b) {
        if (a.compareTo(b) <= 0) {
            return a.toString() + "_" + b.toString();
        } else {
            return b.toString() + "_" + a.toString();
        }
    }

    public int getTotalClaimsCount() {
        int count = 0;
        for (Long2ObjectMap<TerritoryChunk> map : territoryGrids.values()) {
            count += map.size();
        }
        return count;
    }

    // --- Territory queries ---

    public TerritoryChunk getChunk(String dimension, int chunkX, int chunkZ) {
        Long2ObjectMap<TerritoryChunk> grid = territoryGrids.get(dimension);
        if (grid == null) return null;
        return grid.get(TerritoryChunk.toChunkKey(chunkX, chunkZ));
    }

    public JurisdictionType getJurisdiction(String dimension, int chunkX, int chunkZ) {
        TerritoryChunk chunk = getChunk(dimension, chunkX, chunkZ);
        if (chunk == null) {
            return JurisdictionType.WILD;
        }
        return chunk.getJurisdictionType();
    }

    public FrontierState getStateAt(String dimension, int chunkX, int chunkZ) {
        TerritoryChunk chunk = getChunk(dimension, chunkX, chunkZ);
        if (chunk == null || chunk.getStateId() == null) {
            return null;
        }
        return states.get(chunk.getStateId());
    }

    public JurisdictionType getJurisdiction(String dimension, int x, int y, int z) {
        TerritoryZone zone = getZoneAt(dimension, x, y, z);
        if (zone != null) {
            return zone.getJurisdictionType();
        }
        Long2ObjectMap<List<TerritoryZone>> zGrid = dimensionZones.get(dimension);
        if (zGrid != null && zGrid.containsKey(TerritoryChunk.toChunkKey(x >> 4, z >> 4))) {
            return JurisdictionType.WILD;
        }
        return getJurisdiction(dimension, x >> 4, z >> 4);
    }

    public JurisdictionType getJurisdiction(String dimension, BlockPos pos) {
        return getJurisdiction(dimension, pos.getX(), pos.getY(), pos.getZ());
    }

    public FrontierState getStateAt(String dimension, int x, int y, int z) {
        TerritoryZone zone = getZoneAt(dimension, x, y, z);
        if (zone != null && zone.getStateId() != null) {
            return states.get(zone.getStateId());
        }
        Long2ObjectMap<List<TerritoryZone>> zGrid = dimensionZones.get(dimension);
        if (zGrid != null && zGrid.containsKey(TerritoryChunk.toChunkKey(x >> 4, z >> 4))) {
            return null;
        }
        return getStateAt(dimension, x >> 4, z >> 4);
    }

    public FrontierState getStateAt(String dimension, BlockPos pos) {
        return getStateAt(dimension, pos.getX(), pos.getY(), pos.getZ());
    }

    // --- Player Selections (WorldEdit style) ---

    public PlayerSelection getPlayerSelection(UUID playerUuid) {
        return playerSelections.computeIfAbsent(playerUuid, PlayerSelection::new);
    }

    public void clearPlayerSelection(UUID playerUuid) {
        playerSelections.remove(playerUuid);
    }

    // --- Territory Zones (Block-level Regions) ---

    public TerritoryZone getZoneAt(String dimension, int x, int y, int z) {
        Long2ObjectMap<List<TerritoryZone>> grid = dimensionZones.get(dimension);
        if (grid == null) return null;
        long key = TerritoryChunk.toChunkKey(x >> 4, z >> 4);
        List<TerritoryZone> list = grid.get(key);
        if (list != null) {
            for (TerritoryZone zZone : list) {
                if (zZone.contains(x, y, z)) {
                    return zZone;
                }
            }
        }
        return null;
    }

    public TerritoryZone getZoneAt(String dimension, BlockPos pos) {
        return getZoneAt(dimension, pos.getX(), pos.getY(), pos.getZ());
    }

    public synchronized void addZone(TerritoryZone zone) {
        zones.put(zone.getId(), zone);
        indexZone(zone);
        databaseManager.saveZone(zone);
    }

    public synchronized void removeZone(UUID zoneId) {
        TerritoryZone zone = zones.remove(zoneId);
        if (zone != null) {
            deindexZone(zone);
            databaseManager.deleteZone(zoneId);
        }
    }

    public Collection<TerritoryZone> getAllZones() {
        return Collections.unmodifiableCollection(zones.values());
    }

    public List<TerritoryZone> getZonesOfState(UUID stateId) {
        if (stateId == null) return Collections.emptyList();
        List<TerritoryZone> list = new ArrayList<>();
        for (TerritoryZone z : zones.values()) {
            if (stateId.equals(z.getStateId())) {
                list.add(z);
            }
        }
        return list;
    }

    public TerritoryZone getZoneById(UUID zoneId) {
        return zones.get(zoneId);
    }

    private void indexZone(TerritoryZone zone) {
        Long2ObjectMap<List<TerritoryZone>> grid = dimensionZones.computeIfAbsent(zone.getDimension(), k -> new Long2ObjectOpenHashMap<>());
        Long2ObjectMap<TerritoryChunk> claimGrid = territoryGrids.computeIfAbsent(zone.getDimension(), k -> new Long2ObjectOpenHashMap<>());
        int minCx = zone.getMinX() >> 4;
        int maxCx = zone.getMaxX() >> 4;
        int minCz = zone.getMinZ() >> 4;
        int maxCz = zone.getMaxZ() >> 4;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                long key = TerritoryChunk.toChunkKey(cx, cz);
                grid.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(zone);

                if (!claimGrid.containsKey(key)) {
                    claimGrid.put(key, new TerritoryChunk(zone.getDimension(), cx, cz, zone.getStateId(), zone.getJurisdictionType(), zone.getCreatedBy(), zone.getCreatedAt()));
                }
            }
        }
    }

    private void deindexZone(TerritoryZone zone) {
        Long2ObjectMap<List<TerritoryZone>> grid = dimensionZones.get(zone.getDimension());
        if (grid == null) return;
        Long2ObjectMap<TerritoryChunk> claimGrid = territoryGrids.get(zone.getDimension());
        int minCx = zone.getMinX() >> 4;
        int maxCx = zone.getMaxX() >> 4;
        int minCz = zone.getMinZ() >> 4;
        int maxCz = zone.getMaxZ() >> 4;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                long key = TerritoryChunk.toChunkKey(cx, cz);
                List<TerritoryZone> list = grid.get(key);
                if (list != null) {
                    list.remove(zone);
                    if (list.isEmpty()) {
                        grid.remove(key);
                        if (claimGrid != null) {
                            claimGrid.remove(key);
                        }
                    }
                }
            }
        }
    }

    public synchronized void setClaim(TerritoryChunk chunk) {
        Long2ObjectMap<TerritoryChunk> grid = territoryGrids.computeIfAbsent(chunk.getDimension(), k -> new Long2ObjectOpenHashMap<>());
        grid.put(chunk.getChunkKey(), chunk);
        databaseManager.saveClaim(chunk);
    }

    public synchronized void unclaim(String dimension, int chunkX, int chunkZ) {
        Long2ObjectMap<TerritoryChunk> grid = territoryGrids.get(dimension);
        if (grid != null) {
            grid.remove(TerritoryChunk.toChunkKey(chunkX, chunkZ));
        }
        databaseManager.removeClaim(dimension, chunkX, chunkZ);
    }

    public List<TerritoryChunk> getClaimsForState(UUID stateId) {
        List<TerritoryChunk> list = new ArrayList<>();
        for (Long2ObjectMap<TerritoryChunk> grid : territoryGrids.values()) {
            for (TerritoryChunk chunk : grid.values()) {
                if (stateId.equals(chunk.getStateId())) {
                    list.add(chunk);
                }
            }
        }
        return list;
    }

    public Map<Long, TerritoryChunk> getDimensionChunks(String dimension) {
        Long2ObjectMap<TerritoryChunk> grid = territoryGrids.get(dimension);
        if (grid == null) return Collections.emptyMap();
        return Collections.unmodifiableMap(grid);
    }

    // --- States ---

    public Collection<FrontierState> getAllStates() {
        return Collections.unmodifiableCollection(states.values());
    }

    public FrontierState getState(UUID stateId) {
        if (stateId == null) return null;
        return states.get(stateId);
    }

    public FrontierState getStateByName(String name) {
        for (FrontierState state : states.values()) {
            if (state.getName().equalsIgnoreCase(name)) {
                return state;
            }
        }
        return null;
    }

    public FrontierState getStateByTag(String tag) {
        for (FrontierState state : states.values()) {
            if (state.getTag().equalsIgnoreCase(tag)) {
                return state;
            }
        }
        return null;
    }

    public FrontierState getPlayerState(UUID playerUuid) {
        UUID stateId = playerToState.get(playerUuid);
        if (stateId == null) return null;
        return states.get(stateId);
    }

    public StateMember getMember(UUID stateId, UUID playerUuid) {
        Map<UUID, StateMember> members = stateMembers.get(stateId);
        if (members == null) return null;
        return members.get(playerUuid);
    }

    public Collection<StateMember> getStateMembers(UUID stateId) {
        Map<UUID, StateMember> members = stateMembers.get(stateId);
        if (members == null) return Collections.emptyList();
        return Collections.unmodifiableCollection(members.values());
    }

    public synchronized void registerState(FrontierState state, StateMember leaderMember) {
        states.put(state.getId(), state);
        Map<UUID, StateMember> members = new ConcurrentHashMap<>();
        members.put(leaderMember.getPlayerUuid(), leaderMember);
        stateMembers.put(state.getId(), members);
        playerToState.put(leaderMember.getPlayerUuid(), state.getId());
        stateLaws.put(state.getId(), new CopyOnWriteArrayList<>());
        stateCases.put(state.getId(), new CopyOnWriteArrayList<>());
        stateOutlaws.put(state.getId(), ConcurrentHashMap.newKeySet());

        databaseManager.saveOrUpdateState(state);
        databaseManager.saveOrUpdateMember(leaderMember);
    }

    public synchronized void addMember(StateMember member) {
        Map<UUID, StateMember> members = stateMembers.computeIfAbsent(member.getStateId(), k -> new ConcurrentHashMap<>());
        members.put(member.getPlayerUuid(), member);
        playerToState.put(member.getPlayerUuid(), member.getStateId());
        databaseManager.saveOrUpdateMember(member);
    }

    public synchronized void removeMember(UUID stateId, UUID playerUuid) {
        Map<UUID, StateMember> members = stateMembers.get(stateId);
        if (members != null) {
            members.remove(playerUuid);
        }
        playerToState.remove(playerUuid);
        databaseManager.removeMember(stateId, playerUuid);
    }

    public synchronized void disbandState(UUID stateId) {
        FrontierState state = states.remove(stateId);
        if (state != null) {
            Map<UUID, StateMember> members = stateMembers.remove(stateId);
            if (members != null) {
                for (UUID playerUuid : members.keySet()) {
                    playerToState.remove(playerUuid);
                }
            }
            stateLaws.remove(stateId);
            stateCases.remove(stateId);
            stateOutlaws.remove(stateId);

            // Remove all claims of this state
            for (Long2ObjectMap<TerritoryChunk> grid : territoryGrids.values()) {
                grid.values().removeIf(chunk -> stateId.equals(chunk.getStateId()));
            }

            databaseManager.deleteState(stateId);
        }
    }

    // --- Laws ---

    public List<Law> getLaws(UUID stateId) {
        List<Law> list = stateLaws.get(stateId);
        if (list == null) return Collections.emptyList();
        return Collections.unmodifiableList(list);
    }

    public synchronized void saveLaw(Law law) {
        List<Law> list = stateLaws.computeIfAbsent(law.getStateId(), k -> new CopyOnWriteArrayList<>());
        list.removeIf(l -> l.getId() == law.getId() && l.getId() > 0);
        list.add(law);
        databaseManager.saveOrUpdateLaw(law);
    }

    // --- Diplomacy ---

    public RelationType getRelation(UUID stateA, UUID stateB) {
        if (stateA == null || stateB == null || stateA.equals(stateB)) {
            return RelationType.NEUTRAL;
        }
        DiplomacyRelation rel = relations.get(getRelationKey(stateA, stateB));
        if (rel == null) {
            return RelationType.NEUTRAL;
        }
        return rel.getRelation();
    }

    public synchronized void setRelation(DiplomacyRelation rel) {
        relations.put(getRelationKey(rel.getStateA(), rel.getStateB()), rel);
        databaseManager.saveRelation(rel);
    }

    public boolean isAtWar(UUID stateA, UUID stateB) {
        return getRelation(stateA, stateB) == RelationType.WAR;
    }

    public boolean isAllied(UUID stateA, UUID stateB) {
        return getRelation(stateA, stateB) == RelationType.ALLIANCE;
    }

    // --- Cases & Outlaws ---

    public List<CourtCase> getCases(UUID stateId) {
        List<CourtCase> list = stateCases.get(stateId);
        if (list == null) return Collections.emptyList();
        return Collections.unmodifiableList(list);
    }

    public synchronized void saveCourtCase(CourtCase courtCase) {
        List<CourtCase> list = stateCases.computeIfAbsent(courtCase.getStateId(), k -> new CopyOnWriteArrayList<>());
        list.removeIf(c -> c.getId() == courtCase.getId() && c.getId() > 0);
        list.add(courtCase);

        Set<UUID> outlaws = stateOutlaws.computeIfAbsent(courtCase.getStateId(), k -> ConcurrentHashMap.newKeySet());
        if (courtCase.getStatus() == CaseStatus.GUILTY && courtCase.getAccusedUuid() != null) {
            outlaws.add(courtCase.getAccusedUuid());
        } else if (courtCase.getAccusedUuid() != null) {
            outlaws.remove(courtCase.getAccusedUuid());
        }

        databaseManager.saveOrUpdateCase(courtCase);
    }

    public boolean isWantedInState(UUID stateId, UUID playerUuid) {
        if (stateId == null || playerUuid == null) return false;
        Set<UUID> outlaws = stateOutlaws.get(stateId);
        return outlaws != null && outlaws.contains(playerUuid);
    }

    // --- Petitions ---

    public List<StatePetition> getPetitions() {
        return Collections.unmodifiableList(petitions);
    }

    public synchronized void addPetition(StatePetition petition) {
        petitions.add(petition);
        databaseManager.savePetition(petition);
    }

    public synchronized void updatePetition(StatePetition petition) {
        databaseManager.savePetition(petition);
    }

    // --- Feudal Vassalage & Hierarchy ---

    public VassalPact getSuzerainPact(UUID vassalId) {
        if (vassalId == null) return null;
        return vassalPacts.get(vassalId);
    }

    public FrontierState getSuzerainState(UUID vassalId) {
        VassalPact pact = getSuzerainPact(vassalId);
        return pact != null ? getState(pact.getSuzerainId()) : null;
    }

    public List<VassalPact> getDirectVassals(UUID suzerainId) {
        if (suzerainId == null) return Collections.emptyList();
        List<VassalPact> list = new ArrayList<>();
        for (VassalPact pact : vassalPacts.values()) {
            if (suzerainId.equals(pact.getSuzerainId())) {
                list.add(pact);
            }
        }
        return list;
    }

    public Collection<VassalPact> getAllVassalPacts() {
        return Collections.unmodifiableCollection(vassalPacts.values());
    }

    /**
     * Strict feudal principle: "Вассал моего вассала — не мой вассал"
     * Returns true ONLY if the state is a direct subordinate.
     */
    public boolean isDirectVassal(UUID vassalId, UUID suzerainId) {
        if (vassalId == null || suzerainId == null) return false;
        VassalPact pact = vassalPacts.get(vassalId);
        return pact != null && suzerainId.equals(pact.getSuzerainId());
    }

    public synchronized void setVassalage(UUID suzerainId, UUID vassalId, String title, int tribute) {
        if (suzerainId.equals(vassalId)) return;
        VassalPact pact = new VassalPact(suzerainId, vassalId, System.currentTimeMillis(), title, tribute);
        vassalPacts.put(vassalId, pact);
        databaseManager.saveVassalPact(pact);
    }

    public synchronized void breakVassalage(UUID vassalId) {
        if (vassalId == null) return;
        vassalPacts.remove(vassalId);
        databaseManager.removeVassalPact(vassalId);
    }
}
