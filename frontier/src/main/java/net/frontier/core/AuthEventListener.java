package net.frontier.core;

import net.frontier.network.FrontierNetwork;
import net.frontier.network.payloads.SyncAuthLockPayload;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AuthEventListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(AuthEventListener.class);
    private static final Set<UUID> pendingAuthPlayers = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private static boolean vouchChecked = false;
    private static boolean vouchPresent = false;
    private static Object authManagerInstance = null;
    private static Method isPendingAuthMethod = null;

    private static void initVouchReflection() {
        if (vouchChecked) return;
        vouchChecked = true;
        try {
            if (ModList.get().isLoaded("vouch")) {
                Class<?> authManagerClass = Class.forName("com.nozz.vouch.auth.AuthManager");
                Method getInstanceMethod = authManagerClass.getMethod("getInstance");
                authManagerInstance = getInstanceMethod.invoke(null);
                isPendingAuthMethod = authManagerClass.getMethod("isPendingAuth", ServerPlayer.class);
                vouchPresent = true;
                LOGGER.info("[Frontier] Successfully hooked into Vouch authentication manager.");
            }
        } catch (Throwable t) {
            LOGGER.warn("[Frontier] Could not hook into Vouch: " + t.getMessage());
            vouchPresent = false;
        }
    }

    public static boolean isPlayerPendingAuth(ServerPlayer player) {
        initVouchReflection();
        if (vouchPresent && authManagerInstance != null && isPendingAuthMethod != null) {
            try {
                Object res = isPendingAuthMethod.invoke(authManagerInstance, player);
                if (res instanceof Boolean b) {
                    return b;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (isPlayerPendingAuth(player)) {
                pendingAuthPlayers.add(player.getUUID());
                player.connection.send(new ClientboundEntityEventPacket(player, (byte) 22)); // Reduced debug info ON
                FrontierNetwork.sendToPlayer(player, new SyncAuthLockPayload(true));
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            pendingAuthPlayers.remove(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            UUID uuid = player.getUUID();
            boolean wasPending = pendingAuthPlayers.contains(uuid);
            boolean isPending = isPlayerPendingAuth(player);

            if (isPending) {
                if (!wasPending) {
                    pendingAuthPlayers.add(uuid);
                    player.connection.send(new ClientboundEntityEventPacket(player, (byte) 22));
                    FrontierNetwork.sendToPlayer(player, new SyncAuthLockPayload(true));
                }
            } else if (wasPending) {
                // Player has just authenticated!
                pendingAuthPlayers.remove(uuid);
                player.connection.send(new ClientboundEntityEventPacket(player, (byte) 23)); // Reduced debug info OFF
                FrontierNetwork.sendToPlayer(player, new SyncAuthLockPayload(false));
                LOGGER.info("[Frontier] Player {} authenticated, restored debug info.", player.getScoreboardName());
            }
        }
    }
}
