package net.frontier.items;

import net.frontier.api.Role;
import net.frontier.core.FrontierServerCache;
import net.frontier.data.model.ClueMarker;
import net.frontier.data.model.FrontierState;
import net.frontier.data.model.StateMember;
import net.frontier.data.model.TerritoryChunk;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.List;

public class DetectiveKitItem extends Item {
    public DetectiveKitItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        Player player = context.getPlayer();
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }

        BlockPos pos = context.getClickedPos();
        String dim = level.dimension().location().toString();
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;

        FrontierServerCache cache = FrontierServerCache.get();
        TerritoryChunk chunk = cache.getChunk(dim, cx, cz);

        if (chunk == null || chunk.getStateId() == null) {
            serverPlayer.sendSystemMessage(Component.literal("§7[Frontier] Это нейтральные или дикие земли. Криминалистические записи здесь не ведутся.")
                    .withStyle(ChatFormatting.GRAY));
            return InteractionResult.SUCCESS;
        }

        FrontierState state = cache.getState(chunk.getStateId());
        String stateName = state != null ? state.getName() : "Неизвестное государство";

        // Check if player has right to investigate (is judge/officer/leader of this state, or admin)
        StateMember member = cache.getMember(chunk.getStateId(), serverPlayer.getUUID());
        boolean hasPerms = serverPlayer.hasPermissions(2) || (member != null && member.getRole().canInvestigate());

        if (!hasPerms) {
            serverPlayer.sendSystemMessage(Component.literal("§c[Frontier] У вас нет полномочий дознавателя или судьи на территории " + stateName + ".")
                    .withStyle(ChatFormatting.RED));
            return InteractionResult.SUCCESS;
        }

        // Query database for recent clues in this chunk (past 24 hours)
        long since = System.currentTimeMillis() - 86400000L;
        cache.getDatabaseManager().executeAsync(() -> {
            try {
                List<ClueMarker> clues = cache.getDatabaseManager().queryCluesInChunk(dim, cx, cz, since);
                serverPlayer.server.execute(() -> {
                    if (clues.isEmpty()) {
                        serverPlayer.sendSystemMessage(Component.literal("§a[Frontier] Осмотр чанка [" + cx + ", " + cz + "] завершён: подозрительных следов не обнаружено.")
                                .withStyle(ChatFormatting.GREEN));
                    } else {
                        serverPlayer.sendSystemMessage(Component.literal("§6=== Протокол осмотра места происшествия [" + stateName + "] ===")
                                .withStyle(ChatFormatting.GOLD));
                        serverPlayer.sendSystemMessage(Component.literal("§7Обнаружено улик: §f" + clues.size()));

                        for (int i = 0; i < Math.min(3, clues.size()); i++) {
                            ClueMarker clue = clues.get(i);
                            String timeDesc = clue.getTimeAgoDesc(System.currentTimeMillis());
                            serverPlayer.sendSystemMessage(Component.literal("§e#" + clue.getId() + " §8[" + clue.getGrade().getLabel() + "§8] §f"
                                    + clue.getCrimeType().getTitle() + " §7(" + timeDesc + "):"));
                            serverPlayer.sendSystemMessage(Component.literal("  §f" + clue.getDescription()));
                        }

                        // If sneaking, create an Evidence Note for the latest clue
                        if (serverPlayer.isCrouching() && !clues.isEmpty()) {
                            ClueMarker latest = clues.get(0);
                            ItemStack note = new ItemStack(FrontierItems.EVIDENCE_NOTE.get());
                            CompoundTag tag = new CompoundTag();
                            tag.putInt("ClueId", latest.getId());
                            tag.putString("StateName", stateName);
                            tag.putString("CrimeType", latest.getCrimeType().name());
                            tag.putString("Grade", latest.getGrade().name());
                            tag.putString("Desc", latest.getDescription());
                            tag.putString("Tool", latest.getToolUsed());
                            tag.putLong("Timestamp", latest.getTimestamp());
                            tag.putString("ActorName", latest.getGrade() == net.frontier.api.EvidenceGrade.CLEAR ? latest.getActorName() : "Неопознан");
                            note.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));

                            if (!serverPlayer.getInventory().add(note)) {
                                serverPlayer.drop(note, false);
                            }
                            serverPlayer.sendSystemMessage(Component.literal("§a[+] В инвентарь добавлена «Заметка эксперта» (Улика #" + latest.getId() + ").")
                                    .withStyle(ChatFormatting.GREEN));
                        } else {
                            serverPlayer.sendSystemMessage(Component.literal("§8[Подсказка] Зажмите Shift при осмотре, чтобы извлечь вещественное доказательство.")
                                    .withStyle(ChatFormatting.DARK_GRAY));
                        }
                    }
                });
            } catch (Exception e) {
                serverPlayer.sendSystemMessage(Component.literal("§cОшибка при анализе улик: " + e.getMessage()));
            }
        });

        return InteractionResult.SUCCESS;
    }
}
