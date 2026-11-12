package net.frontier.client.network;

import net.frontier.client.FrontierClientCache;
import net.frontier.client.gui.FrontierScreen;
import net.frontier.client.FrontierClientEvents;
import net.frontier.network.payloads.OpenFrontierScreenPayload;
import net.frontier.network.payloads.SyncAuthLockPayload;
import net.frontier.network.payloads.SyncCasesAndLawsPayload;
import net.frontier.network.payloads.SyncTerritoryDataPayload;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public class ClientPayloadHandler {

    public static void handleSyncTerritoryData(SyncTerritoryDataPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            FrontierClientCache.get().updateTerritoryData(payload);
        });
    }

    public static void handleSyncCasesAndLaws(SyncCasesAndLawsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            FrontierClientCache.get().updateCasesAndLaws(payload);
        });
    }

    public static void handleOpenScreen(OpenFrontierScreenPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft.getInstance().setScreen(new FrontierScreen(payload.defaultTab()));
        });
    }

    public static void handleSyncAdminPetitions(net.frontier.network.payloads.SyncAdminPetitionsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            FrontierClientCache.get().updateAdminPetitions(payload);
        });
    }

    public static void handleSyncAuthLock(SyncAuthLockPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            FrontierClientEvents.setAuthLocked(payload.locked());
        });
    }
}
