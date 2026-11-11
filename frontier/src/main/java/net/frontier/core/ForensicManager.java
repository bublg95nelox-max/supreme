package net.frontier.core;

import net.frontier.api.CrimeType;
import net.frontier.api.EvidenceGrade;
import net.frontier.data.model.ClueMarker;
import net.frontier.data.model.FrontierState;
import net.frontier.data.model.TerritoryChunk;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

public class ForensicManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(ForensicManager.class);
    private final FrontierServerCache cache;

    public ForensicManager(FrontierServerCache cache) {
        this.cache = cache;
    }

    private boolean shouldTrack(Level level, BlockPos pos) {
        if (level.isClientSide()) return false;
        String dim = level.dimension().location().toString();
        return cache.getStateAt(dim, pos) != null;
    }

    private EvidenceGrade determineGrade(ServerPlayer player) {
        boolean crouching = player.isCrouching();
        ItemStack boots = player.getItemBySlot(EquipmentSlot.FEET);
        boolean lightFootwear = boots.isEmpty() || boots.getItem().toString().contains("leather");

        if (crouching && lightFootwear) {
            return EvidenceGrade.FAINT;
        } else if (!crouching && !boots.isEmpty() && (boots.getItem().toString().contains("netherite") || boots.getItem().toString().contains("diamond") || boots.getItem().toString().contains("iron"))) {
            return EvidenceGrade.CLEAR;
        } else {
            return EvidenceGrade.PARTIAL;
        }
    }

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        Level level = (Level) event.getLevel();
        BlockPos pos = event.getPos();

        if (!shouldTrack(level, pos)) return;

        String dim = level.dimension().location().toString();
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        TerritoryChunk chunk = cache.getChunk(dim, cx, cz);
        FrontierState state = cache.getState(chunk.getStateId());
        if (state == null) return;

        // Check if player is at war with this state
        FrontierState playerState = cache.getPlayerState(player.getUUID());
        if (playerState != null && cache.isAtWar(state.getId(), playerState.getId())) {
            // War activity, not civilian crime
            return;
        }

        // Check if player is a citizen of this state
        if (playerState != null && playerState.getId().equals(state.getId())) {
            // Citizens breaking blocks in their own territory don't leave criminal clues unless sneaking with suspicious tools
            if (!player.isCrouching()) {
                return;
            }
        }

        EvidenceGrade grade = determineGrade(player);
        BlockState blockState = event.getState();
        String blockName = blockState.getBlock().getName().getString();
        ItemStack held = player.getMainHandItem();
        String toolDesc = held.isEmpty() ? "Голые руки" : held.getHoverName().getString();

        String desc;
        if (grade == EvidenceGrade.FAINT) {
            desc = "Едва различимый скол материала (" + blockName + "), мягкие следы подошвы.";
        } else if (grade == EvidenceGrade.CLEAR) {
            desc = "Грубое разрушение блока (" + blockName + ") с помощью [" + toolDesc + "]. Глубокие отпечатки тяжелых ботинок.";
        } else {
            desc = "Свежие повреждения блока (" + blockName + ") инструментом [" + toolDesc + "].";
        }

        ClueMarker clue = new ClueMarker(
                0, dim, pos.getX(), pos.getY(), pos.getZ(), cx, cz,
                CrimeType.BLOCK_BREAK, grade, desc,
                player.getUUID(), player.getGameProfile().getName(),
                toolDesc, System.currentTimeMillis()
        );

        cache.getDatabaseManager().saveClue(clue);
    }

    @SubscribeEvent
    public void onContainerInteract(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Level level = event.getLevel();
        BlockPos pos = event.getPos();

        if (!shouldTrack(level, pos)) return;

        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof RandomizableContainerBlockEntity) {
            String dim = level.dimension().location().toString();
            int cx = pos.getX() >> 4;
            int cz = pos.getZ() >> 4;
            TerritoryChunk chunk = cache.getChunk(dim, cx, cz);
            FrontierState state = cache.getState(chunk.getStateId());
            if (state == null) return;

            FrontierState playerState = cache.getPlayerState(player.getUUID());
            if (playerState != null && playerState.getId().equals(state.getId())) {
                // Citizen opening chests in own state
                return;
            }

            EvidenceGrade grade = determineGrade(player);
            String desc = (grade == EvidenceGrade.CLEAR)
                    ? "Следы взлома и спешного перебора вещей в хранилище. Отпечатки пальцев на замке."
                    : "Хранилище открывалось посторонним. Следы перемещения замка.";

            ClueMarker clue = new ClueMarker(
                    0, dim, pos.getX(), pos.getY(), pos.getZ(), cx, cz,
                    CrimeType.THEFT, grade, desc,
                    player.getUUID(), player.getGameProfile().getName(),
                    "Отмычка / Руки", System.currentTimeMillis()
            );

            cache.getDatabaseManager().saveClue(clue);
        }
    }

    @SubscribeEvent
    public void onPlayerKill(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer victim)) return;
        if (!(event.getSource().getEntity() instanceof ServerPlayer killer)) return;

        Level level = victim.level();
        BlockPos pos = victim.blockPosition();

        if (!shouldTrack(level, pos)) return;

        String dim = level.dimension().location().toString();
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        TerritoryChunk chunk = cache.getChunk(dim, cx, cz);
        FrontierState state = cache.getState(chunk.getStateId());
        if (state == null) return;

        FrontierState killerState = cache.getPlayerState(killer.getUUID());
        FrontierState victimState = cache.getPlayerState(victim.getUUID());

        // Check if wartime combat
        if (killerState != null && victimState != null && cache.isAtWar(killerState.getId(), victimState.getId())) {
            // Military casualty
            return;
        }

        ItemStack weapon = killer.getMainHandItem();
        String weaponDesc = weapon.isEmpty() ? "Кулаки" : weapon.getHoverName().getString();

        String desc = "Пятна крови и следы жестокой схватки. Погибший: " + victim.getGameProfile().getName()
                + ". Причина смерти: удар оружием [" + weaponDesc + "].";

        ClueMarker clue = new ClueMarker(
                0, dim, pos.getX(), pos.getY(), pos.getZ(), cx, cz,
                CrimeType.PVP_KILL, EvidenceGrade.CLEAR, desc,
                killer.getUUID(), killer.getGameProfile().getName(),
                weaponDesc, System.currentTimeMillis()
        );

        cache.getDatabaseManager().saveClue(clue);
    }

    @SubscribeEvent
    public void onExplosion(ExplosionEvent.Detonate event) {
        Level level = event.getLevel();
        if (level.isClientSide()) return;
        BlockPos pos = BlockPos.containing(event.getExplosion().center());

        if (!shouldTrack(level, pos)) return;

        String dim = level.dimension().location().toString();
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;

        ServerPlayer indirectSource = null;
        if (event.getExplosion().getIndirectSourceEntity() instanceof ServerPlayer sp) {
            indirectSource = sp;
        }

        UUID actorUuid = indirectSource != null ? indirectSource.getUUID() : UUID.fromString("00000000-0000-0000-0000-000000000000");
        String actorName = indirectSource != null ? indirectSource.getGameProfile().getName() : "Неизвестный подрывник";

        String desc = "Свежий нагар пороха и глубокая воронка от взрыва (Create / TNT).";

        ClueMarker clue = new ClueMarker(
                0, dim, pos.getX(), pos.getY(), pos.getZ(), cx, cz,
                CrimeType.EXPLOSION, EvidenceGrade.CLEAR, desc,
                actorUuid, actorName,
                "Взрывчатка", System.currentTimeMillis()
        );

        cache.getDatabaseManager().saveClue(clue);
    }
}
