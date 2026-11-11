package net.frontier.client.compat.xaero;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xaero.map.MapProcessor;
import xaero.map.WorldMapSession;
import xaero.map.gui.GuiMap;
import xaero.map.highlight.AbstractHighlighter;
import xaero.map.highlight.HighlighterRegistry;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class XaeroMapImpl {
    private static final Logger LOGGER = LoggerFactory.getLogger(XaeroMapImpl.class);
    private static FrontierClaimsHighlighter highlighterInstance = null;

    public static void checkAndRegisterHighlighter() {
        try {
            WorldMapSession session = WorldMapSession.getCurrentSession();
            if (session == null || session.getMapProcessor() == null) {
                return;
            }
            HighlighterRegistry reg = session.getMapProcessor().getHighlighterRegistry();
            if (reg == null) {
                return;
            }

            List<AbstractHighlighter> current = reg.getHighlighters();
            if (current != null) {
                for (AbstractHighlighter h : current) {
                    if (h instanceof FrontierClaimsHighlighter) {
                        return; // Already registered
                    }
                }
            }

            if (highlighterInstance == null) {
                highlighterInstance = new FrontierClaimsHighlighter();
            }

            try {
                reg.register(highlighterInstance);
                LOGGER.info("[Frontier] Registered FrontierClaimsHighlighter in Xaero World Map!");
            } catch (UnsupportedOperationException unmod) {
                // HighlighterRegistry list was locked with Collections.unmodifiableList
                Field f = HighlighterRegistry.class.getDeclaredField("highlighters");
                f.setAccessible(true);
                @SuppressWarnings("unchecked")
                List<AbstractHighlighter> list = (List<AbstractHighlighter>) f.get(reg);
                List<AbstractHighlighter> modifiable = new ArrayList<>(list);
                modifiable.add(highlighterInstance);
                f.set(reg, Collections.unmodifiableList(modifiable));
                LOGGER.info("[Frontier] Injected FrontierClaimsHighlighter via reflection into HighlighterRegistry!");
            }
        } catch (Throwable t) {
            LOGGER.debug("[Frontier] Xaero highlighter check skipped: {}", t.getMessage());
        }
    }

    public static void refreshXaeroHighlights() {
        try {
            WorldMapSession session = WorldMapSession.getCurrentSession();
            if (session != null && session.getMapProcessor() != null && session.getMapProcessor().getMapWorld() != null) {
                session.getMapProcessor().getMapWorld().clearAllCachedHighlightHashes();
            }
        } catch (Throwable t) {
            LOGGER.debug("[Frontier] Could not refresh Xaero highlights: {}", t.getMessage());
        }
    }

    public static void openXaeroWorldMap(Screen parent) {
        try {
            WorldMapSession session = WorldMapSession.getCurrentSession();
            Minecraft mc = Minecraft.getInstance();
            if (session != null && session.getMapProcessor() != null && mc.player != null) {
                mc.setScreen(new GuiMap(parent, parent, session.getMapProcessor(), mc.player));
            } else {
                LOGGER.warn("[Frontier] Cannot open Xaero World Map: session or player is null");
            }
        } catch (Throwable t) {
            LOGGER.error("[Frontier] Error opening Xaero World Map screen", t);
        }
    }
}
