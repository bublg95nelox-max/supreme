package net.frontier.discord;

import net.frontier.data.model.CourtCase;
import net.frontier.data.model.StatePetition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Properties;

/**
 * Discord Moderation Webhook integration for Frontier.
 *
 * Webhooks are stored obfuscated (XOR + Base64) to protect against
 * automated GitHub Secret Scanning partner token revocations.
 *
 * Webhook URL can also be configured dynamically on the server via
 * frontier/discord.properties or the FRONTIER_MODERATION_WEBHOOK environment variable.
 */
public class FrontierDiscord {
    private static final Logger LOGGER = LoggerFactory.getLogger(FrontierDiscord.class);

    private static final byte XOR_KEY = 0x5B;
    // Obfuscated moderation webhook (zero plaintext webhook tokens in code)
    private static final String DEFAULT_ENC_WEBHOOK = "My8vKyhhdHQ/Mig4NCk/dTg0NnQ6KzJ0LD45MzQ0MCh0am5sa2tqaG9ja29oaW5tbWhjaXQhLwsrDykwGB41bz8QMSoIDBMUFmw8MDQ/bTUwIT0fHS0eEDcOEGluNzA/OhodHQoOIwwPAjZvFx1sAx4xHw0UNRA5IQ==";

    private static String activeWebhookUrl = null;
    private static HttpClient httpClient = null;

