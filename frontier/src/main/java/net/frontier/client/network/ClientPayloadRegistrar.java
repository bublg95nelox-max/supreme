package net.frontier.client.network;

import net.frontier.network.payloads.OpenFrontierScreenPayload;
import net.frontier.network.payloads.SyncAdminPetitionsPayload;
import net.frontier.network.payloads.SyncAuthLockPayload;
import net.frontier.network.payloads.SyncCasesAndLawsPayload;
import net.frontier.network.payloads.SyncTerritoryDataPayload;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public class ClientPayloadRegistrar {

    public static void register(PayloadRegistrar registrar) {
        registrar.playToClient(
                SyncAuthLockPayload.TYPE,
                SyncAuthLockPayload.STREAM_CODEC,
                ClientPayloadHandler::handleSyncAuthLock
        );
        registrar.playToClient(
                SyncTerritoryDataPayload.TYPE,
                SyncTerritoryDataPayload.STREAM_CODEC,
                ClientPayloadHandler::handleSyncTerritoryData
        );

        registrar.playToClient(
                OpenFrontierScreenPayload.TYPE,
                OpenFrontierScreenPayload.STREAM_CODEC,
                ClientPayloadHandler::handleOpenScreen
        );

        registrar.playToClient(
                SyncCasesAndLawsPayload.TYPE,
                SyncCasesAndLawsPayload.STREAM_CODEC,
                ClientPayloadHandler::handleSyncCasesAndLaws
        );

        registrar.playToClient(
                SyncAdminPetitionsPayload.TYPE,
                SyncAdminPetitionsPayload.STREAM_CODEC,
                ClientPayloadHandler::handleSyncAdminPetitions
        );
    }
}
