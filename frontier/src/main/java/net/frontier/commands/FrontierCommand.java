package net.frontier.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.frontier.api.JurisdictionType;
import net.frontier.api.Role;
import net.frontier.core.FrontierServerCache;
import net.frontier.data.model.*;
import net.frontier.items.SurveyorToolItem;
import net.frontier.network.FrontierNetwork;
import net.frontier.network.payloads.OpenFrontierScreenPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public class FrontierCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("frontier")
                .executes(ctx -> openGui(ctx.getSource(), 0))
                .then(Commands.literal("gui")
                        .executes(ctx -> openGui(ctx.getSource(), 0)))
                .then(Commands.literal("map")
                        .executes(ctx -> openGui(ctx.getSource(), 0)))
                .then(Commands.literal("laws")
                        .executes(ctx -> openGui(ctx.getSource(), 2)))
                .then(Commands.literal("diplomacy")
                        .executes(ctx -> openGui(ctx.getSource(), 3)))
                .then(Commands.literal("court")
                        .executes(ctx -> openGui(ctx.getSource(), 4)))
                .then(Commands.literal("vassals")
                        .executes(ctx -> listVassals(ctx.getSource()))
                        .then(Commands.literal("list")
                                .executes(ctx -> listVassals(ctx.getSource())))
                        .then(Commands.literal("pledge")
                                .then(Commands.argument("suzerain_tag", StringArgumentType.word())
                                        .executes(ctx -> pledgeVassal(ctx.getSource(), StringArgumentType.getString(ctx, "suzerain_tag")))))
                        .then(Commands.literal("rebel")
                                .executes(ctx -> rebelVassal(ctx.getSource())))
                        .then(Commands.literal("release")
                                .then(Commands.argument("vassal_tag", StringArgumentType.word())
                                        .executes(ctx -> releaseVassal(ctx.getSource(), StringArgumentType.getString(ctx, "vassal_tag"))))))
                .then(Commands.literal("info")
                        .executes(ctx -> showInfo(ctx.getSource())))
                .then(Commands.literal("pos1")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> setPos1Command(ctx.getSource())))
                .then(Commands.literal("pos2")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> setPos2Command(ctx.getSource())))
                .then(Commands.literal("select")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> showSelectCommand(ctx.getSource()))
                        .then(Commands.literal("clear")
                                .executes(ctx -> clearSelectCommand(ctx.getSource()))))
                .then(Commands.literal("zone")
                        .then(Commands.literal("create")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                        .executes(ctx -> createZoneCommand(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
                        .then(Commands.literal("remove")
                                .requires(src -> src.hasPermission(2))
                                .executes(ctx -> removeZoneCommand(ctx.getSource())))
                        .then(Commands.literal("list")
                                .executes(ctx -> listZonesCommand(ctx.getSource()))))
                .then(Commands.literal("claim")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> claimChunk(ctx.getSource())))
                .then(Commands.literal("unclaim")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> unclaimChunk(ctx.getSource())))
                .then(Commands.literal("admin")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.literal("petitions")
                                .executes(ctx -> listPetitions(ctx.getSource())))
                        .then(Commands.literal("approve")
                                .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                        .executes(ctx -> approvePetition(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "id")))))
                        .then(Commands.literal("neutral")
                                .executes(ctx -> setNeutral(ctx.getSource())))
                        .then(Commands.literal("unclaim")
                                .executes(ctx -> adminUnclaim(ctx.getSource())))
                        .then(Commands.literal("claim")
                                .then(Commands.argument("state_tag", StringArgumentType.word())
                                        .executes(ctx -> adminClaimForState(ctx.getSource(), StringArgumentType.getString(ctx, "state_tag")))))
                        .then(Commands.literal("zone")
                                .then(Commands.literal("create")
                                        .then(Commands.argument("state_tag", StringArgumentType.word())
                                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                                        .executes(ctx -> adminCreateZone(ctx.getSource(), StringArgumentType.getString(ctx, "state_tag"), StringArgumentType.getString(ctx, "name"))))))
                                .then(Commands.literal("neutral")
                                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                                .executes(ctx -> adminCreateNeutralZone(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
                                .then(Commands.literal("remove")
                                        .executes(ctx -> adminRemoveZone(ctx.getSource())))
                                .then(Commands.literal("list")
                                        .executes(ctx -> adminListZones(ctx.getSource())))))
        );
    }

    private static int openGui(CommandSourceStack source, int tab) {
        if (source.getEntity() instanceof ServerPlayer player) {
            FrontierNetwork.syncPlayerData(player);
            FrontierNetwork.sendToPlayer(player, new OpenFrontierScreenPayload(tab));
            return 1;
        }
        source.sendFailure(Component.literal("Команда доступна только игрокам."));
        return 0;
    }

    private static int showInfo(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) return 0;
        FrontierServerCache cache = FrontierServerCache.get();
        int cx = player.blockPosition().getX() >> 4;
        int cz = player.blockPosition().getZ() >> 4;
        String dim = player.level().dimension().location().toString();

        TerritoryChunk chunk = cache.getChunk(dim, cx, cz);
        if (chunk == null || chunk.getStateId() == null) {
            JurisdictionType type = chunk != null ? chunk.getJurisdictionType() : JurisdictionType.WILD;
            source.sendSuccess(() -> Component.literal("§7[Frontier] Чанк [" + cx + ", " + cz + "]: §f" + type.getLabel()), false);
        } else {
            FrontierState st = cache.getState(chunk.getStateId());
            String stName = st != null ? st.getName() : "Государство";
            String tag = st != null ? st.getTag() : "?";
            source.sendSuccess(() -> Component.literal("§6[Frontier] Чанк [" + cx + ", " + cz + "] принадлежит §e" + stName + " §7[" + tag + "] (" + chunk.getJurisdictionType().getLabel() + ")"), false);
        }
        return 1;
    }

    private static int claimChunk(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) return 0;
        FrontierServerCache cache = FrontierServerCache.get();
        FrontierState st = cache.getPlayerState(player.getUUID());
        if (st == null) {
            source.sendFailure(Component.literal("§cВы не состоите в государстве."));
            return 0;
        }
        StateMember m = cache.getMember(st.getId(), player.getUUID());
        if (m == null || !m.getRole().canManageTerritory()) {
            source.sendFailure(Component.literal("§cУ вас нет прав на клейм чанков (требуется Офицер или Лидер)."));
            return 0;
        }

        int cx = player.blockPosition().getX() >> 4;
        int cz = player.blockPosition().getZ() >> 4;
        String dim = player.level().dimension().location().toString();

        TerritoryChunk existing = cache.getChunk(dim, cx, cz);
        if (existing != null && existing.getStateId() != null) {
            if (existing.getStateId().equals(st.getId())) {
                source.sendFailure(Component.literal("§eЭтот чанк уже принадлежит вашему государству."));
            } else {
                source.sendFailure(Component.literal("§cЧанк принадлежит другому государству! Приват чужого запрещён."));
            }
            return 0;
        }

        TerritoryChunk chunk = new TerritoryChunk(dim, cx, cz, st.getId(), JurisdictionType.NATIONAL, player.getUUID(), System.currentTimeMillis());
        cache.setClaim(chunk);
        source.sendSuccess(() -> Component.literal("§a[Frontier] Чанк [" + cx + ", " + cz + "] успешно присоединён к " + st.getName() + "!"), true);
        FrontierNetwork.broadcastTerritoryUpdate(player.server);
        return 1;
    }

    private static int unclaimChunk(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) return 0;
        FrontierServerCache cache = FrontierServerCache.get();
        FrontierState st = cache.getPlayerState(player.getUUID());
        if (st == null) return 0;
        StateMember m = cache.getMember(st.getId(), player.getUUID());
        if (m == null || !m.getRole().canManageTerritory()) {
            source.sendFailure(Component.literal("§cУ вас нет прав на расприват чанков."));
            return 0;
        }

        int cx = player.blockPosition().getX() >> 4;
        int cz = player.blockPosition().getZ() >> 4;
        String dim = player.level().dimension().location().toString();

        TerritoryChunk existing = cache.getChunk(dim, cx, cz);
        if (existing == null || !st.getId().equals(existing.getStateId())) {
            source.sendFailure(Component.literal("§cЭтот чанк не принадлежит вашему государству."));
            return 0;
        }

        cache.unclaim(dim, cx, cz);
        source.sendSuccess(() -> Component.literal("§7[Frontier] Чанк [" + cx + ", " + cz + "] исключён из юрисдикции государства."), true);
        FrontierNetwork.broadcastTerritoryUpdate(player.server);
        return 1;
    }

    private static int listPetitions(CommandSourceStack source) {
        FrontierServerCache cache = FrontierServerCache.get();
        List<StatePetition> list = cache.getPetitions();
        source.sendSuccess(() -> Component.literal("§6=== Заявки на создание государств ==="), false);
        boolean hasPending = false;
        for (StatePetition p : list) {
            if ("PENDING".equals(p.getStatus())) {
                hasPending = true;
                source.sendSuccess(() -> Component.literal("§e#" + p.getId() + " §f«" + p.getStateName() + "» [" + p.getStateTag() + "] от §b" + p.getRequesterName()
                        + " §7(Чанк: " + p.getCapitalChunkX() + ", " + p.getCapitalChunkZ() + ") §a/frontier admin approve " + p.getId()), false);
            }
        }
        if (!hasPending) {
            source.sendSuccess(() -> Component.literal("§7Нет активных заявок."), false);
        }
        return 1;
    }

    private static int approvePetition(CommandSourceStack source, int id) {
        FrontierServerCache cache = FrontierServerCache.get();
        for (StatePetition p : cache.getPetitions()) {
            if (p.getId() == id && "PENDING".equals(p.getStatus())) {
                p.setStatus("APPROVED");
                cache.updatePetition(p);

                UUID stateId = UUID.randomUUID();
                FrontierState st = new FrontierState(
                        stateId, p.getStateName(), p.getStateTag(), p.getColor(),
                        p.getRequesterUuid(), p.getRequesterName(), System.currentTimeMillis(), "Да здравствует " + p.getStateName() + "!"
                );
                StateMember leader = new StateMember(stateId, p.getRequesterUuid(), p.getRequesterName(), Role.LEADER, System.currentTimeMillis());
                cache.registerState(st, leader);

                TerritoryChunk capital = new TerritoryChunk(p.getDimension(), p.getCapitalChunkX(), p.getCapitalChunkZ(), stateId, JurisdictionType.NATIONAL, p.getRequesterUuid(), System.currentTimeMillis());
                cache.setClaim(capital);

                source.sendSuccess(() -> Component.literal("§a[Frontier-Admin] Заявка #" + id + " утверждена! Создано государство «" + st.getName() + "»."), true);
                if (source.getServer() != null) {
                    source.getServer().getPlayerList().broadcastSystemMessage(
                            Component.literal("§6§l[Frontier] На мировой арене провозглашено новое государство: §e" + st.getName() + " §7[" + st.getTag() + "]!"), false
                    );
                    FrontierNetwork.broadcastTerritoryUpdate(source.getServer());
                }
                return 1;
            }
        }
        source.sendFailure(Component.literal("§cЗаявка с таким ID не найдена или уже рассмотрена."));
        return 0;
    }

    private static int setNeutral(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) return 0;
        FrontierServerCache cache = FrontierServerCache.get();
        int cx = player.blockPosition().getX() >> 4;
        int cz = player.blockPosition().getZ() >> 4;
        String dim = player.level().dimension().location().toString();

        TerritoryChunk chunk = new TerritoryChunk(dim, cx, cz, null, JurisdictionType.NEUTRAL, player.getUUID(), System.currentTimeMillis());
        cache.setClaim(chunk);
        source.sendSuccess(() -> Component.literal("§b[Frontier-Admin] Чанк [" + cx + ", " + cz + "] объявлен Нейтральной зоной!"), true);
        FrontierNetwork.broadcastTerritoryUpdate(player.server);
        return 1;
    }

    private static int adminUnclaim(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) return 0;
        FrontierServerCache cache = FrontierServerCache.get();
        int cx = player.blockPosition().getX() >> 4;
        int cz = player.blockPosition().getZ() >> 4;
        String dim = player.level().dimension().location().toString();

        cache.unclaim(dim, cx, cz);
        source.sendSuccess(() -> Component.literal("§7[Frontier-Admin] Чанк [" + cx + ", " + cz + "] освобождён (Дикие земли)."), true);
        FrontierNetwork.broadcastTerritoryUpdate(player.server);
        return 1;
    }

    private static int adminClaimForState(CommandSourceStack source, String stateTag) {
        if (!(source.getEntity() instanceof ServerPlayer player)) return 0;
        FrontierServerCache cache = FrontierServerCache.get();
        FrontierState st = cache.getStateByTag(stateTag);
        if (st == null) {
            source.sendFailure(Component.literal("§cГосударство с тегом [" + stateTag + "] не найдено."));
            return 0;
        }

        int cx = player.blockPosition().getX() >> 4;
        int cz = player.blockPosition().getZ() >> 4;
        String dim = player.level().dimension().location().toString();

        TerritoryChunk chunk = new TerritoryChunk(dim, cx, cz, st.getId(), JurisdictionType.NATIONAL, player.getUUID(), System.currentTimeMillis());
        cache.setClaim(chunk);
        source.sendSuccess(() -> Component.literal("§a[Frontier-Admin] Чанк [" + cx + ", " + cz + "] передан государству " + st.getName() + "!"), true);
        FrontierNetwork.broadcastTerritoryUpdate(player.server);
        return 1;
    }

    private static int listVassals(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) return 0;
        FrontierServerCache cache = FrontierServerCache.get();
        FrontierState pState = cache.getPlayerState(player.getUUID());

        source.sendSuccess(() -> Component.literal("§6§o«Вассал моего вассала — не мой вассал...»"), false);
        source.sendSuccess(() -> Component.literal("§8§m----------------------------------------"), false);

        if (pState == null) {
            source.sendSuccess(() -> Component.literal("§7Вы свободный житель и не связаны феодальными клятвами."), false);
            return 1;
        }

        // Suzerain
        net.frontier.data.model.VassalPact suzerainPact = cache.getSuzerainPact(pState.getId());
        if (suzerainPact != null) {
            FrontierState suzerain = cache.getState(suzerainPact.getSuzerainId());
            String sName = suzerain != null ? suzerain.getName() : "Неизвестный";
            String sTag = suzerain != null ? suzerain.getTag() : "?";
            source.sendSuccess(() -> Component.literal("§eВаш Сюзерен: §6" + sName + " §7[" + sTag + "] §8(Титул: §f" + suzerainPact.getTitle() + "§8)"), false);
        } else {
            source.sendSuccess(() -> Component.literal("§aВаше государство полностью суверенно (нет сюзерена)."), false);
        }

        // Vassals
        List<net.frontier.data.model.VassalPact> vassals = cache.getDirectVassals(pState.getId());
        if (!vassals.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§bВаши прямые вассалы:"), false);
            for (net.frontier.data.model.VassalPact vp : vassals) {
                FrontierState vState = cache.getState(vp.getVassalId());
                String vName = vState != null ? vState.getName() : "Вассал";
                String vTag = vState != null ? vState.getTag() : "?";
                source.sendSuccess(() -> Component.literal("  §f• §e" + vName + " §7[" + vTag + "] — §7" + vp.getTitle() + " §8(Дань: " + vp.getTributePercent() + "%)"), false);
            }
        } else {
            source.sendSuccess(() -> Component.literal("§7У вашего государства нет прямых вассалов."), false);
        }

        return 1;
    }

    private static int pledgeVassal(CommandSourceStack source, String suzerainTag) {
        if (!(source.getEntity() instanceof ServerPlayer player)) return 0;
        FrontierServerCache cache = FrontierServerCache.get();
        FrontierState pState = cache.getPlayerState(player.getUUID());
        if (pState == null) {
            source.sendFailure(Component.literal("§cВы не состоите в государстве."));
            return 0;
        }
        StateMember m = cache.getMember(pState.getId(), player.getUUID());
        if (m == null || m.getRole() != Role.LEADER) {
            source.sendFailure(Component.literal("§cТолько лидер государства может приносить вассальную клятву."));
            return 0;
        }

        FrontierState suzerain = cache.getStateByTag(suzerainTag);
        if (suzerain == null) {
            source.sendFailure(Component.literal("§cГосударство с тегом [" + suzerainTag + "] не найдено."));
            return 0;
        }

        if (suzerain.getId().equals(pState.getId())) {
            source.sendFailure(Component.literal("§cНельзя стать вассалом самого себя."));
            return 0;
        }

        if (cache.isDirectVassal(suzerain.getId(), pState.getId())) {
            source.sendFailure(Component.literal("§cНельзя присягнуть собственному вассалу! («Вассал моего вассала — не мой вассал»)."));
            return 0;
        }

        cache.setVassalage(suzerain.getId(), pState.getId(), "Вассалитет", 10);
        cache.setRelation(new net.frontier.data.model.DiplomacyRelation(suzerain.getId(), pState.getId(), net.frontier.api.RelationType.ALLIANCE, player.getUUID(), System.currentTimeMillis(), "Оммаж"));

        String msg = "§6§l[ФЕОДАЛЬНЫЙ ОММАЖ] §e«" + pState.getName() + "» принесло присягу верности Сюзерену «" + suzerain.getName() + "»!";
        player.server.getPlayerList().broadcastSystemMessage(Component.literal(msg), false);
        FrontierNetwork.broadcastTerritoryUpdate(player.server);
        return 1;
    }

    private static int rebelVassal(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) return 0;
        FrontierServerCache cache = FrontierServerCache.get();
        FrontierState pState = cache.getPlayerState(player.getUUID());
        if (pState == null) return 0;
        StateMember m = cache.getMember(pState.getId(), player.getUUID());
        if (m == null || m.getRole() != Role.LEADER) {
            source.sendFailure(Component.literal("§cТолько лидер государства может объявить восстание."));
            return 0;
        }

        net.frontier.data.model.VassalPact pact = cache.getSuzerainPact(pState.getId());
        if (pact == null) {
            source.sendFailure(Component.literal("§eВаше государство не является вассалом."));
            return 0;
        }

        FrontierState suzerain = cache.getState(pact.getSuzerainId());
        String suzerainName = suzerain != null ? suzerain.getName() : "бывший сюзерен";

        cache.breakVassalage(pState.getId());
        cache.setRelation(new net.frontier.data.model.DiplomacyRelation(pact.getSuzerainId(), pState.getId(), net.frontier.api.RelationType.WAR, player.getUUID(), System.currentTimeMillis(), "Восстание"));

        String msg = "§4§l[ВОССТАНИЕ ВАССАЛА] §cГосударство «" + pState.getName() + "» разорвало клятву и объявило войну сюзерену «" + suzerainName + "»!";
        player.server.getPlayerList().broadcastSystemMessage(Component.literal(msg), false);
        FrontierNetwork.broadcastTerritoryUpdate(player.server);
        return 1;
    }

    private static int releaseVassal(CommandSourceStack source, String vassalTag) {
        if (!(source.getEntity() instanceof ServerPlayer player)) return 0;
        FrontierServerCache cache = FrontierServerCache.get();
        FrontierState pState = cache.getPlayerState(player.getUUID());
        if (pState == null) return 0;
        StateMember m = cache.getMember(pState.getId(), player.getUUID());
        if (m == null || m.getRole() != Role.LEADER) {
            source.sendFailure(Component.literal("§cТолько лидер государства может даровать вольную."));
            return 0;
        }

        FrontierState vassal = cache.getStateByTag(vassalTag);
        if (vassal == null) {
            source.sendFailure(Component.literal("§cГосударство с тегом [" + vassalTag + "] не найдено."));
            return 0;
        }

        net.frontier.data.model.VassalPact pact = cache.getSuzerainPact(vassal.getId());
        if (pact == null || !pState.getId().equals(pact.getSuzerainId())) {
            source.sendFailure(Component.literal("§cЭто государство не является вашим прямым вассалом."));
            return 0;
        }

        cache.breakVassalage(vassal.getId());
        cache.setRelation(new net.frontier.data.model.DiplomacyRelation(pState.getId(), vassal.getId(), net.frontier.api.RelationType.NON_AGGRESSION, player.getUUID(), System.currentTimeMillis(), "Вольная грамота"));

        String msg = "§a§l[ВОЛЬНАЯ ГРАМОТА] §2Сюзерен «" + pState.getName() + "» признал независимость «" + vassal.getName() + "»!";
        player.server.getPlayerList().broadcastSystemMessage(Component.literal(msg), false);
        FrontierNetwork.broadcastTerritoryUpdate(player.server);
        return 1;
    }

    // --- Block-based selection commands ---

    private static int setPos1Command(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Команда доступна только игрокам."));
            return 0;
        }
        SurveyorToolItem.handlePos1(player, player.blockPosition());
        return 1;
    }

    private static int setPos2Command(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Команда доступна только игрокам."));
            return 0;
        }
        SurveyorToolItem.handlePos2(player, player.blockPosition());
        return 1;
    }

    private static int showSelectCommand(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) return 0;
        FrontierServerCache cache = FrontierServerCache.get();
        PlayerSelection sel = cache.getPlayerSelection(player.getUUID());
        if (sel == null || !sel.isComplete()) {
            source.sendFailure(Component.literal("§c[Frontier] Выделение не завершено. Используйте ЛКМ/ПКМ инструментом землемера или /frontier pos1 и pos2."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("§6[Frontier] Текущее выделение:"), false);
        source.sendSuccess(() -> Component.literal(" §eТочка 1: §f(" + sel.getPos1().getX() + ", " + sel.getPos1().getY() + ", " + sel.getPos1().getZ() + ")"), false);
        source.sendSuccess(() -> Component.literal(" §bТочка 2: §f(" + sel.getPos2().getX() + ", " + sel.getPos2().getY() + ", " + sel.getPos2().getZ() + ")"), false);
        source.sendSuccess(() -> Component.literal(" §7Размер: §f" + sel.getWidth() + "×" + sel.getLength() + " (высота: " + sel.getHeight() + ") — §a" + sel.getArea() + " м² §7(" + sel.getChunkCount() + " чанков)"), false);
        return 1;
    }

    private static int clearSelectCommand(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) return 0;
        FrontierServerCache.get().clearPlayerSelection(player.getUUID());
        source.sendSuccess(() -> Component.literal("§a[Frontier] Выделение очищено."), false);
        return 1;
    }

    private static int createZoneCommand(CommandSourceStack source, String name) {
        if (!(source.getEntity() instanceof ServerPlayer player)) return 0;
        FrontierServerCache cache = FrontierServerCache.get();
        FrontierState playerState = cache.getPlayerState(player.getUUID());
        if (playerState == null) {
            source.sendFailure(Component.literal("§c[Frontier] Вы не состоите в государстве."));
            return 0;
        }
        StateMember member = cache.getMember(playerState.getId(), player.getUUID());
        if (member == null || !member.getRole().canManageTerritory()) {
            source.sendFailure(Component.literal("§c[Frontier] У вас нет полномочий офицера на создание территорий."));
            return 0;
        }
        PlayerSelection sel = cache.getPlayerSelection(player.getUUID());
        if (sel == null || !sel.isComplete()) {
            source.sendFailure(Component.literal("§c[Frontier] Сначала выделите территорию по блокам (ЛКМ и ПКМ инструментом землемера или /frontier pos1 и pos2)."));
            return 0;
        }
        for (TerritoryZone existing : cache.getAllZones()) {
            if (existing.intersects2D(sel.getDimension(), sel.getMinX(), sel.getMinZ(), sel.getMaxX(), sel.getMaxZ())) {
                if (existing.getStateId() == null || !existing.getStateId().equals(playerState.getId())) {
                    source.sendFailure(Component.literal("§c[Frontier] Ошибка: выделенная область пересекается с территорией '" + existing.getName() + "' другого государства!"));
                    return 0;
                }
            }
        }
        UUID zoneId = UUID.randomUUID();
        TerritoryZone zone = new TerritoryZone(
                zoneId, playerState.getId(), name, sel.getDimension(),
                sel.getMinX(), sel.getMinY(), sel.getMinZ(),
                sel.getMaxX(), sel.getMaxY(), sel.getMaxZ(),
                JurisdictionType.NATIONAL, player.getUUID(), System.currentTimeMillis()
        );
        cache.addZone(zone);
        cache.clearPlayerSelection(player.getUUID());
        FrontierNetwork.syncAllTerritoryData();
        source.sendSuccess(() -> Component.literal("§a[Frontier] Территория '" + name + "' (" + zone.getArea() + " м² / " + sel.getChunkCount() + " чанков) успешно закреплена за " + playerState.getName() + "!"), true);
        return 1;
    }

    private static int removeZoneCommand(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) return 0;
        FrontierServerCache cache = FrontierServerCache.get();
        FrontierState playerState = cache.getPlayerState(player.getUUID());
        if (playerState == null) {
            source.sendFailure(Component.literal("§c[Frontier] Вы не состоите в государстве."));
            return 0;
        }
        StateMember member = cache.getMember(playerState.getId(), player.getUUID());
        if (member == null || !member.getRole().canManageTerritory()) {
            source.sendFailure(Component.literal("§c[Frontier] У вас нет полномочий офицера."));
            return 0;
        }
        String dim = player.level().dimension().location().toString();
        TerritoryZone zone = cache.getZoneAt(dim, player.blockPosition());
        if (zone == null || !playerState.getId().equals(zone.getStateId())) {
            source.sendFailure(Component.literal("§c[Frontier] Вы не находитесь на территории своего государства."));
            return 0;
        }
        cache.removeZone(zone.getId());
        FrontierNetwork.syncAllTerritoryData();
        source.sendSuccess(() -> Component.literal("§c[Frontier] Территория '" + zone.getName() + "' была упразднена."), true);
        return 1;
    }

    private static int listZonesCommand(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) return 0;
        FrontierServerCache cache = FrontierServerCache.get();
        FrontierState playerState = cache.getPlayerState(player.getUUID());
        if (playerState == null) {
            source.sendFailure(Component.literal("§c[Frontier] Вы не состоите в государстве."));
            return 0;
        }
        List<TerritoryZone> list = cache.getZonesOfState(playerState.getId());
        source.sendSuccess(() -> Component.literal("§6=== Территории государства " + playerState.getName() + " (" + list.size() + ") ==="), false);
        for (TerritoryZone z : list) {
            source.sendSuccess(() -> Component.literal(" §e• " + z.getName() + " §7[" + z.getMinX() + "," + z.getMinZ() + " → " + z.getMaxX() + "," + z.getMaxZ() + "] — §a" + z.getArea() + " м²"), false);
        }
        return 1;
    }

    private static int adminCreateZone(CommandSourceStack source, String stateTag, String name) {
        FrontierServerCache cache = FrontierServerCache.get();
        FrontierState targetState = null;
        for (FrontierState s : cache.getAllStates()) {
            if (s.getTag().equalsIgnoreCase(stateTag) || s.getName().equalsIgnoreCase(stateTag)) {
                targetState = s;
                break;
            }
        }
        if (targetState == null) {
            source.sendFailure(Component.literal("§c[Frontier-Admin] Государство с тегом '" + stateTag + "' не найдено."));
            return 0;
        }
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Требуется игрок с выделением."));
            return 0;
        }
        PlayerSelection sel = cache.getPlayerSelection(player.getUUID());
        if (sel == null || !sel.isComplete()) {
            source.sendFailure(Component.literal("§c[Frontier-Admin] Сначала выделите область инструментом землемера."));
            return 0;
        }
        UUID zoneId = UUID.randomUUID();
        TerritoryZone zone = new TerritoryZone(
                zoneId, targetState.getId(), name, sel.getDimension(),
                sel.getMinX(), sel.getMinY(), sel.getMinZ(),
                sel.getMaxX(), sel.getMaxY(), sel.getMaxZ(),
                JurisdictionType.NATIONAL, player.getUUID(), System.currentTimeMillis()
        );
        cache.addZone(zone);
        cache.clearPlayerSelection(player.getUUID());
        FrontierNetwork.syncAllTerritoryData();
        final FrontierState finalTargetState = targetState;
        source.sendSuccess(() -> Component.literal("§a[Frontier-Admin] Территория '" + name + "' (" + zone.getArea() + " м²) назначена государству " + finalTargetState.getName() + "!"), true);
        return 1;
    }

    private static int adminCreateNeutralZone(CommandSourceStack source, String name) {
        if (!(source.getEntity() instanceof ServerPlayer player)) return 0;
        FrontierServerCache cache = FrontierServerCache.get();
        PlayerSelection sel = cache.getPlayerSelection(player.getUUID());
        if (sel == null || !sel.isComplete()) {
            source.sendFailure(Component.literal("§c[Frontier-Admin] Сначала выделите область инструментом землемера."));
            return 0;
        }
        UUID zoneId = UUID.randomUUID();
        TerritoryZone zone = new TerritoryZone(
                zoneId, null, name, sel.getDimension(),
                sel.getMinX(), sel.getMinY(), sel.getMinZ(),
                sel.getMaxX(), sel.getMaxY(), sel.getMaxZ(),
                JurisdictionType.NEUTRAL, player.getUUID(), System.currentTimeMillis()
        );
        cache.addZone(zone);
        cache.clearPlayerSelection(player.getUUID());
        FrontierNetwork.syncAllTerritoryData();
        source.sendSuccess(() -> Component.literal("§b[Frontier-Admin] Нейтральная буферная зона '" + name + "' (" + zone.getArea() + " м²) создана!"), true);
        return 1;
    }

    private static int adminRemoveZone(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) return 0;
        FrontierServerCache cache = FrontierServerCache.get();
        String dim = player.level().dimension().location().toString();
        TerritoryZone zone = cache.getZoneAt(dim, player.blockPosition());
        if (zone == null) {
            source.sendFailure(Component.literal("§c[Frontier-Admin] В текущей позиции нет зоны."));
            return 0;
        }
        cache.removeZone(zone.getId());
        FrontierNetwork.syncAllTerritoryData();
        source.sendSuccess(() -> Component.literal("§c[Frontier-Admin] Зона '" + zone.getName() + "' успешно удалена."), true);
        return 1;
    }

    private static int adminListZones(CommandSourceStack source) {
        FrontierServerCache cache = FrontierServerCache.get();
        Collection<TerritoryZone> all = cache.getAllZones();
        source.sendSuccess(() -> Component.literal("§6=== Все территории сервера (" + all.size() + ") ==="), false);
        for (TerritoryZone z : all) {
            String owner = z.getStateId() != null ? cache.getState(z.getStateId()).getName() : "Нейтральная";
            source.sendSuccess(() -> Component.literal(" §e• " + z.getName() + " §7(" + owner + ") [" + z.getMinX() + "," + z.getMinZ() + " → " + z.getMaxX() + "," + z.getMaxZ() + "] — §a" + z.getArea() + " м²"), false);
        }
        return 1;
    }
}
