package net.frontier.client.compat;

import net.frontier.client.compat.xaero.XaeroMapImpl;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class XaeroMapCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger(XaeroMapCompat.class);
    private static boolean isXaeroInstalled = false;

    public static void init() {
        if (ModList.get().isLoaded("xaeroworldmap")) {
            isXaeroInstalled = true;
            LOGGER.info("[Frontier] Xaero's World Map detected! Territory overlay and border highlighting enabled.");
        } else {
            LOGGER.info("[Frontier] Xaero's World Map not detected. Using built-in interactive Frontier codex.");
        }
    }

    public static boolean isXaeroLoaded() {
        return isXaeroInstalled;
    }

    public static void checkAndRegisterHighlighter() {
        if (!isXaeroInstalled) return;
        try {
            XaeroMapImpl.checkAndRegisterHighlighter();
        } catch (Throwable t) {
            LOGGER.warn("[Frontier] Failed checking Xaero highlighter: {}", t.getMessage());
        }
    }

    public static void refreshXaeroHighlights() {
        if (!isXaeroInstalled) return;
        try {
            XaeroMapImpl.refreshXaeroHighlights();
        } catch (Throwable t) {
            LOGGER.warn("[Frontier] Failed refreshing Xaero highlights: {}", t.getMessage());
        }
    }

    public static void openXaeroWorldMap(Screen parent) {
        if (!isXaeroInstalled) return;
        try {
            XaeroMapImpl.openXaeroWorldMap(parent);
        } catch (Throwable t) {
            LOGGER.error("[Frontier] Failed opening Xaero World Map: {}", t.getMessage());
        }
    }
}
