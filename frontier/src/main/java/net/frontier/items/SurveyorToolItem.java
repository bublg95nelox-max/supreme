package net.frontier.items;

import net.frontier.api.JurisdictionType;
import net.frontier.core.FrontierServerCache;
import net.frontier.data.model.*;
import net.frontier.network.FrontierNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.UUID;

public class SurveyorToolItem extends Item {

    public SurveyorToolItem(Properties properties) {
        super(properties);
    }

    /**
     * Intercepts left-click block breaking in survival/adventure mode.
     * Prevents block damage and sets Position 1 (WorldEdit style).
     */
    @Override
    public boolean canAttackBlock(net.minecraft.world.level.block.state.BlockState state, Level level, BlockPos pos, Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            handlePos1(serverPlayer, pos);
        }
        return false; // Cancel block breaking
    }

    /**
     * Event listener to intercept left-clicks across all game modes (including Creative).
     */
    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getItemStack().is(FrontierItems.SURVEYOR_TOOL.get())) {
            event.setCanceled(true);
            if (event.getEntity() instanceof ServerPlayer serverPlayer) {
                handlePos1(serverPlayer, event.getPos());
            }
        }
    }

    /**
     * Right-click on a block.
     * Normal click sets Position 2. Shift-click executes territory action / inspection.
     */
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

        BlockPos clickedPos = context.getClickedPos();

        if (!serverPlayer.isCrouching()) {
            // Normal Right Click = Set Pos 2 (WorldEdit style)
            handlePos2(serverPlayer, clickedPos);
            return InteractionResult.SUCCESS;
        }

        // Shift + Right Click = Territory action / Claim / Inspection
        handleShiftAction(serverPlayer, clickedPos);
        return InteractionResult.SUCCESS;
    }

    /**
     * Right-click in the air: shows current selection summary or claims.
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            if (!serverPlayer.hasPermissions(2)) {
                handleShiftAction(serverPlayer, null);
                return InteractionResultHolder.success(player.getItemInHand(hand));
            }
            if (serverPlayer.isCrouching()) {
                handleShiftAction(serverPlayer, null);
            } else {
                FrontierServerCache cache = FrontierServerCache.get();
                PlayerSelection sel = cache != null ? cache.getPlayerSelection(serverPlayer.getUUID()) : null;
                if (sel != null && sel.isComplete()) {
                    serverPlayer.sendSystemMessage(Component.literal("§6[Землемер] Текущее выделение:")
                            .withStyle(ChatFormatting.GOLD));
                    serverPlayer.sendSystemMessage(Component.literal(" §eТочка 1: §f(" + sel.getPos1().getX() + ", " + sel.getPos1().getY() + ", " + sel.getPos1().getZ() + ")"));
                    serverPlayer.sendSystemMessage(Component.literal(" §bТочка 2: §f(" + sel.getPos2().getX() + ", " + sel.getPos2().getY() + ", " + sel.getPos2().getZ() + ")"));
                    serverPlayer.sendSystemMessage(Component.literal(" §7Размер: §f" + sel.getWidth() + "×" + sel.getLength()
                            + " §7(Высота: §f" + sel.getHeight() + "§7) — §a" + sel.getArea() + " м² §7(" + sel.getChunkCount() + " чанков)"));
                    serverPlayer.sendSystemMessage(Component.literal("§8[Подсказка] Зажмите Shift + ПКМ для создания территории, или: §f/frontier zone create <имя>"));
                } else {
                    serverPlayer.sendSystemMessage(Component.literal("§7[Землемер] Выделение не завершено. §aЛКМ§7 — Точка 1, §bПКМ§7 — Точка 2."));
                }
            }
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide());
    }

    /**
     * When holding the surveyor tool, render particle hints at the 8 bounding box corners once a second.
     */
    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean isSelected) {
        if (!isSelected || level.isClientSide()) return;
        if (level.getGameTime() % 20 != 0) return;
        if (!(entity instanceof ServerPlayer player)) return;
        if (!player.hasPermissions(2)) return;

        FrontierServerCache cache = FrontierServerCache.get();
        if (cache == null) return;
        PlayerSelection sel = cache.getPlayerSelection(player.getUUID());
        if (sel == null || !sel.isComplete()) return;
        if (!sel.getDimension().equals(level.dimension().location().toString())) return;

        ServerLevel serverLevel = player.serverLevel();
        int minX = sel.getMinX();
        int maxX = sel.getMaxX() + 1;
        int minY = sel.getMinY();
        int maxY = sel.getMaxY() + 1;
        int minZ = sel.getMinZ();
        int maxZ = sel.getMaxZ() + 1;

        BlockPos[] corners = new BlockPos[]{
                new BlockPos(minX, minY, minZ),
                new BlockPos(maxX, minY, minZ),
                new BlockPos(minX, minY, maxZ),
                new BlockPos(maxX, minY, maxZ),
                new BlockPos(minX, maxY, minZ),
                new BlockPos(maxX, maxY, minZ),
                new BlockPos(minX, maxY, maxZ),
                new BlockPos(maxX, maxY, maxZ)
        };

        for (BlockPos c : corners) {
            serverLevel.sendParticles(player, ParticleTypes.GLOW, false,
                    c.getX() + 0.5, c.getY() + 0.5, c.getZ() + 0.5,
                    2, 0.1, 0.1, 0.1, 0.01);
        }
    }

    public static void handlePos1(ServerPlayer player, BlockPos pos) {
        if (!player.hasPermissions(2)) {
            handleShiftAction(player, pos);
            return;
        }
        FrontierServerCache cache = FrontierServerCache.get();
        if (cache == null) return;
        String dim = player.level().dimension().location().toString();
        PlayerSelection sel = cache.getPlayerSelection(player.getUUID());
        sel.setDimension(dim);
        sel.setPos1(pos);

        player.playNotifySound(SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 1.0f, 1.2f);
        if (player.serverLevel() != null) {
            player.serverLevel().sendParticles(ParticleTypes.HAPPY_VILLAGER,
                    pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5,
                    8, 0.2, 0.2, 0.2, 0.05);
        }

        String summary = "";
        if (sel.getPos2() != null) {
            summary = " §7[" + sel.getWidth() + "×" + sel.getLength() + " (H:" + sel.getHeight() + ") — §e" + sel.getArea() + " м²§7, " + sel.getChunkCount() + " чанков]";
        }

        player.sendSystemMessage(Component.literal("§a[Землемер] §2Точка 1 (Pos 1) §aустановлена: §f("
                + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")" + summary));
    }

    public static void handlePos2(ServerPlayer player, BlockPos pos) {
        if (!player.hasPermissions(2)) {
            handleShiftAction(player, pos);
            return;
        }
        FrontierServerCache cache = FrontierServerCache.get();
        if (cache == null) return;
        String dim = player.level().dimension().location().toString();
        PlayerSelection sel = cache.getPlayerSelection(player.getUUID());
        sel.setDimension(dim);
        sel.setPos2(pos);

        player.playNotifySound(SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.PLAYERS, 1.0f, 1.5f);
        if (player.serverLevel() != null) {
            player.serverLevel().sendParticles(ParticleTypes.ENCHANT,
                    pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5,
                    12, 0.2, 0.2, 0.2, 0.1);
        }

        String summary = "";
        if (sel.getPos1() != null) {
            summary = " §6[Выделено: §e" + sel.getWidth() + "×" + sel.getLength() + " блоков §7(Высота: " + sel.getHeight() + ") — §a" + sel.getArea() + " м²§7 (" + sel.getChunkCount() + " чанков)]";
        }

        player.sendSystemMessage(Component.literal("§b[Землемер] §3Точка 2 (Pos 2) §bустановлена: §f("
                + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")" + summary));
        if (sel.isComplete()) {
            player.sendSystemMessage(Component.literal("§8[Подсказка] Зажмите Shift + ПКМ для создания территории, или: §f/frontier zone create <имя>"));
        }
    }

    public static void handleShiftAction(ServerPlayer player, BlockPos clickedPos) {
        FrontierServerCache cache = FrontierServerCache.get();
        if (cache == null) return;
        String dim = player.level().dimension().location().toString();
        PlayerSelection sel = cache.getPlayerSelection(player.getUUID());

        if (player.hasPermissions(2) && sel != null && sel.isComplete()) {
            boolean isAdmin = player.hasPermissions(2);
            FrontierState playerState = cache.getPlayerState(player.getUUID());
            StateMember member = playerState != null ? cache.getMember(playerState.getId(), player.getUUID()) : null;
            boolean isOfficer = member != null && member.getRole().canManageTerritory();

            if (isAdmin) {
                // Admin creates Neutral buffer zone or claims
                UUID zoneId = UUID.randomUUID();
                String name = "Буфер-" + (cache.getAllZones().size() + 1);
                TerritoryZone zone = new TerritoryZone(
                        zoneId, null, name, sel.getDimension(),
                        sel.getMinX(), sel.getMinY(), sel.getMinZ(),
                        sel.getMaxX(), sel.getMaxY(), sel.getMaxZ(),
                        JurisdictionType.NEUTRAL, player.getUUID(), System.currentTimeMillis()
                );
                cache.addZone(zone);
                cache.clearPlayerSelection(player.getUUID());
                FrontierNetwork.syncAllTerritoryData();
                player.sendSystemMessage(Component.literal("§b[Frontier-Admin] Нейтральная зона §f'" + name + "' §bсоздана! (" + zone.getArea() + " м² / " + sel.getChunkCount() + " чанков)")
                        .withStyle(ChatFormatting.AQUA));
                return;
            }

            if (isOfficer) {
                // Check for overlapping zones of other nations
                for (TerritoryZone existing : cache.getAllZones()) {
                    if (existing.intersects2D(sel.getDimension(), sel.getMinX(), sel.getMinZ(), sel.getMaxX(), sel.getMaxZ())) {
                        if (existing.getStateId() == null || !existing.getStateId().equals(playerState.getId())) {
                            player.sendSystemMessage(Component.literal("§c[Frontier] Ошибка: выделенная область пересекается с территорией '" + existing.getName() + "' другого государства!")
                                    .withStyle(ChatFormatting.RED));
                            return;
                        }
                    }
                }

                UUID zoneId = UUID.randomUUID();
                String name = playerState.getTag() + "-Зона " + (cache.getZonesOfState(playerState.getId()).size() + 1);
                TerritoryZone zone = new TerritoryZone(
                        zoneId, playerState.getId(), name, sel.getDimension(),
                        sel.getMinX(), sel.getMinY(), sel.getMinZ(),
                        sel.getMaxX(), sel.getMaxY(), sel.getMaxZ(),
                        JurisdictionType.NATIONAL, player.getUUID(), System.currentTimeMillis()
                );
                cache.addZone(zone);
                cache.clearPlayerSelection(player.getUUID());
                FrontierNetwork.syncAllTerritoryData();
                player.sendSystemMessage(Component.literal("§a[Frontier] Территория §f'" + name + "' §a(" + zone.getArea() + " м²) успешно присоединена к " + playerState.getName() + "!")
                        .withStyle(ChatFormatting.GREEN));
                return;
            }

            player.sendSystemMessage(Component.literal("§c[Frontier] У вас нет прав офицера на создание территорий государств.")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        // Selection is not complete -> inspect block / territory info
        BlockPos targetPos = clickedPos != null ? clickedPos : player.blockPosition();
        TerritoryZone zone = cache.getZoneAt(dim, targetPos);
        if (zone != null) {
            FrontierState st = zone.getStateId() != null ? cache.getState(zone.getStateId()) : null;
            String stName = st != null ? st.getName() + " [" + st.getTag() + "]" : "Нейтральная зона";
            player.sendSystemMessage(Component.literal("§6[Землемер] Территория: §e" + zone.getName()
                    + " §7| Владелец: §f" + stName
                    + " §7| Юрисдикция: §a" + zone.getJurisdictionType().getLabel()
                    + " §7| Площадь: §f" + zone.getArea() + " м²")
                    .withStyle(ChatFormatting.GOLD));
        } else {
            int cx = targetPos.getX() >> 4;
            int cz = targetPos.getZ() >> 4;
            TerritoryChunk chunk = cache.getChunk(dim, cx, cz);
            if (chunk == null || chunk.getStateId() == null) {
                JurisdictionType type = chunk != null ? chunk.getJurisdictionType() : JurisdictionType.WILD;
                player.sendSystemMessage(Component.literal("§7[Землемер] Блок вне именованных зон. Чанк [" + cx + ", " + cz + "]: §f" + type.getLabel())
                        .withStyle(ChatFormatting.GRAY));
            } else {
                FrontierState st = cache.getState(chunk.getStateId());
                player.sendSystemMessage(Component.literal("§6[Землемер] Чанк [" + cx + ", " + cz + "] принадлежит §e" + (st != null ? st.getName() : "Государство")
                        + " §7(Юрисдикция: §a" + chunk.getJurisdictionType().getLabel() + "§7)")
                        .withStyle(ChatFormatting.GOLD));
            }
        }
    }
}
