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
    private static int tickCounter = 0;

    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN_GUI_KEY);
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        if (++tickCounter % 20 == 0) {
            net.frontier.client.compat.XaeroMapCompat.checkAndRegisterHighlighter();
        }

        while (OPEN_GUI_KEY.consumeClick()) {
            if (mc.screen == null) {
                mc.setScreen(new FrontierScreen(0));
            }
        }

        // Territory HUD Notification
        int currentCx = mc.player.blockPosition().getX() >> 4;
        int currentCz = mc.player.blockPosition().getZ() >> 4;

        if (currentCx != lastChunkX || currentCz != lastChunkZ) {
            lastChunkX = currentCx;
            lastChunkZ = currentCz;

            FrontierClientCache cache = FrontierClientCache.get();
            SyncTerritoryDataPayload.ClientClaimData claim = cache.getClaim(currentCx, currentCz);

            if (claim != null && claim.stateId() != null) {
                SyncTerritoryDataPayload.ClientStateData st = cache.getState(claim.stateId());
                String name = st != null ? st.name() : "Государство";
                String tag = st != null ? (" [" + st.tag() + "]") : "";

                SyncTerritoryDataPayload.ClientVassalData vPact = cache.getSuzerainPact(claim.stateId());
                String suzInfo = "";
                if (vPact != null) {
                    SyncTerritoryDataPayload.ClientStateData suz = cache.getState(vPact.suzerainId());
                    if (suz != null) {
                        suzInfo = " §8[Вассал: §e" + suz.name() + "§8]";
                    }
                }

                mc.gui.setOverlayMessage(Component.literal("§6§l" + name + tag + suzInfo + " §7(Юрисдикция: §a" + claim.jurisdictionType().getLabel() + "§7)"), false);
            } else if (claim != null && claim.jurisdictionType() == JurisdictionType.NEUTRAL) {
                mc.gui.setOverlayMessage(Component.literal("§b§lНейтральная территория §7(Общая зона)"), false);
            }
        }
    }
}
