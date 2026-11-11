package net.frontier;

import net.frontier.api.CaseStatus;
import net.frontier.api.JurisdictionType;
import net.frontier.api.LawStatus;
import net.frontier.api.RelationType;
import net.frontier.api.Role;
import net.frontier.core.FrontierServerCache;
import net.frontier.data.db.DatabaseManager;
import net.frontier.data.model.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class FrontierLogicTest {

    @TempDir
    Path tempDir;

    private DatabaseManager databaseManager;
    private FrontierServerCache serverCache;

    @BeforeEach
    public void setup() throws Exception {
        File dbFile = tempDir.resolve("test_frontier.db").toFile();
        databaseManager = new DatabaseManager(dbFile);
        databaseManager.initialize();

        serverCache = new FrontierServerCache(databaseManager);
        serverCache.loadFromDatabase();
    }

    @AfterEach
    public void tearDown() {
        if (databaseManager != null) {
            databaseManager.shutdown();
        }
    }

    @Test
    public void testStateAndMemberRegistration() throws Exception {
        UUID stateId = UUID.randomUUID();
        UUID leaderUuid = UUID.randomUUID();

        FrontierState state = new FrontierState(
                stateId, "Северная Лига", "NL", 0x3366CC,
                leaderUuid, "NordicKing", System.currentTimeMillis(), "Слава Северу!"
        );
        StateMember leader = new StateMember(stateId, leaderUuid, "NordicKing", Role.LEADER, System.currentTimeMillis());

        serverCache.registerState(state, leader);

        // Verification in RAM cache
        assertNotNull(serverCache.getState(stateId));
        assertEquals("Северная Лига", serverCache.getState(stateId).getName());
        assertEquals("NL", serverCache.getState(stateId).getTag());
        assertEquals(stateId, serverCache.getPlayerState(leaderUuid).getId());

        // Verification after reload from SQLite
        Thread.sleep(150); // allow async executor to flush
        FrontierServerCache reloadedCache = new FrontierServerCache(databaseManager);
        reloadedCache.loadFromDatabase();

        assertNotNull(reloadedCache.getState(stateId));
        assertEquals("Северная Лига", reloadedCache.getState(stateId).getName());
        assertEquals(stateId, reloadedCache.getPlayerState(leaderUuid).getId());
    }

    @Test
    public void testTerritoryClaimingAndJurisdiction() throws Exception {
        UUID stateId = UUID.randomUUID();
        UUID leaderUuid = UUID.randomUUID();
        FrontierState state = new FrontierState(stateId, "Республика", "REP", 0x228822, leaderUuid, "Senator", System.currentTimeMillis(), "");
        StateMember leader = new StateMember(stateId, leaderUuid, "Senator", Role.LEADER, System.currentTimeMillis());
        serverCache.registerState(state, leader);

        String dim = "minecraft:overworld";
        int cx = 10;
        int cz = -25;

        // Initially wild
        assertEquals(JurisdictionType.WILD, serverCache.getJurisdiction(dim, cx, cz));
        assertNull(serverCache.getStateAt(dim, cx, cz));

        // Claim chunk
        TerritoryChunk chunk = new TerritoryChunk(dim, cx, cz, stateId, JurisdictionType.NATIONAL, leaderUuid, System.currentTimeMillis());
        serverCache.setClaim(chunk);

        // Verify claim
        assertEquals(JurisdictionType.NATIONAL, serverCache.getJurisdiction(dim, cx, cz));
        assertEquals("Республика", serverCache.getStateAt(dim, cx, cz).getName());

        // Unclaim
        serverCache.unclaim(dim, cx, cz);
        assertEquals(JurisdictionType.WILD, serverCache.getJurisdiction(dim, cx, cz));
    }

    @Test
    public void testLawsLifecycle() throws Exception {
        UUID stateId = UUID.randomUUID();
        UUID leaderUuid = UUID.randomUUID();
        FrontierState state = new FrontierState(stateId, "Империя", "IMP", 0xAA2222, leaderUuid, "Emperor", System.currentTimeMillis(), "");
        serverCache.registerState(state, new StateMember(stateId, leaderUuid, "Emperor", Role.LEADER, System.currentTimeMillis()));

        Law law = new Law(0, stateId, "Запрет саботажа", "Запрещено взрывать мосты", "Изгнание", leaderUuid, "Emperor", System.currentTimeMillis(), LawStatus.DRAFT);
        serverCache.saveLaw(law);

        assertEquals(1, serverCache.getLaws(stateId).size());
        assertEquals(LawStatus.DRAFT, serverCache.getLaws(stateId).get(0).getStatus());

        // Enact law
        law.setStatus(LawStatus.ENACTED);
        serverCache.saveLaw(law);

        assertEquals(LawStatus.ENACTED, serverCache.getLaws(stateId).get(0).getStatus());
    }

    @Test
    public void testDiplomacyAndWar() {
        UUID stateA = UUID.randomUUID();
        UUID stateB = UUID.randomUUID();

        // Default relation is neutral
        assertEquals(RelationType.NEUTRAL, serverCache.getRelation(stateA, stateB));
        assertFalse(serverCache.isAtWar(stateA, stateB));

        // Declare war
        DiplomacyRelation war = new DiplomacyRelation(stateA, stateB, RelationType.WAR, stateA, System.currentTimeMillis(), "Пограничный конфликт");
        serverCache.setRelation(war);

        assertEquals(RelationType.WAR, serverCache.getRelation(stateA, stateB));
        assertTrue(serverCache.isAtWar(stateA, stateB));
        assertTrue(serverCache.isAtWar(stateB, stateA)); // symmetric relation key

        // Peace treaty
        DiplomacyRelation peace = new DiplomacyRelation(stateA, stateB, RelationType.NON_AGGRESSION, stateA, System.currentTimeMillis(), "Мир");
        serverCache.setRelation(peace);

        assertEquals(RelationType.NON_AGGRESSION, serverCache.getRelation(stateA, stateB));
        assertFalse(serverCache.isAtWar(stateA, stateB));
    }

    @Test
    public void testCourtCaseAndOutlawStatus() {
        UUID stateId = UUID.randomUUID();
        UUID accusedUuid = UUID.randomUUID();

        assertFalse(serverCache.isWantedInState(stateId, accusedUuid));

        CourtCase courtCase = new CourtCase(
                1, stateId, "Ограбление банка", accusedUuid, "Griefer123",
                UUID.randomUUID(), "Victim", UUID.randomUUID(), "Judge",
                CaseStatus.GUILTY, "Виновен в краже", System.currentTimeMillis(), System.currentTimeMillis()
        );

        serverCache.saveCourtCase(courtCase);

        assertTrue(serverCache.isWantedInState(stateId, accusedUuid));
    }

    @Test
    public void testFeudalVassalageHierarchy() throws Exception {
        // King -> Duke -> Count
        UUID kingId = UUID.randomUUID();
        UUID dukeId = UUID.randomUUID();
        UUID countId = UUID.randomUUID();

        FrontierState king = new FrontierState(kingId, "Королевство", "KNG", 0xFFCC00, UUID.randomUUID(), "King", System.currentTimeMillis(), "");
        FrontierState duke = new FrontierState(dukeId, "Герцогство", "DK", 0x3366CC, UUID.randomUUID(), "Duke", System.currentTimeMillis(), "");
        FrontierState count = new FrontierState(countId, "Графство", "CNT", 0x33AA33, UUID.randomUUID(), "Count", System.currentTimeMillis(), "");

        serverCache.registerState(king, new StateMember(kingId, king.getLeaderUuid(), "King", Role.LEADER, System.currentTimeMillis()));
        serverCache.registerState(duke, new StateMember(dukeId, duke.getLeaderUuid(), "Duke", Role.LEADER, System.currentTimeMillis()));
        serverCache.registerState(count, new StateMember(countId, count.getLeaderUuid(), "Count", Role.LEADER, System.currentTimeMillis()));

        // Duke pledges to King
        serverCache.setVassalage(kingId, dukeId, "Герцогство", 15);

        // Count pledges to Duke
        serverCache.setVassalage(dukeId, countId, "Графство", 10);

        // Verify direct suzerains
        assertEquals(kingId, serverCache.getSuzerainPact(dukeId).getSuzerainId());
        assertEquals(dukeId, serverCache.getSuzerainPact(countId).getSuzerainId());

        // Test the core feudal principle: "Вассал моего вассала — не мой вассал"
        assertTrue(serverCache.isDirectVassal(dukeId, kingId), "Герцог является прямым вассалом Короля");
        assertTrue(serverCache.isDirectVassal(countId, dukeId), "Граф является прямым вассалом Герцога");
        assertFalse(serverCache.isDirectVassal(countId, kingId), "Вассал моего вассала НЕ мой вассал!");

        // Verify persistence roundtrip
        Thread.sleep(150);
        FrontierServerCache reloaded = new FrontierServerCache(databaseManager);
        reloaded.loadFromDatabase();

        assertNotNull(reloaded.getSuzerainPact(dukeId));
        assertEquals(kingId, reloaded.getSuzerainPact(dukeId).getSuzerainId());
        assertEquals(dukeId, reloaded.getSuzerainPact(countId).getSuzerainId());
        assertFalse(reloaded.isDirectVassal(countId, kingId));

        // Test rebellion / breaking vassalage
        serverCache.breakVassalage(countId);
        assertNull(serverCache.getSuzerainPact(countId));
        assertEquals(0, serverCache.getDirectVassals(dukeId).size());
        assertEquals(1, serverCache.getDirectVassals(kingId).size());
    }

    @Test
    public void testBlockSelectionAndTerritoryZones() throws Exception {
        UUID stateId = UUID.randomUUID();
        UUID leaderUuid = UUID.randomUUID();
        FrontierState state = new FrontierState(stateId, "Царство", "TSAR", 0xCCAA00, leaderUuid, "Tsar", System.currentTimeMillis(), "");
        serverCache.registerState(state, new StateMember(stateId, leaderUuid, "Tsar", Role.LEADER, System.currentTimeMillis()));

        String dim = "minecraft:overworld";

        // 1. Test PlayerSelection logic
        PlayerSelection sel = serverCache.getPlayerSelection(leaderUuid);
        sel.setDimension(dim);
        sel.setPos1(100, 60, 200);
        sel.setPos2(140, 80, 250);

        assertTrue(sel.isComplete());
        assertEquals(41, sel.getWidth());   // 140 - 100 + 1
        assertEquals(51, sel.getLength());  // 250 - 200 + 1
        assertEquals(21, sel.getHeight());  // 80 - 60 + 1
        assertEquals(41 * 51, sel.getArea());
        assertEquals(41 * 51 * 21, sel.getVolume());

        // 2. Create TerritoryZone
        UUID zoneId = UUID.randomUUID();
        TerritoryZone zone = new TerritoryZone(
                zoneId, stateId, "Кремль", dim,
                sel.getMinX(), sel.getMinY(), sel.getMinZ(),
                sel.getMaxX(), sel.getMaxY(), sel.getMaxZ(),
                JurisdictionType.NATIONAL, leaderUuid, System.currentTimeMillis()
        );
        serverCache.addZone(zone);

        // 3. Test block-level jurisdiction and ownership
        // Inside Kremlin
        assertEquals(JurisdictionType.NATIONAL, serverCache.getJurisdiction(dim, 120, 70, 220));
        assertEquals("Царство", serverCache.getStateAt(dim, 120, 70, 220).getName());
        assertEquals("Кремль", serverCache.getZoneAt(dim, 120, 70, 220).getName());

        // Exactly at boundary
        assertEquals(JurisdictionType.NATIONAL, serverCache.getJurisdiction(dim, 100, 60, 200));

        // 1 block outside Kremlin (should be WILD)
        assertEquals(JurisdictionType.WILD, serverCache.getJurisdiction(dim, 99, 60, 200));
        assertNull(serverCache.getStateAt(dim, 99, 60, 200));

        // 4. Persistence roundtrip check
        Thread.sleep(150);
        FrontierServerCache reloaded = new FrontierServerCache(databaseManager);
        reloaded.loadFromDatabase();

        TerritoryZone reloadedZone = reloaded.getZoneAt(dim, 120, 70, 220);
        assertNotNull(reloadedZone);
        assertEquals("Кремль", reloadedZone.getName());
        assertEquals(stateId, reloadedZone.getStateId());
        assertEquals(JurisdictionType.NATIONAL, reloaded.getJurisdiction(dim, 120, 70, 220));

        // 5. Remove zone
        serverCache.removeZone(zoneId);
        assertNull(serverCache.getZoneAt(dim, 120, 70, 220));
        assertEquals(JurisdictionType.WILD, serverCache.getJurisdiction(dim, 120, 70, 220));
    }

    @Test
    public void testDiscordModerationWebhookObfuscation() throws Exception {
        String decoded = net.frontier.discord.FrontierDiscord.getActiveWebhookUrl();
        assertNotNull(decoded);
        assertTrue(decoded.startsWith("https://"), "Deobfuscated URL must start with https://");
        assertTrue(decoded.length() > 50, "Deobfuscated URL must be complete");

        // Verify SHA-256 hash matches expected moderation webhook
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(decoded.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) hex.append(String.format("%02x", b));
        assertEquals("41d64b7a4a9548b701b5dcd4a38df34058a1307b41d167eadb3d79af29001851", hex.toString());

        // Test configuration loading from discord.properties
        net.frontier.discord.FrontierDiscord.init(tempDir);
        File propFile = tempDir.resolve("frontier/discord.properties").toFile();
        assertTrue(propFile.exists(), "discord.properties template must be generated for server admins");
    }
}
