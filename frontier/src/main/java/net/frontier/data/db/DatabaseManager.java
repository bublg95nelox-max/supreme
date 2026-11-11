package net.frontier.data.db;

import net.frontier.api.*;
import net.frontier.data.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

public class DatabaseManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseManager.class);

    private final File dbFile;
    private Connection connection;
    private final ExecutorService executorService;
    private volatile boolean isRunning = true;

    public DatabaseManager(File dbFile) {
        this.dbFile = dbFile;
        this.executorService = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Frontier-DB-Worker");
            t.setDaemon(true);
            return t;
        });
    }

    public synchronized void initialize() throws SQLException {
        if (!dbFile.getParentFile().exists()) {
            dbFile.getParentFile().mkdirs();
        }

        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        this.connection = DriverManager.getConnection(url);

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA journal_mode = WAL;");
            stmt.execute("PRAGMA synchronous = NORMAL;");
            stmt.execute("PRAGMA foreign_keys = ON;");

            // States
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS frontier_states (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    tag TEXT NOT NULL,
                    color INTEGER NOT NULL,
                    leader_uuid TEXT NOT NULL,
                    leader_name TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    motd TEXT DEFAULT ''
                );
            """);

            // Members
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS frontier_members (
                    state_id TEXT NOT NULL,
                    player_uuid TEXT NOT NULL,
                    player_name TEXT NOT NULL,
                    role TEXT NOT NULL,
                    joined_at INTEGER NOT NULL,
                    PRIMARY KEY (state_id, player_uuid),
                    FOREIGN KEY (state_id) REFERENCES frontier_states(id) ON DELETE CASCADE
                );
            """);

            // Territory Claims
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS frontier_claims (
                    dimension TEXT NOT NULL,
                    chunk_x INTEGER NOT NULL,
                    chunk_z INTEGER NOT NULL,
                    state_id TEXT,
                    jurisdiction_type TEXT NOT NULL,
                    claimed_by TEXT NOT NULL,
                    claimed_at INTEGER NOT NULL,
                    PRIMARY KEY (dimension, chunk_x, chunk_z)
                );
            """);

            // Laws
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS frontier_laws (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    state_id TEXT NOT NULL,
                    title TEXT NOT NULL,
                    content TEXT NOT NULL,
                    penalty TEXT NOT NULL,
                    created_by TEXT NOT NULL,
                    author_name TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    FOREIGN KEY (state_id) REFERENCES frontier_states(id) ON DELETE CASCADE
                );
            """);

            // Diplomacy Relations
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS frontier_relations (
                    state_a TEXT NOT NULL,
                    state_b TEXT NOT NULL,
                    relation TEXT NOT NULL,
                    declared_by TEXT NOT NULL,
                    updated_at INTEGER NOT NULL,
                    terms TEXT DEFAULT '',
                    PRIMARY KEY (state_a, state_b)
                );
            """);

            // Clues / Traces
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS frontier_clues (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    dimension TEXT NOT NULL,
                    x INTEGER NOT NULL,
                    y INTEGER NOT NULL,
                    z INTEGER NOT NULL,
                    chunk_x INTEGER NOT NULL,
                    chunk_z INTEGER NOT NULL,
                    crime_type TEXT NOT NULL,
                    grade TEXT NOT NULL,
                    description TEXT NOT NULL,
                    actor_uuid TEXT NOT NULL,
                    actor_name TEXT NOT NULL,
                    tool_used TEXT NOT NULL,
                    timestamp INTEGER NOT NULL
                );
            """);

            // Court Cases
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS frontier_cases (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    state_id TEXT NOT NULL,
                    title TEXT NOT NULL,
                    accused_uuid TEXT NOT NULL,
                    accused_name TEXT NOT NULL,
                    plaintiff_uuid TEXT NOT NULL,
                    plaintiff_name TEXT NOT NULL,
                    judge_uuid TEXT NOT NULL,
                    judge_name TEXT NOT NULL,
                    status TEXT NOT NULL,
                    verdict TEXT DEFAULT '',
                    created_at INTEGER NOT NULL,
                    resolved_at INTEGER NOT NULL,
                    FOREIGN KEY (state_id) REFERENCES frontier_states(id) ON DELETE CASCADE
                );
            """);

            // Case Evidence links
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS frontier_case_evidence (
                    case_id INTEGER NOT NULL,
                    clue_id INTEGER NOT NULL,
                    notes TEXT DEFAULT '',
                    added_at INTEGER NOT NULL,
                    PRIMARY KEY (case_id, clue_id),
                    FOREIGN KEY (case_id) REFERENCES frontier_cases(id) ON DELETE CASCADE
                );
            """);

            // Petitions
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS frontier_petitions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    requester_uuid TEXT NOT NULL,
                    requester_name TEXT NOT NULL,
                    state_name TEXT NOT NULL,
                    state_tag TEXT NOT NULL,
                    color INTEGER NOT NULL,
                    dimension TEXT NOT NULL,
                    capital_chunk_x INTEGER NOT NULL,
                    capital_chunk_z INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    submitted_at INTEGER NOT NULL
                );
            """);

            // Feudal Vassals
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS frontier_vassals (
                    suzerain_id TEXT NOT NULL,
                    vassal_id TEXT PRIMARY KEY,
                    pledged_at INTEGER NOT NULL,
                    title TEXT NOT NULL,
                    tribute INTEGER DEFAULT 0,
                    FOREIGN KEY (suzerain_id) REFERENCES frontier_states(id) ON DELETE CASCADE,
                    FOREIGN KEY (vassal_id) REFERENCES frontier_states(id) ON DELETE CASCADE
                );
            """);

            // Territory Zones (Block-level regions)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS frontier_zones (
                    id TEXT PRIMARY KEY,
                    state_id TEXT,
                    name TEXT NOT NULL,
                    dimension TEXT NOT NULL,
                    min_x INTEGER NOT NULL,
                    min_y INTEGER NOT NULL,
                    min_z INTEGER NOT NULL,
                    max_x INTEGER NOT NULL,
                    max_y INTEGER NOT NULL,
                    max_z INTEGER NOT NULL,
                    jurisdiction TEXT NOT NULL,
                    created_by TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                );
            """);
        }
        LOGGER.info("[Frontier] SQLite database initialized successfully at {}", dbFile.getAbsolutePath());
    }

    @FunctionalInterface
    public interface SqlTask {
        void run() throws SQLException;
    }

    public void executeAsync(SqlTask task) {
        if (!isRunning) return;
        executorService.submit(() -> {
            try {
                task.run();
            } catch (Exception e) {
                LOGGER.error("[Frontier] Error during async database operation", e);
            }
        });
    }

    // --- Synchronous load methods for startup ---

    public List<FrontierState> loadAllStates() throws SQLException {
        List<FrontierState> list = new ArrayList<>();
        String sql = "SELECT * FROM frontier_states";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new FrontierState(
                    UUID.fromString(rs.getString("id")),
                    rs.getString("name"),
                    rs.getString("tag"),
                    rs.getInt("color"),
                    UUID.fromString(rs.getString("leader_uuid")),
                    rs.getString("leader_name"),
                    rs.getLong("created_at"),
                    rs.getString("motd")
                ));
            }
        }
        return list;
    }

    public List<StateMember> loadAllMembers() throws SQLException {
        List<StateMember> list = new ArrayList<>();
        String sql = "SELECT * FROM frontier_members";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new StateMember(
                    UUID.fromString(rs.getString("state_id")),
                    UUID.fromString(rs.getString("player_uuid")),
                    rs.getString("player_name"),
                    Role.valueOf(rs.getString("role")),
                    rs.getLong("joined_at")
                ));
            }
        }
        return list;
    }

    public List<TerritoryChunk> loadAllClaims() throws SQLException {
        List<TerritoryChunk> list = new ArrayList<>();
        String sql = "SELECT * FROM frontier_claims";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String stateIdStr = rs.getString("state_id");
                UUID stateId = stateIdStr != null ? UUID.fromString(stateIdStr) : null;
                list.add(new TerritoryChunk(
                    rs.getString("dimension"),
                    rs.getInt("chunk_x"),
                    rs.getInt("chunk_z"),
                    stateId,
                    JurisdictionType.valueOf(rs.getString("jurisdiction_type")),
                    UUID.fromString(rs.getString("claimed_by")),
                    rs.getLong("claimed_at")
                ));
            }
        }
        return list;
    }

    public List<Law> loadAllLaws() throws SQLException {
        List<Law> list = new ArrayList<>();
        String sql = "SELECT * FROM frontier_laws";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new Law(
                    rs.getInt("id"),
                    UUID.fromString(rs.getString("state_id")),
                    rs.getString("title"),
                    rs.getString("content"),
                    rs.getString("penalty"),
                    UUID.fromString(rs.getString("created_by")),
                    rs.getString("author_name"),
                    rs.getLong("created_at"),
                    LawStatus.valueOf(rs.getString("status"))
                ));
            }
        }
        return list;
    }

    public List<DiplomacyRelation> loadAllRelations() throws SQLException {
        List<DiplomacyRelation> list = new ArrayList<>();
        String sql = "SELECT * FROM frontier_relations";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new DiplomacyRelation(
                    UUID.fromString(rs.getString("state_a")),
                    UUID.fromString(rs.getString("state_b")),
                    RelationType.valueOf(rs.getString("relation")),
                    UUID.fromString(rs.getString("declared_by")),
                    rs.getLong("updated_at"),
                    rs.getString("terms")
                ));
            }
        }
        return list;
    }

    public List<CourtCase> loadAllCases() throws SQLException {
        List<CourtCase> list = new ArrayList<>();
        String sql = "SELECT * FROM frontier_cases";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new CourtCase(
                    rs.getInt("id"),
                    UUID.fromString(rs.getString("state_id")),
                    rs.getString("title"),
                    UUID.fromString(rs.getString("accused_uuid")),
                    rs.getString("accused_name"),
                    UUID.fromString(rs.getString("plaintiff_uuid")),
                    rs.getString("plaintiff_name"),
                    UUID.fromString(rs.getString("judge_uuid")),
                    rs.getString("judge_name"),
                    CaseStatus.valueOf(rs.getString("status")),
                    rs.getString("verdict"),
                    rs.getLong("created_at"),
                    rs.getLong("resolved_at")
                ));
            }
        }
        return list;
    }

    public List<StatePetition> loadAllPetitions() throws SQLException {
        List<StatePetition> list = new ArrayList<>();
        String sql = "SELECT * FROM frontier_petitions";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new StatePetition(
                    rs.getInt("id"),
                    UUID.fromString(rs.getString("requester_uuid")),
                    rs.getString("requester_name"),
                    rs.getString("state_name"),
                    rs.getString("state_tag"),
                    rs.getInt("color"),
                    rs.getString("dimension"),
                    rs.getInt("capital_chunk_x"),
                    rs.getInt("capital_chunk_z"),
                    rs.getString("status"),
                    rs.getLong("submitted_at")
                ));
            }
        }
        return list;
    }

    public List<VassalPact> loadAllVassals() throws SQLException {
        List<VassalPact> list = new ArrayList<>();
        String sql = "SELECT * FROM frontier_vassals";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new VassalPact(
                    UUID.fromString(rs.getString("suzerain_id")),
                    UUID.fromString(rs.getString("vassal_id")),
                    rs.getLong("pledged_at"),
                    rs.getString("title"),
                    rs.getInt("tribute")
                ));
            }
        }
        return list;
    }

    // --- Async write persistence methods ---

    public void saveOrUpdateState(FrontierState state) {
        executeAsync(() -> {
            String sql = """
                INSERT INTO frontier_states (id, name, tag, color, leader_uuid, leader_name, created_at, motd)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    name=excluded.name,
                    tag=excluded.tag,
                    color=excluded.color,
                    leader_uuid=excluded.leader_uuid,
                    leader_name=excluded.leader_name,
                    motd=excluded.motd;
            """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, state.getId().toString());
                ps.setString(2, state.getName());
                ps.setString(3, state.getTag());
                ps.setInt(4, state.getColor());
                ps.setString(5, state.getLeaderUuid().toString());
                ps.setString(6, state.getLeaderName());
                ps.setLong(7, state.getCreatedAt());
                ps.setString(8, state.getMotd());
                ps.executeUpdate();
            }
        });
    }

    public void deleteState(UUID stateId) {
        executeAsync(() -> {
            String sql = "DELETE FROM frontier_states WHERE id = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, stateId.toString());
                ps.executeUpdate();
            }
        });
    }

    public void saveOrUpdateMember(StateMember member) {
        executeAsync(() -> {
            String sql = """
                INSERT INTO frontier_members (state_id, player_uuid, player_name, role, joined_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(state_id, player_uuid) DO UPDATE SET
                    player_name=excluded.player_name,
                    role=excluded.role;
            """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, member.getStateId().toString());
                ps.setString(2, member.getPlayerUuid().toString());
                ps.setString(3, member.getPlayerName());
                ps.setString(4, member.getRole().name());
                ps.setLong(5, member.getJoinedAt());
                ps.executeUpdate();
            }
        });
    }

    public void removeMember(UUID stateId, UUID playerUuid) {
        executeAsync(() -> {
            String sql = "DELETE FROM frontier_members WHERE state_id = ? AND player_uuid = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, stateId.toString());
                ps.setString(2, playerUuid.toString());
                ps.executeUpdate();
            }
        });
    }

    public void saveClaim(TerritoryChunk claim) {
        executeAsync(() -> {
            String sql = """
                INSERT INTO frontier_claims (dimension, chunk_x, chunk_z, state_id, jurisdiction_type, claimed_by, claimed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(dimension, chunk_x, chunk_z) DO UPDATE SET
                    state_id=excluded.state_id,
                    jurisdiction_type=excluded.jurisdiction_type,
                    claimed_by=excluded.claimed_by,
                    claimed_at=excluded.claimed_at;
            """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, claim.getDimension());
                ps.setInt(2, claim.getChunkX());
                ps.setInt(3, claim.getChunkZ());
                ps.setString(4, claim.getStateId() != null ? claim.getStateId().toString() : null);
                ps.setString(5, claim.getJurisdictionType().name());
                ps.setString(6, claim.getClaimedBy().toString());
                ps.setLong(7, claim.getClaimedAt());
                ps.executeUpdate();
            }
        });
    }

    public void removeClaim(String dimension, int chunkX, int chunkZ) {
        executeAsync(() -> {
            String sql = "DELETE FROM frontier_claims WHERE dimension = ? AND chunk_x = ? AND chunk_z = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, dimension);
                ps.setInt(2, chunkX);
                ps.setInt(3, chunkZ);
                ps.executeUpdate();
            }
        });
    }

    public void saveOrUpdateLaw(Law law) {
        executeAsync(() -> {
            if (law.getId() <= 0) {
                String sql = """
                    INSERT INTO frontier_laws (state_id, title, content, penalty, created_by, author_name, created_at, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
                try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, law.getStateId().toString());
                    ps.setString(2, law.getTitle());
                    ps.setString(3, law.getContent());
                    ps.setString(4, law.getPenalty());
                    ps.setString(5, law.getCreatedBy().toString());
                    ps.setString(6, law.getAuthorName());
                    ps.setLong(7, law.getCreatedAt());
                    ps.setString(8, law.getStatus().name());
                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        if (rs.next()) {
                            law.setId(rs.getInt(1));
                        }
                    }
                }
            } else {
                String sql = """
                    UPDATE frontier_laws SET title = ?, content = ?, penalty = ?, status = ? WHERE id = ?
                """;
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, law.getTitle());
                    ps.setString(2, law.getContent());
                    ps.setString(3, law.getPenalty());
                    ps.setString(4, law.getStatus().name());
                    ps.setInt(5, law.getId());
                    ps.executeUpdate();
                }
            }
        });
    }

    public void saveRelation(DiplomacyRelation rel) {
        executeAsync(() -> {
            String sql = """
                INSERT INTO frontier_relations (state_a, state_b, relation, declared_by, updated_at, terms)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(state_a, state_b) DO UPDATE SET
                    relation=excluded.relation,
                    declared_by=excluded.declared_by,
                    updated_at=excluded.updated_at,
                    terms=excluded.terms;
            """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, rel.getStateA().toString());
                ps.setString(2, rel.getStateB().toString());
                ps.setString(3, rel.getRelation().name());
                ps.setString(4, rel.getDeclaredBy().toString());
                ps.setLong(5, rel.getUpdatedAt());
                ps.setString(6, rel.getTerms());
                ps.executeUpdate();
            }
        });
    }

    public void saveClue(ClueMarker clue) {
        executeAsync(() -> {
            String sql = """
                INSERT INTO frontier_clues (dimension, x, y, z, chunk_x, chunk_z, crime_type, grade, description, actor_uuid, actor_name, tool_used, timestamp)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
            try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, clue.getDimension());
                ps.setInt(2, clue.getX());
                ps.setInt(3, clue.getY());
                ps.setInt(4, clue.getZ());
                ps.setInt(5, clue.getChunkX());
                ps.setInt(6, clue.getChunkZ());
                ps.setString(7, clue.getCrimeType().name());
                ps.setString(8, clue.getGrade().name());
                ps.setString(9, clue.getDescription());
                ps.setString(10, clue.getActorUuid().toString());
                ps.setString(11, clue.getActorName());
                ps.setString(12, clue.getToolUsed());
                ps.setLong(13, clue.getTimestamp());
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        clue.setId(rs.getInt(1));
                    }
                }
            }
        });
    }

    public List<ClueMarker> queryCluesInChunk(String dimension, int chunkX, int chunkZ, long sinceTimestamp) throws SQLException {
        List<ClueMarker> list = new ArrayList<>();
        String sql = """
            SELECT * FROM frontier_clues
            WHERE dimension = ? AND chunk_x = ? AND chunk_z = ? AND timestamp >= ?
            ORDER BY timestamp DESC LIMIT 50
        """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, dimension);
            ps.setInt(2, chunkX);
            ps.setInt(3, chunkZ);
            ps.setLong(4, sinceTimestamp);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new ClueMarker(
                        rs.getInt("id"),
                        rs.getString("dimension"),
                        rs.getInt("x"),
                        rs.getInt("y"),
                        rs.getInt("z"),
                        rs.getInt("chunk_x"),
                        rs.getInt("chunk_z"),
                        CrimeType.valueOf(rs.getString("crime_type")),
                        EvidenceGrade.valueOf(rs.getString("grade")),
                        rs.getString("description"),
                        UUID.fromString(rs.getString("actor_uuid")),
                        rs.getString("actor_name"),
                        rs.getString("tool_used"),
                        rs.getLong("timestamp")
                    ));
                }
            }
        }
        return list;
    }

    public void saveOrUpdateCase(CourtCase courtCase) {
        executeAsync(() -> {
            if (courtCase.getId() <= 0) {
                String sql = """
                    INSERT INTO frontier_cases (state_id, title, accused_uuid, accused_name, plaintiff_uuid, plaintiff_name, judge_uuid, judge_name, status, verdict, created_at, resolved_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
                try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, courtCase.getStateId().toString());
                    ps.setString(2, courtCase.getTitle());
                    ps.setString(3, courtCase.getAccusedUuid().toString());
                    ps.setString(4, courtCase.getAccusedName());
                    ps.setString(5, courtCase.getPlaintiffUuid().toString());
                    ps.setString(6, courtCase.getPlaintiffName());
                    ps.setString(7, courtCase.getJudgeUuid().toString());
                    ps.setString(8, courtCase.getJudgeName());
                    ps.setString(9, courtCase.getStatus().name());
                    ps.setString(10, courtCase.getVerdict());
                    ps.setLong(11, courtCase.getCreatedAt());
                    ps.setLong(12, courtCase.getResolvedAt());
                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        if (rs.next()) {
                            courtCase.setId(rs.getInt(1));
                        }
                    }
                }
            } else {
                String sql = """
                    UPDATE frontier_cases SET status = ?, verdict = ?, resolved_at = ? WHERE id = ?
                """;
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, courtCase.getStatus().name());
                    ps.setString(2, courtCase.getVerdict());
                    ps.setLong(3, courtCase.getResolvedAt());
                    ps.setInt(4, courtCase.getId());
                    ps.executeUpdate();
                }
            }
        });
    }

    public void savePetition(StatePetition petition) {
        executeAsync(() -> {
            if (petition.getId() <= 0) {
                String sql = """
                    INSERT INTO frontier_petitions (requester_uuid, requester_name, state_name, state_tag, color, dimension, capital_chunk_x, capital_chunk_z, status, submitted_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
                try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, petition.getRequesterUuid().toString());
                    ps.setString(2, petition.getRequesterName());
                    ps.setString(3, petition.getStateName());
                    ps.setString(4, petition.getStateTag());
                    ps.setInt(5, petition.getColor());
                    ps.setString(6, petition.getDimension());
                    ps.setInt(7, petition.getCapitalChunkX());
                    ps.setInt(8, petition.getCapitalChunkZ());
                    ps.setString(9, petition.getStatus());
                    ps.setLong(10, petition.getSubmittedAt());
                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        if (rs.next()) {
                            petition.setId(rs.getInt(1));
                        }
                    }
                }
            } else {
                String sql = "UPDATE frontier_petitions SET status = ? WHERE id = ?";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, petition.getStatus());
                    ps.setInt(2, petition.getId());
                    ps.executeUpdate();
                }
            }
        });
    }

    public void saveVassalPact(VassalPact pact) {
        executeAsync(() -> {
            String sql = """
                INSERT INTO frontier_vassals (suzerain_id, vassal_id, pledged_at, title, tribute)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(vassal_id) DO UPDATE SET
                    suzerain_id=excluded.suzerain_id,
                    pledged_at=excluded.pledged_at,
                    title=excluded.title,
                    tribute=excluded.tribute;
            """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, pact.getSuzerainId().toString());
                ps.setString(2, pact.getVassalId().toString());
                ps.setLong(3, pact.getPledgedAt());
                ps.setString(4, pact.getTitle());
                ps.setInt(5, pact.getTributePercent());
                ps.executeUpdate();
            }
        });
    }

    public void removeVassalPact(UUID vassalId) {
        executeAsync(() -> {
            String sql = "DELETE FROM frontier_vassals WHERE vassal_id = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, vassalId.toString());
                ps.executeUpdate();
            }
        });
    }

    public void saveZone(TerritoryZone zone) {
        executeAsync(() -> {
            String sql = """
                INSERT INTO frontier_zones (id, state_id, name, dimension, min_x, min_y, min_z, max_x, max_y, max_z, jurisdiction, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    state_id=excluded.state_id,
                    name=excluded.name,
                    dimension=excluded.dimension,
                    min_x=excluded.min_x,
                    min_y=excluded.min_y,
                    min_z=excluded.min_z,
                    max_x=excluded.max_x,
                    max_y=excluded.max_y,
                    max_z=excluded.max_z,
                    jurisdiction=excluded.jurisdiction;
            """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, zone.getId().toString());
                ps.setString(2, zone.getStateId() != null ? zone.getStateId().toString() : null);
                ps.setString(3, zone.getName());
                ps.setString(4, zone.getDimension());
                ps.setInt(5, zone.getMinX());
                ps.setInt(6, zone.getMinY());
                ps.setInt(7, zone.getMinZ());
                ps.setInt(8, zone.getMaxX());
                ps.setInt(9, zone.getMaxY());
                ps.setInt(10, zone.getMaxZ());
                ps.setString(11, zone.getJurisdictionType().name());
                ps.setString(12, zone.getCreatedBy().toString());
                ps.setLong(13, zone.getCreatedAt());
                ps.executeUpdate();
            }
        });
    }

    public void deleteZone(UUID zoneId) {
        executeAsync(() -> {
            String sql = "DELETE FROM frontier_zones WHERE id = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, zoneId.toString());
                ps.executeUpdate();
            }
        });
    }

    public List<TerritoryZone> loadAllZones() throws SQLException {
        List<TerritoryZone> list = new ArrayList<>();
        String sql = "SELECT * FROM frontier_zones";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                UUID id = UUID.fromString(rs.getString("id"));
                String sIdStr = rs.getString("state_id");
                UUID stateId = sIdStr != null ? UUID.fromString(sIdStr) : null;
                String name = rs.getString("name");
                String dimension = rs.getString("dimension");
                int minX = rs.getInt("min_x");
                int minY = rs.getInt("min_y");
                int minZ = rs.getInt("min_z");
                int maxX = rs.getInt("max_x");
                int maxY = rs.getInt("max_y");
                int maxZ = rs.getInt("max_z");
                JurisdictionType jurisdiction = JurisdictionType.valueOf(rs.getString("jurisdiction"));
                UUID createdBy = UUID.fromString(rs.getString("created_by"));
                long createdAt = rs.getLong("created_at");

                list.add(new TerritoryZone(id, stateId, name, dimension, minX, minY, minZ, maxX, maxY, maxZ, jurisdiction, createdBy, createdAt));
            }
        }
        return list;
    }

    public synchronized void shutdown() {
        isRunning = false;
        LOGGER.info("[Frontier] Shutting down database executor and flushing operations...");
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
            LOGGER.info("[Frontier] SQLite connection closed successfully.");
        } catch (Exception e) {
            LOGGER.error("[Frontier] Error closing SQLite database", e);
        }
    }
}
