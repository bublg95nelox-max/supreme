package net.frontier.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.frontier.api.JurisdictionType;
import net.frontier.client.gui.FrontierScreen;
import net.frontier.network.payloads.SyncTerritoryDataPayload;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

public class FrontierClientEvents {

    public static final KeyMapping OPEN_GUI_KEY = new KeyMapping(
            "key.frontier.open_codex",
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_J,
            "key.categories.frontier"
    );

    private static int lastChunkX = Integer.MIN_VALUE;
    private static int lastChunkZ = Integer.MIN_VALUE;
    private static java.util.UUID lastStateId = null;
    private static boolean lastWasNeutral = false;
    private static String lastStateName = null;
    private static int tickCounter = 0;
    private static volatile boolean authLocked = false;

    public static void setAuthLocked(boolean locked) {
        authLocked = locked;
        if (locked) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getDebugOverlay() != null && mc.getDebugOverlay().showDebugScreen()) {
                mc.getDebugOverlay().reset();
            }
        }
    }

    public static boolean isAuthLocked() {
        return authLocked;
    }

    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN_GUI_KEY);
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            authLocked = false;
            lastChunkX = Integer.MIN_VALUE;
            lastChunkZ = Integer.MIN_VALUE;
            lastStateId = null;
            lastWasNeutral = false;
            lastStateName = null;
            return;
        }

        // Suppress F3 debug screen immediately if authentication is locked
        if (authLocked) {
            if (mc.getDebugOverlay() != null && mc.getDebugOverlay().showDebugScreen()) {
                mc.getDebugOverlay().reset();
            }
        }

        if (++tickCounter % 20 == 0) {
            net.frontier.client.compat.XaeroMapCompat.checkAndRegisterHighlighter();
        }

        while (OPEN_GUI_KEY.consumeClick()) {
            if (mc.screen == null) {
                mc.setScreen(new FrontierScreen(0));
            }
        }

        // Territory HUD Notification (Triggered only on entering and exiting territories)
        int currentCx = mc.player.blockPosition().getX() >> 4;
        int currentCz = mc.player.blockPosition().getZ() >> 4;

        if (currentCx != lastChunkX || currentCz != lastChunkZ) {
            lastChunkX = currentCx;
            lastChunkZ = currentCz;

            FrontierClientCache cache = FrontierClientCache.get();
            SyncTerritoryDataPayload.ClientClaimData claim = cache.getClaim(currentCx, currentCz);

            java.util.UUID currentStateId = (claim != null) ? claim.stateId() : null;
            boolean currentIsNeutral = (claim != null && (claim.jurisdictionType() == JurisdictionType.NEUTRAL
                    || (claim.stateId() == null && claim.jurisdictionType() != null && claim.jurisdictionType() != JurisdictionType.WILD)));

            boolean stateChanged = !java.util.Objects.equals(lastStateId, currentStateId);
            boolean neutralChanged = (lastWasNeutral != currentIsNeutral);

            if (stateChanged || neutralChanged) {
                if (currentStateId != null) {
                    SyncTerritoryDataPayload.ClientStateData st = cache.getState(currentStateId);
                    String name = st != null ? st.name() : "Государство";
                    String tag = (st != null && st.tag() != null && !st.tag().isEmpty()) ? (" [" + st.tag() + "]") : "";

                    SyncTerritoryDataPayload.ClientVassalData vPact = cache.getSuzerainPact(currentStateId);
                    String suzInfo = "";
                    if (vPact != null) {
                        SyncTerritoryDataPayload.ClientStateData suz = cache.getState(vPact.suzerainId());
                        if (suz != null) {
                            suzInfo = " §8[Вассал: §e" + suz.name() + "§8]";
                        }
                    }

                    String jurLabel = (claim != null && claim.jurisdictionType() != null) ? claim.jurisdictionType().getLabel() : "Территория государства";
                    mc.gui.setOverlayMessage(Component.literal("§6§l" + name + tag + suzInfo + " §7(Юрисдикция: §a" + jurLabel + "§7)"), false);
                    lastStateName = name + tag;
                } else if (currentIsNeutral) {
                    mc.gui.setOverlayMessage(Component.literal("§b§lНейтральная территория §7(Общая зона)"), false);
                    lastStateName = null;
                } else {
                    // Exited into wilderness
                    if (lastStateId != null) {
                        String exName = lastStateName != null ? lastStateName : "государства";
                        mc.gui.setOverlayMessage(Component.literal("§7Вы покинули территорию §6" + exName + " §8[Дикие земли]"), false);
                    } else if (lastWasNeutral) {
                        mc.gui.setOverlayMessage(Component.literal("§7Вы покинули нейтральную территорию §8[Дикие земли]"), false);
                    }
                    lastStateName = null;
                }

                lastStateId = currentStateId;
                lastWasNeutral = currentIsNeutral;
            }
        }
    }

    public static void onCustomizeDebugText(CustomizeGuiOverlayEvent.DebugText event) {
        if (authLocked) {
            event.getLeft().clear();
            event.getRight().clear();
        }
    }
}
