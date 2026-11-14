package net.frontier.core;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.bus.api.SubscribeEvent;

/** Server-authoritative, world-global one-mace rule. */
public final class MaceRarityManager {
    private static final String DATA_ID = "frontier_mace_rarity";
    private static final String CLAIMED = "claimed";

    private static MaceState state(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(MaceState::new, MaceState::load), DATA_ID);
    }

    private static boolean isMace(ItemStack stack) {
        return !stack.isEmpty() && stack.is(Items.MACE);
    }

    @SubscribeEvent
    public void onEntityJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof ItemEntity item)) return;
        if (isMace(item.getItem()) && state(level).claimed) {
            item.discard();
        }
    }

    @SubscribeEvent
    public void onPickup(ItemEntityPickupEvent.Pre event) {
        if (!(event.getPlayer() instanceof Player player) || !(player.level() instanceof ServerLevel level)) return;
        ItemEntity item = event.getItemEntity();
        if (!isMace(item.getItem())) return;
        MaceState saved = state(level);
        if (saved.claimed) {
            event.setCanPickup(TriState.FALSE);
            item.discard();
            return;
        }
        // Reserve the unique item before inventory insertion; this is persisted immediately.
        saved.claimed = true;
        saved.setDirty();
    }

    public static final class MaceState extends SavedData {
        private boolean claimed;
        public MaceState() {}
        public static MaceState load(CompoundTag tag, HolderLookup.Provider ignored) {
            MaceState state = new MaceState();
            state.claimed = tag.getBoolean(CLAIMED);
            return state;
        }
        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider ignored) {
            tag.putBoolean(CLAIMED, claimed);
            return tag;
        }
    }
}
