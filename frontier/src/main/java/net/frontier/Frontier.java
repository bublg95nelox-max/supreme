package net.frontier;

import net.frontier.client.FrontierClientEvents;
import net.frontier.client.compat.XaeroMapCompat;
import net.frontier.commands.FrontierCommand;
import net.frontier.core.ForensicManager;
import net.frontier.core.FrontierServerCache;
import net.frontier.data.db.DatabaseManager;
import net.frontier.items.FrontierItems;
import net.frontier.network.FrontierNetwork;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

@Mod(Frontier.MODID)
public class Frontier {
    public static final String MODID = "frontier";
    private static final Logger LOGGER = LoggerFactory.getLogger(Frontier.class);

    private static DatabaseManager databaseManager;
    private static FrontierServerCache serverCache;
    private static ForensicManager forensicManager;

    public Frontier(IEventBus modEventBus) {
        LOGGER.info("[Frontier] Initializing Frontier Mod for NeoForge 1.21.1...");

        // Register items and tabs
        FrontierItems.register(modEventBus);

        // Register payloads
        modEventBus.addListener(FrontierNetwork::register);

        // Client specific hooks
        if (FMLEnvironment.dist.isClient()) {
            modEventBus.addListener(FrontierClientEvents::registerKeys);
            NeoForge.EVENT_BUS.addListener(FrontierClientEvents::onClientTick);
            XaeroMapCompat.init();
        }

        // Register game bus listeners
        NeoForge.EVENT_BUS.addListener(net.frontier.items.SurveyorToolItem::onLeftClickBlock);
        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLogin);
    }

    private void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("[Frontier] Server starting: initializing Frontier SQLite database and caches...");
        java.nio.file.Path serverDir = event.getServer().getServerDirectory();
        File dbFile = serverDir.resolve("frontier/frontier.db").toFile();

        try {
            net.frontier.discord.FrontierDiscord.init(serverDir);
            databaseManager = new DatabaseManager(dbFile);
            databaseManager.initialize();

            serverCache = new FrontierServerCache(databaseManager);
            serverCache.loadFromDatabase();

            forensicManager = new ForensicManager(serverCache);
            NeoForge.EVENT_BUS.register(forensicManager);

            LOGGER.info("[Frontier] Core engine and forensic event listeners online!");
        } catch (Exception e) {
            LOGGER.error("[Frontier] FATAL ERROR initializing Frontier database", e);
        }
    }

    private void onServerStopping(ServerStoppingEvent event) {
        LOGGER.info("[Frontier] Server stopping: cleanly flushing data...");
        if (forensicManager != null) {
            NeoForge.EVENT_BUS.unregister(forensicManager);
        }
        if (databaseManager != null) {
            databaseManager.shutdown();
        }
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        FrontierCommand.register(event.getDispatcher());
    }

    private void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            FrontierNetwork.syncPlayerData(player);
        }
    }

    public static FrontierServerCache getServerCache() {
        return serverCache;
    }

    public static DatabaseManager getDatabaseManager() {
        return databaseManager;
    }
}
