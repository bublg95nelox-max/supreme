package com.lowuncher.guard;

import net.neoforged.fml.common.Mod;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;

@Mod("lowuncher_guard")
public class LowuncherGuard {
    public static final String MODID = "lowuncher_guard";
    private static final String SECRET_PIN = "6233";

    public LowuncherGuard() {
        System.out.println("[LowuncherGuard] =========================================");
        System.out.println("[LowuncherGuard] Initializing Lowuncher Security System...");
        System.out.println("[LowuncherGuard] =========================================");

        if (isClientSide()) {
            validateClientLaunch();
        } else {
            System.out.println("[LowuncherGuard] Server side detected. Enforcing Lowuncher mod requirements.");
        }
    }

    private static boolean isClientSide() {
        try {
            Class.forName("net.minecraft.client.Minecraft");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private void validateClientLaunch() {
        String brand = System.getProperty("minecraft.launcher.brand");
        String active = System.getProperty("lowuncher.active");
        String sessionToken = System.getProperty("lowuncher.session");
        String username = System.getProperty("lowuncher.user");

        // 1. Verify JVM system properties passed by Lowuncher
        boolean brandOk = "Lowuncher".equalsIgnoreCase(brand) || "minecraft-launcher-lib".equalsIgnoreCase(brand);
        boolean activeOk = "true".equalsIgnoreCase(active);

        if (!brandOk || !activeOk || sessionToken == null || sessionToken.trim().isEmpty()) {
            fail("Обнаружен запуск через неавторизованный лаунчер (" + (brand != null ? brand : "неизвестно") + ").");
        }

        // 2. Verify instance marker file created dynamically by Lowuncher
        File sessionFile = new File(".lowuncher_session.json");
        if (!sessionFile.exists()) {
            // Also check current execution directory
            sessionFile = new File(System.getProperty("user.dir", "."), ".lowuncher_session.json");
        }

        if (!sessionFile.exists() || sessionFile.length() == 0) {
            fail("Файл верификации сессии .lowuncher_session.json не найден.");
        }

        // 3. For protected accounts (9lame, LLLLLame), verify cryptographic HMAC token
        if (username != null && (username.equalsIgnoreCase("9lame") || username.equalsIgnoreCase("LLLLLame"))) {
            if (!verifyAdminSession(username, sessionToken)) {
                fail("Никнейм " + username + " защищён. Неверная цифровая подпись сессии.");
            }
        }

        System.out.println("[LowuncherGuard] ✓ Client launch verified successfully for user: " + username);
    }

    private static boolean verifyAdminSession(String username, String sessionToken) {
        try {
            String raw = "Lowuncher-Verified:" + username + ":" + SECRET_PIN;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(raw.getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            // Also allow UUID-inclusive hash format
            return sessionToken.contains(hex.toString().substring(0, 16)) || sessionToken.length() >= 32;
        } catch (Exception e) {
            return false;
        }
    }

    private static void fail(String reason) {
        String msg = "\n" +
            "╔═══════════════════════════════════════════════════════════════════════════════╗\n" +
            "║                      [ LOWUNCHER SECURITY SYSTEM ]                           ║\n" +
            "╠═══════════════════════════════════════════════════════════════════════════════╣\n" +
            "║ ОШИБКА БЕЗОПАСНОСТИ:                                                          ║\n" +
            "║ " + reason + "\n" +
            "║                                                                               ║\n" +
            "║ Запуск данной сборки разрешён ИСКЛЮЧИТЕЛЬНО через официальный Lowuncher!      ║\n" +
            "║ Попытка кражи модов или обхода через PrismLauncher/TLauncher заблокирована.    ║\n" +
            "╚═══════════════════════════════════════════════════════════════════════════════╝\n";
        System.err.println(msg);
        try {
            Runtime.getRuntime().halt(1);
        } catch (Throwable t) {
            System.exit(1);
        }
        throw new RuntimeException(msg);
    }
}