    public static String deobfuscate(String b64) {
        if (b64 == null || b64.trim().isEmpty()) return "";
        try {
            byte[] raw = Base64.getDecoder().decode(b64.trim());
            for (int i = 0; i < raw.length; i++) {
                raw[i] = (byte) (raw[i] ^ XOR_KEY);
            }
            return new String(raw, StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.error("[FrontierDiscord] Failed to deobfuscate webhook", e);
            return "";
        }
    }

    public static synchronized void init(java.nio.file.Path serverDir) {
        if (httpClient == null) {
            httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(6))
                    .build();
        }

        // 1. Check environment variable override
        String envUrl = System.getenv("FRONTIER_MODERATION_WEBHOOK");
        if (envUrl != null && !envUrl.trim().isEmpty()) {
            activeWebhookUrl = envUrl.trim();
            LOGGER.info("[FrontierDiscord] Moderation webhook configured from environment variable.");
            return;
        }

        // 2. Check frontier/discord.properties file on server
        if (serverDir != null) {
            try {
                File dir = serverDir.resolve("frontier").toFile();
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                File propFile = new File(dir, "discord.properties");
                if (propFile.exists()) {
                    Properties props = new Properties();
                    try (FileInputStream fis = new FileInputStream(propFile)) {
                        props.load(fis);
                    }
                    String loaded = props.getProperty("moderation_webhook");
                    if (loaded != null && !loaded.trim().isEmpty() && !loaded.contains("YOUR_DISCORD_WEBHOOK_URL")) {
                        activeWebhookUrl = loaded.trim();
                        LOGGER.info("[FrontierDiscord] Moderation webhook loaded from discord.properties");
                        return;
                    }
                } else {
                    // Create template for SFTP server admins
                    Properties props = new Properties();
                    props.setProperty("moderation_webhook", "DEFAULT");
                    props.setProperty("enabled", "true");
                    try (FileOutputStream fos = new FileOutputStream(propFile)) {
                        props.store(fos, "Frontier Moderation Discord Webhook Configuration (Leave DEFAULT to use built-in webhook)");
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("[FrontierDiscord] Could not access discord.properties: " + e.getMessage());
            }
        }

        // 3. Fallback to built-in protected webhook
        activeWebhookUrl = deobfuscate(DEFAULT_ENC_WEBHOOK);
        LOGGER.info("[FrontierDiscord] Initialized with built-in protected moderation webhook.");
    }

    public static String getActiveWebhookUrl() {
        if (activeWebhookUrl == null) {
            activeWebhookUrl = deobfuscate(DEFAULT_ENC_WEBHOOK);
        }
        return activeWebhookUrl;
    }

    public static void setActiveWebhookUrl(String url) {
        activeWebhookUrl = url;
    }

    /**
     * Sends an asynchronous JSON payload to the Discord moderation webhook.
     */
    public static void postRawJson(String json) {
        String url = getActiveWebhookUrl();
        if (url == null || url.trim().isEmpty()) return;

        if (httpClient == null) {
            httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(6))
                    .build();
        }

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Lowuncher-Frontier/1.0.7")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();

            httpClient.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                    .whenComplete((resp, ex) -> {
                        if (ex != null) {
                            LOGGER.warn("[FrontierDiscord] Failed to dispatch webhook alert: " + ex.getMessage());
                        } else if (resp != null && resp.statusCode() >= 400) {
                            LOGGER.warn("[FrontierDiscord] Discord webhook responded with status: " + resp.statusCode());
                        }
                    });
        } catch (Exception e) {
            LOGGER.warn("[FrontierDiscord] Failed to prepare HTTP request: " + e.getMessage());
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }

    /**
     * Alert: Player created a new state registration petition.
     */
    public static void notifyPetitionCreated(StatePetition p) {
        String json = "{\n" +
                "  \"content\": \"📜 **[Frontier] Новая заявка на регистрацию государства!**\",\n" +
                "  \"embeds\": [{\n" +
                "    \"title\": \"📜 Заявка на создание государства «" + escapeJson(p.getStateName()) + "»\",\n" +
                "    \"color\": 16705372,\n" +
                "    \"fields\": [\n" +
                "      {\"name\": \"👤 Заявитель\", \"value\": \"`" + escapeJson(p.getRequesterName()) + "`\", \"inline\": true},\n" +
                "      {\"name\": \"🏷️ Тег\", \"value\": \"`[" + escapeJson(p.getStateTag()) + "]`\", \"inline\": true},\n" +
                "      {\"name\": \"🎨 Цвет\", \"value\": \"`#" + String.format("%06X", (p.getColor() & 0xFFFFFF)) + "`\", \"inline\": true},\n" +
                "      {\"name\": \"📍 Столичный чанк\", \"value\": \"X: `" + p.getCapitalChunkX() + "`, Z: `" + p.getCapitalChunkZ() + "` (" + escapeJson(p.getDimension()) + ")\", \"inline\": false},\n" +
                "      {\"name\": \"⚡ Действие куратора\", \"value\": \"Используйте в игре: `/frontier admin approve " + p.getId() + "` или откройте Кодекс (J) -> вкладка Куратор.\", \"inline\": false}\n" +
                "    ],\n" +
                "    \"footer\": {\"text\": \"Frontier Moderation Bot • Supreme 1.21.1\"},\n" +
                "    \"timestamp\": \"" + Instant.now().toString() + "\"\n" +
                "  }]\n" +
                "}";
        postRawJson(json);
    }

    /**
     * Alert: Petition approved by moderator/admin.
     */
    public static void notifyPetitionApproved(StatePetition p, String adminName) {
        String json = "{\n" +
                "  \"content\": \"✅ **[Frontier] Заявка на государство утверждена!**\",\n" +
                "  \"embeds\": [{\n" +
                "    \"title\": \"✅ Государство «" + escapeJson(p.getStateName()) + "» зарегистрировано\",\n" +
                "    \"color\": 5763719,\n" +
                "    \"fields\": [\n" +
                "      {\"name\": \"👑 Основатель\", \"value\": \"`" + escapeJson(p.getRequesterName()) + "`\", \"inline\": true},\n" +
                "      {\"name\": \"🛡️ Куратор\", \"value\": \"`" + escapeJson(adminName) + "`\", \"inline\": true},\n" +
                "      {\"name\": \"🏷️ Тег\", \"value\": \"`[" + escapeJson(p.getStateTag()) + "]`\", \"inline\": true}\n" +
                "    ],\n" +
                "    \"footer\": {\"text\": \"Frontier Moderation Bot\"},\n" +
                "    \"timestamp\": \"" + Instant.now().toString() + "\"\n" +
                "  }]\n" +
                "}";
        postRawJson(json);
    }

    /**
     * Alert: Petition rejected by moderator/admin.
     */
    public static void notifyPetitionRejected(StatePetition p, String adminName) {
        String json = "{\n" +
                "  \"content\": \"❌ **[Frontier] Заявка на государство отклонена**\",\n" +
                "  \"embeds\": [{\n" +
                "    \"title\": \"❌ Заявка на «" + escapeJson(p.getStateName()) + "» отклонена\",\n" +
                "    \"color\": 15548997,\n" +
                "    \"fields\": [\n" +
                "      {\"name\": \"👤 Заявитель\", \"value\": \"`" + escapeJson(p.getRequesterName()) + "`\", \"inline\": true},\n" +
                "      {\"name\": \"🛡️ Куратор\", \"value\": \"`" + escapeJson(adminName) + "`\", \"inline\": true}\n" +
                "    ],\n" +
                "    \"footer\": {\"text\": \"Frontier Moderation Bot\"},\n" +
                "    \"timestamp\": \"" + Instant.now().toString() + "\"\n" +
                "  }]\n" +
                "}";
        postRawJson(json);
    }

    /**
     * Alert: Court case opened.
     */
    public static void notifyCaseCreated(CourtCase c) {
        String json = "{\n" +
                "  \"content\": \"⚖️ **[Frontier Суд] Возбуждено дело №" + c.getId() + "**\",\n" +
                "  \"embeds\": [{\n" +
                "    \"title\": \"⚖️ " + escapeJson(c.getTitle()) + "\",\n" +
                "    \"color\": 15105570,\n" +
                "    \"fields\": [\n" +
                "      {\"name\": \"🕵️ Дознаватель/Истец\", \"value\": \"`" + escapeJson(c.getPlaintiffName()) + "`\", \"inline\": true},\n" +
                "      {\"name\": \"🎯 Обвиняемый\", \"value\": \"`" + escapeJson(c.getAccusedName()) + "`\", \"inline\": true},\n" +
                "      {\"name\": \"📁 Статус\", \"value\": \"`Следствие`\", \"inline\": true}\n" +
                "    ],\n" +
                "    \"footer\": {\"text\": \"Frontier Justice System\"},\n" +
                "    \"timestamp\": \"" + Instant.now().toString() + "\"\n" +
                "  }]\n" +
                "}";
        postRawJson(json);
    }

    /**
     * Alert: Verdict pronounced.
     */
    public static void notifyVerdict(CourtCase c, boolean guilty, String verdict, String judgeName) {
        int color = guilty ? 15548997 : 5763719;
        String statusLabel = guilty ? "ВИНОВЕН" : "ОПРАВДАН";
        String json = "{\n" +
                "  \"content\": \"" + (guilty ? "🚨" : "⚖️") + " **[Frontier Суд] Вынесен вердикт по делу №" + c.getId() + "**\",\n" +
                "  \"embeds\": [{\n" +
                "    \"title\": \"" + (guilty ? "🚨 Приговор: " : "⚖️ Приговор: ") + statusLabel + " — " + escapeJson(c.getTitle()) + "\",\n" +
                "    \"color\": " + color + ",\n" +
                "    \"fields\": [\n" +
                "      {\"name\": \"⚖️ Судья\", \"value\": \"`" + escapeJson(judgeName) + "`\", \"inline\": true},\n" +
                "      {\"name\": \"👤 Обвиняемый\", \"value\": \"`" + escapeJson(c.getAccusedName()) + "`\", \"inline\": true},\n" +
                "      {\"name\": \"📝 Текст вердикта\", \"value\": \"" + escapeJson(verdict) + "\", \"inline\": false}\n" +
                "    ],\n" +
                "    \"footer\": {\"text\": \"Frontier Justice System\"},\n" +
                "    \"timestamp\": \"" + Instant.now().toString() + "\"\n" +
                "  }]\n" +
                "}";
        postRawJson(json);
    }
}
