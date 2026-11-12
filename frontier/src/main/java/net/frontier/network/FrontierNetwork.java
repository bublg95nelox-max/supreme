package net.frontier.network;

import net.frontier.api.CaseStatus;
import net.frontier.api.JurisdictionType;
import net.frontier.api.LawStatus;
import net.frontier.api.RelationType;
import net.frontier.api.Role;
import net.frontier.core.FrontierServerCache;
import net.frontier.data.model.*;
import net.frontier.network.payloads.*;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class FrontierNetwork {
    private static final Logger LOGGER = LoggerFactory.getLogger(FrontierNetwork.class);

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("frontier").versioned("1.0.1");

        if (FMLEnvironment.dist.isClient()) {
            net.frontier.client.network.ClientPayloadRegistrar.register(registrar);
        } else {
            registrar.playToClient(
                    SyncTerritoryDataPayload.TYPE,
                    SyncTerritoryDataPayload.STREAM_CODEC,
                    (p, c) -> {}
            );
            registrar.playToClient(
                    OpenFrontierScreenPayload.TYPE,
                    OpenFrontierScreenPayload.STREAM_CODEC,
                    (p, c) -> {}
            );
            registrar.playToClient(
                    SyncCasesAndLawsPayload.TYPE,
                    SyncCasesAndLawsPayload.STREAM_CODEC,
                    (p, c) -> {}
            );
            registrar.playToClient(
                    SyncAdminPetitionsPayload.TYPE,
                    SyncAdminPetitionsPayload.STREAM_CODEC,
                    (p, c) -> {}
            );
            registrar.playToClient(
                    SyncAuthLockPayload.TYPE,
                    SyncAuthLockPayload.STREAM_CODEC,
                    (p, c) -> {}
            );
        }

        registrar.playToServer(
                ClientRequestActionPayload.TYPE,
                ClientRequestActionPayload.STREAM_CODEC,
                FrontierNetwork::handleClientRequest
        );

        LOGGER.info("[Frontier] Registered CustomPacketPayload network handlers successfully.");
    }

    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    public static void sendToAll(CustomPacketPayload payload) {
        PacketDistributor.sendToAllPlayers(payload);
    }

    public static void syncPlayerData(ServerPlayer player) {
        FrontierServerCache cache = FrontierServerCache.get();
        if (cache == null) return;

        List<SyncTerritoryDataPayload.ClientStateData> statesList = new ArrayList<>();
        for (FrontierState s : cache.getAllStates()) {
            statesList.add(new SyncTerritoryDataPayload.ClientStateData(
                    s.getId(), s.getName(), s.getTag(), s.getColor(), s.getLeaderName(), s.getMotd()
            ));
        }

        String dim = player.level().dimension().location().toString();
        List<SyncTerritoryDataPayload.ClientClaimData> claimsList = new ArrayList<>();
        for (TerritoryChunk c : cache.getDimensionChunks(dim).values()) {
            claimsList.add(new SyncTerritoryDataPayload.ClientClaimData(
                    c.getChunkX(), c.getChunkZ(), c.getStateId(), c.getJurisdictionType()
            ));
        }

        List<SyncTerritoryDataPayload.ClientRelationData> relationsList = new ArrayList<>();
        for (FrontierState a : cache.getAllStates()) {
            for (FrontierState b : cache.getAllStates()) {
                if (a.getId().compareTo(b.getId()) < 0) {
                    RelationType rel = cache.getRelation(a.getId(), b.getId());
                    if (rel != RelationType.NEUTRAL) {
                        relationsList.add(new SyncTerritoryDataPayload.ClientRelationData(a.getId(), b.getId(), rel));
                    }
                }
            }
        }

        List<SyncTerritoryDataPayload.ClientVassalData> vassalsList = new ArrayList<>();
        for (VassalPact v : cache.getAllVassalPacts()) {
            vassalsList.add(new SyncTerritoryDataPayload.ClientVassalData(
                    v.getSuzerainId(), v.getVassalId(), v.getTitle(), v.getTributePercent(), v.getPledgedAt()
            ));
        }

        FrontierState playerState = cache.getPlayerState(player.getUUID());
        UUID playerStateId = playerState != null ? playerState.getId() : null;
        Role playerRole = null;
        if (playerStateId != null) {
            StateMember m = cache.getMember(playerStateId, player.getUUID());
            if (m != null) playerRole = m.getRole();
        }

        SyncTerritoryDataPayload payload = new SyncTerritoryDataPayload(
                statesList, claimsList, relationsList, vassalsList, playerStateId, playerRole
        );
        sendToPlayer(player, payload);

        if (player.hasPermissions(2)) {
            syncAdminPetitions(player);
        }
    }

    public static void syncAdminPetitions(ServerPlayer player) {
        FrontierServerCache cache = FrontierServerCache.get();
        if (cache == null) return;
        List<SyncAdminPetitionsPayload.AdminPetitionData> list = new ArrayList<>();
        for (StatePetition p : cache.getPetitions()) {
            list.add(new SyncAdminPetitionsPayload.AdminPetitionData(
                    p.getId(), p.getRequesterUuid(), p.getRequesterName(),
                    p.getStateName(), p.getStateTag(), p.getColor(),
                    p.getDimension(), p.getCapitalChunkX(), p.getCapitalChunkZ(),
                    p.getStatus(), p.getSubmittedAt()
            ));
        }
        sendToPlayer(player, new SyncAdminPetitionsPayload(list));
    }

    public static void broadcastAdminPetitions(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.hasPermissions(2)) {
                syncAdminPetitions(player);
            }
        }
    }

    public static void broadcastTerritoryUpdate(MinecraftServer server) {
        if (server == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            syncPlayerData(player);
        }
    }

    public static void syncAllTerritoryData() {
        MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            broadcastTerritoryUpdate(server);
        }
    }

    public static void sendCasesAndLaws(ServerPlayer player, UUID stateId) {
        FrontierServerCache cache = FrontierServerCache.get();
        if (cache == null || stateId == null) return;

        List<SyncCasesAndLawsPayload.ClientLawData> laws = new ArrayList<>();
        for (Law l : cache.getLaws(stateId)) {
            laws.add(new SyncCasesAndLawsPayload.ClientLawData(
                    l.getId(), l.getTitle(), l.getContent(), l.getPenalty(), l.getAuthorName(), l.getStatus()
            ));
        }

        List<SyncCasesAndLawsPayload.ClientCaseData> cases = new ArrayList<>();
        for (CourtCase c : cache.getCases(stateId)) {
            cases.add(new SyncCasesAndLawsPayload.ClientCaseData(
                    c.getId(), c.getTitle(), c.getAccusedName(), c.getPlaintiffName(), c.getJudgeName(), c.getStatus(), c.getVerdict()
            ));
        }

        sendToPlayer(player, new SyncCasesAndLawsPayload(stateId, laws, cases));
    }

    private static void handleClientRequest(ClientRequestActionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            FrontierServerCache cache = FrontierServerCache.get();
            if (cache == null) return;

            switch (payload.actionType()) {
                case ClientRequestActionPayload.ACTION_CREATE_PETITION -> {
                    String name = payload.strParam1().trim();
                    String tag = payload.strParam2().trim().toUpperCase(Locale.ROOT);
                    int color = payload.intParam1();
                    if (name.isEmpty() || tag.isEmpty() || tag.length() > 5) {
                        player.sendSystemMessage(Component.literal("§c[Frontier] Некорректное имя или тег (макс. 5 символов).").withStyle(ChatFormatting.RED));
                        return;
                    }
                    if (cache.getStateByName(name) != null || cache.getStateByTag(tag) != null) {
                        player.sendSystemMessage(Component.literal("§c[Frontier] Государство с таким именем или тегом уже существует!").withStyle(ChatFormatting.RED));
                        return;
                    }

                    int cx = player.blockPosition().getX() >> 4;
                    int cz = player.blockPosition().getZ() >> 4;
                    String dim = player.level().dimension().location().toString();

                    StatePetition petition = new StatePetition(
                            0, player.getUUID(), player.getGameProfile().getName(),
                            name, tag, color, dim, cx, cz, "PENDING", System.currentTimeMillis()
                    );
                    cache.addPetition(petition);
                    net.frontier.discord.FrontierDiscord.notifyPetitionCreated(petition);
                    player.sendSystemMessage(Component.literal("§a[Frontier] Петиция на создание государства «" + name + "» [" + tag + "] отправлена куратору сервера.")
                            .withStyle(ChatFormatting.GREEN));

                    // Broadcast alert to all online admins / operators!
                    for (ServerPlayer sp : player.server.getPlayerList().getPlayers()) {
                        if (sp.hasPermissions(2)) {
                            sp.sendSystemMessage(Component.literal("§6§l[Frontier-Куратор] §eИгрок §b" + player.getGameProfile().getName()
                                    + " §eподал заявку на создание государства «§6" + name + "§e» [" + tag + "]!"));
                            sp.sendSystemMessage(Component.literal("§7Откройте Кодекс (J) -> вкладка «Куратор» для рассмотрения в 1 клик."));
                        }
                    }
                    broadcastAdminPetitions(player.server);
                }

                case ClientRequestActionPayload.ACTION_APPROVE_PETITION -> {
                    if (!player.hasPermissions(2)) {
                        player.sendSystemMessage(Component.literal("§c[Frontier] Отказано в доступе: требуются права администратора.").withStyle(ChatFormatting.RED));
                        return;
                    }
                    int petId = payload.intParam1();
                    StatePetition pet = null;
                    for (StatePetition p : cache.getPetitions()) {
                        if (p.getId() == petId && "PENDING".equals(p.getStatus())) {
                            pet = p;
                            break;
                        }
                    }
                    if (pet == null) return;

                    pet.setStatus("APPROVED");
                    cache.updatePetition(pet);
                    net.frontier.discord.FrontierDiscord.notifyPetitionApproved(pet, player.getGameProfile().getName());

                    UUID stateId = UUID.randomUUID();
                    FrontierState state = new FrontierState(
                            stateId, pet.getStateName(), pet.getStateTag(), pet.getColor(),
                            pet.getRequesterUuid(), pet.getRequesterName(), System.currentTimeMillis(), "Да здравствует " + pet.getStateName() + "!"
                    );
                    StateMember leader = new StateMember(stateId, pet.getRequesterUuid(), pet.getRequesterName(), Role.LEADER, System.currentTimeMillis());
                    cache.registerState(state, leader);

                    // Auto-claim the capital territory region (3x3 chunks around capital)
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            TerritoryChunk capitalChunk = new TerritoryChunk(pet.getDimension(), pet.getCapitalChunkX() + dx, pet.getCapitalChunkZ() + dz, stateId, JurisdictionType.NATIONAL, player.getUUID(), System.currentTimeMillis());
                            cache.setClaim(capitalChunk);
                        }
                    }

                    player.sendSystemMessage(Component.literal("§a[Frontier-Admin] Государство «" + state.getName() + "» утверждено! Земли столичного региона закреплены.").withStyle(ChatFormatting.GREEN));
                    player.server.getPlayerList().broadcastSystemMessage(
                            Component.literal("§6§l[Frontier] На мировой арене провозглашено новое государство: §e" + state.getName() + " §7[" + state.getTag() + "]!"), false
                    );
                    broadcastTerritoryUpdate(player.server);
                    broadcastAdminPetitions(player.server);
                }

                case ClientRequestActionPayload.ACTION_REJECT_PETITION -> {
                    if (!player.hasPermissions(2)) return;
                    int petId = payload.intParam1();
                    for (StatePetition p : cache.getPetitions()) {
                        if (p.getId() == petId && "PENDING".equals(p.getStatus())) {
                            p.setStatus("REJECTED");
                            cache.updatePetition(p);
                            net.frontier.discord.FrontierDiscord.notifyPetitionRejected(p, player.getGameProfile().getName());
                            player.sendSystemMessage(Component.literal("§7[Frontier-Admin] Заявка «" + p.getStateName() + "» отклонена.").withStyle(ChatFormatting.GRAY));

                            ServerPlayer req = player.server.getPlayerList().getPlayer(p.getRequesterUuid());
                            if (req != null) {
                                req.sendSystemMessage(Component.literal("§c[Frontier] Ваша заявка на создание государства «" + p.getStateName() + "» была отклонена куратором.")
                                        .withStyle(ChatFormatting.RED));
                            }
                            broadcastAdminPetitions(player.server);
                            break;
                        }
                    }
                }

                case ClientRequestActionPayload.ACTION_ADMIN_ASSIGN_CHUNK -> {
                    if (!player.hasPermissions(2)) return;
                    int cx = payload.intParam1();
                    int cz = payload.intParam2();
                    String dim = player.level().dimension().location().toString();
                    UUID targetStateId = payload.uuidParam1();

                    if (targetStateId == null) {
                        // Declare neutral zone
                        TerritoryChunk neutral = new TerritoryChunk(dim, cx, cz, null, JurisdictionType.NEUTRAL, player.getUUID(), System.currentTimeMillis());
                        cache.setClaim(neutral);
                        player.sendSystemMessage(Component.literal("§b[Frontier-Admin] Чанк [" + cx + ", " + cz + "] объявлен Нейтральной зоной.").withStyle(ChatFormatting.AQUA));
                    } else {
                        FrontierState target = cache.getState(targetStateId);
                        if (target != null) {
                            TerritoryChunk chunk = new TerritoryChunk(dim, cx, cz, targetStateId, JurisdictionType.NATIONAL, player.getUUID(), System.currentTimeMillis());
                            cache.setClaim(chunk);
                            player.sendSystemMessage(Component.literal("§a[Frontier-Admin] Чанк [" + cx + ", " + cz + "] передан государству «" + target.getName() + "».").withStyle(ChatFormatting.GREEN));
                        }
                    }
                    broadcastTerritoryUpdate(player.server);
                }

                case ClientRequestActionPayload.ACTION_CREATE_LAW -> {
                    FrontierState pState = cache.getPlayerState(player.getUUID());
                    if (pState == null) return;
                    StateMember m = cache.getMember(pState.getId(), player.getUUID());
                    if (m == null || !m.getRole().canManageLaws()) {
                        player.sendSystemMessage(Component.literal("§c[Frontier] У вас нет полномочий вносить законопроекты.").withStyle(ChatFormatting.RED));
                        return;
                    }
                    String title = payload.strParam1().trim();
                    String content = payload.strParam2().trim();
                    String penalty = payload.strParam3().trim();
                    if (title.isEmpty() || content.isEmpty()) return;

                    Law law = new Law(0, pState.getId(), title, content, penalty, player.getUUID(), player.getGameProfile().getName(), System.currentTimeMillis(), LawStatus.DRAFT);
                    cache.saveLaw(law);
                    player.sendSystemMessage(Component.literal("§a[Frontier] Законопроект «" + title + "» внесён на рассмотрение.").withStyle(ChatFormatting.GREEN));
                    sendCasesAndLaws(player, pState.getId());
                }

                case ClientRequestActionPayload.ACTION_ENACT_LAW -> {
                    FrontierState pState = cache.getPlayerState(player.getUUID());
                    if (pState == null) return;
                    StateMember m = cache.getMember(pState.getId(), player.getUUID());
                    if (m == null || m.getRole() != Role.LEADER) {
                        player.sendSystemMessage(Component.literal("§c[Frontier] Только лидер государства может утверждать законы.").withStyle(ChatFormatting.RED));
                        return;
                    }
                    int lawId = payload.intParam1();
                    for (Law law : cache.getLaws(pState.getId())) {
                        if (law.getId() == lawId) {
                            law.setStatus(LawStatus.ENACTED);
                            cache.saveLaw(law);
                            player.sendSystemMessage(Component.literal("§6[Frontier] Закон «" + law.getTitle() + "» официально вступил в силу!").withStyle(ChatFormatting.GOLD));
                            sendCasesAndLaws(player, pState.getId());
                            break;
                        }
                    }
                }

                case ClientRequestActionPayload.ACTION_REPEAL_LAW -> {
                    FrontierState pState = cache.getPlayerState(player.getUUID());
                    if (pState == null) return;
                    StateMember m = cache.getMember(pState.getId(), player.getUUID());
                    if (m == null || m.getRole() != Role.LEADER) return;
                    int lawId = payload.intParam1();
                    for (Law law : cache.getLaws(pState.getId())) {
                        if (law.getId() == lawId) {
                            law.setStatus(LawStatus.REPEALED);
                            cache.saveLaw(law);
                            player.sendSystemMessage(Component.literal("§7[Frontier] Закон «" + law.getTitle() + "» отменён.").withStyle(ChatFormatting.GRAY));
                            sendCasesAndLaws(player, pState.getId());
                            break;
                        }
                    }
                }

                case ClientRequestActionPayload.ACTION_SET_RELATION -> {
                    FrontierState pState = cache.getPlayerState(player.getUUID());
                    if (pState == null) return;
                    StateMember m = cache.getMember(pState.getId(), player.getUUID());
                    if (m == null || !m.getRole().canDeclareWarOrDiplomacy()) return;

                    UUID targetStateId = payload.uuidParam1();
                    FrontierState targetState = cache.getState(targetStateId);
                    if (targetState == null || targetStateId.equals(pState.getId())) return;

                    RelationType relType = RelationType.values()[Math.min(RelationType.values().length - 1, Math.max(0, payload.intParam1()))];
                    DiplomacyRelation rel = new DiplomacyRelation(pState.getId(), targetStateId, relType, player.getUUID(), System.currentTimeMillis(), payload.strParam1());
                    cache.setRelation(rel);

                    String msg = "§6[Дипломатия] " + pState.getName() + " установило отношения с " + targetState.getName() + ": §l" + relType.getLabel();
                    player.server.getPlayerList().broadcastSystemMessage(Component.literal(msg), false);
                    broadcastTerritoryUpdate(player.server);
                }

                case ClientRequestActionPayload.ACTION_CREATE_CASE -> {
                    FrontierState pState = cache.getPlayerState(player.getUUID());
                    if (pState == null) return;
                    StateMember m = cache.getMember(pState.getId(), player.getUUID());
                    if (m == null || !m.getRole().canInvestigate()) {
                        player.sendSystemMessage(Component.literal("§c[Frontier] Только судьи и дознаватели могут открывать судебные дела.").withStyle(ChatFormatting.RED));
                        return;
                    }
                    String title = payload.strParam1().trim();
                    String accusedName = payload.strParam2().trim();
                    UUID accusedUuid = payload.uuidParam1() != null ? payload.uuidParam1() : UUID.nameUUIDFromBytes(accusedName.getBytes());

                    CourtCase c = new CourtCase(0, pState.getId(), title, accusedUuid, accusedName, player.getUUID(), player.getGameProfile().getName(), player.getUUID(), player.getGameProfile().getName(), CaseStatus.INVESTIGATION, "", System.currentTimeMillis(), 0);
                    cache.saveCourtCase(c);
                    net.frontier.discord.FrontierDiscord.notifyCaseCreated(c);
                    player.sendSystemMessage(Component.literal("§e[Frontier] Возбуждено судебное дело №" + c.getId() + " против " + accusedName + ".").withStyle(ChatFormatting.YELLOW));
                    sendCasesAndLaws(player, pState.getId());
                }

                case ClientRequestActionPayload.ACTION_SET_VERDICT -> {
                    FrontierState pState = cache.getPlayerState(player.getUUID());
                    if (pState == null) return;
                    StateMember m = cache.getMember(pState.getId(), player.getUUID());
                    if (m == null || !m.getRole().canInvestigate()) return;

                    int caseId = payload.intParam1();
                    boolean guilty = payload.intParam2() == 1;
                    String verdictText = payload.strParam1();

                    for (CourtCase c : cache.getCases(pState.getId())) {
                        if (c.getId() == caseId) {
                            c.setStatus(guilty ? CaseStatus.GUILTY : CaseStatus.INNOCENT);
                            c.setVerdict(verdictText);
                            c.setResolvedAt(System.currentTimeMillis());
                            c.setJudgeUuid(player.getUUID());
                            c.setJudgeName(player.getGameProfile().getName());
                            cache.saveCourtCase(c);
                            net.frontier.discord.FrontierDiscord.notifyVerdict(c, guilty, verdictText, player.getGameProfile().getName());

                            if (guilty) {
                                String alert = "§c§l[РОЗЫСК] §eИгрок " + c.getAccusedName() + " объявлен вне закона на территории " + pState.getName() + "!";
                                player.server.getPlayerList().broadcastSystemMessage(Component.literal(alert), false);
                            } else {
                                player.sendSystemMessage(Component.literal("§a[Frontier] Обвиняемый " + c.getAccusedName() + " оправдан по делу #" + caseId + ".").withStyle(ChatFormatting.GREEN));
                            }
                            sendCasesAndLaws(player, pState.getId());
                            break;
                        }
                    }
                }

                case ClientRequestActionPayload.ACTION_REQUEST_CASES_LAWS -> {
                    UUID targetState = payload.uuidParam1();
                    if (targetState != null) {
                        sendCasesAndLaws(player, targetState);
                    }
                }

                case ClientRequestActionPayload.ACTION_CLAIM_CHUNK -> {
                    // Only admins can claim/assign chunks. Normal players use surveyor tool for info.
                    if (!player.hasPermissions(2)) {
                        player.sendSystemMessage(Component.literal("§c[Frontier] Только администратор может назначать территории.").withStyle(ChatFormatting.RED));
                        return;
                    }
                    int cx = payload.intParam1();
                    int cz = payload.intParam2();
                    String dim = player.level().dimension().location().toString();
                    UUID targetStateId = payload.uuidParam1();

                    if (targetStateId == null) {
                        // Declare neutral zone
                        TerritoryChunk neutral = new TerritoryChunk(dim, cx, cz, null, JurisdictionType.NEUTRAL, player.getUUID(), System.currentTimeMillis());
                        cache.setClaim(neutral);
                        player.sendSystemMessage(Component.literal("§b[Frontier-Admin] Чанк [" + cx + ", " + cz + "] объявлен Нейтральной зоной.").withStyle(ChatFormatting.AQUA));
                    } else {
                        FrontierState target = cache.getState(targetStateId);
                        if (target != null) {
                            TerritoryChunk chunk = new TerritoryChunk(dim, cx, cz, targetStateId, JurisdictionType.NATIONAL, player.getUUID(), System.currentTimeMillis());
                            cache.setClaim(chunk);
                            player.sendSystemMessage(Component.literal("§a[Frontier-Admin] Чанк [" + cx + ", " + cz + "] передан государству «" + target.getName() + "».").withStyle(ChatFormatting.GREEN));
                        }
                    }
                    broadcastTerritoryUpdate(player.server);
                }

                case ClientRequestActionPayload.ACTION_UNCLAIM_CHUNK -> {
                    // Only admins can unclaim chunks
                    if (!player.hasPermissions(2)) {
                        player.sendSystemMessage(Component.literal("§c[Frontier] Только администратор может снимать территории.").withStyle(ChatFormatting.RED));
                        return;
                    }
                    int cx = payload.intParam1();
                    int cz = payload.intParam2();
                    String dim = player.level().dimension().location().toString();

                    TerritoryChunk existing = cache.getChunk(dim, cx, cz);
                    if (existing == null) {
                        player.sendSystemMessage(Component.literal("§c[Frontier] Чанк не принадлежит ни одному государству.").withStyle(ChatFormatting.RED));
                        return;
                    }

                    cache.unclaim(dim, cx, cz);
                    player.sendSystemMessage(Component.literal("§7[Frontier-Admin] Чанк [" + cx + ", " + cz + "] исключён из юрисдикции.").withStyle(ChatFormatting.GRAY));
                    broadcastTerritoryUpdate(player.server);
                }

                case ClientRequestActionPayload.ACTION_PLEDGE_VASSALAGE -> {
                    FrontierState pState = cache.getPlayerState(player.getUUID());
                    if (pState == null) return;
                    StateMember m = cache.getMember(pState.getId(), player.getUUID());
                    if (m == null || m.getRole() != Role.LEADER) {
                        player.sendSystemMessage(Component.literal("§c[Frontier] Только лидер государства может приносить вассальную присягу.").withStyle(ChatFormatting.RED));
                        return;
                    }
                    UUID targetSuzerainId = payload.uuidParam1();
                    FrontierState suzerain = cache.getState(targetSuzerainId);
                    if (suzerain == null || targetSuzerainId.equals(pState.getId())) return;

                    // Feudal loop prevention
                    if (cache.isDirectVassal(targetSuzerainId, pState.getId())) {
                        player.sendSystemMessage(Component.literal("§c[Frontier] Нельзя присягнуть собственному вассалу! («Вассал моего вассала — не мой вассал»).").withStyle(ChatFormatting.RED));
                        return;
                    }

                    String title = payload.strParam1().trim();
                    if (title.isEmpty()) title = "Вассалитет";
                    int tribute = Math.max(0, Math.min(50, payload.intParam1()));

                    cache.setVassalage(targetSuzerainId, pState.getId(), title, tribute);
                    // Automatic alliance with suzerain
                    cache.setRelation(new DiplomacyRelation(targetSuzerainId, pState.getId(), RelationType.ALLIANCE, player.getUUID(), System.currentTimeMillis(), "Феодальный оммаж"));

                    String oathMsg = "§6§l[ФЕОДАЛЬНЫЙ ОММАЖ] §eГосударство «" + pState.getName() + "» принесло присягу верности Сюзерену «" + suzerain.getName() + "»!";
                    player.server.getPlayerList().broadcastSystemMessage(Component.literal(oathMsg), false);
                    broadcastTerritoryUpdate(player.server);
                }

                case ClientRequestActionPayload.ACTION_REBEL_VASSALAGE -> {
                    FrontierState pState = cache.getPlayerState(player.getUUID());
                    if (pState == null) return;
                    StateMember m = cache.getMember(pState.getId(), player.getUUID());
                    if (m == null || m.getRole() != Role.LEADER) return;

                    VassalPact pact = cache.getSuzerainPact(pState.getId());
                    if (pact == null) return;

                    FrontierState suzerain = cache.getState(pact.getSuzerainId());
                    String suzerainName = suzerain != null ? suzerain.getName() : "бывший сюзерен";

                    cache.breakVassalage(pState.getId());
                    // Rebellion initiates war of independence
                    cache.setRelation(new DiplomacyRelation(pact.getSuzerainId(), pState.getId(), RelationType.WAR, player.getUUID(), System.currentTimeMillis(), "Восстание за независимость"));

                    String rebelMsg = "§4§l[ВОССТАНИЕ ВАССАЛА] §cГосударство «" + pState.getName() + "» разорвало вассальную клятву и объявило войну за независимость против «" + suzerainName + "»!";
                    player.server.getPlayerList().broadcastSystemMessage(Component.literal(rebelMsg), false);
                    broadcastTerritoryUpdate(player.server);
                }

                case ClientRequestActionPayload.ACTION_RELEASE_VASSAL -> {
                    FrontierState pState = cache.getPlayerState(player.getUUID());
                    if (pState == null) return;
                    StateMember m = cache.getMember(pState.getId(), player.getUUID());
                    if (m == null || m.getRole() != Role.LEADER) return;

                    UUID vassalId = payload.uuidParam1();
                    VassalPact pact = cache.getSuzerainPact(vassalId);
                    if (pact == null || !pState.getId().equals(pact.getSuzerainId())) return;

                    FrontierState vassal = cache.getState(vassalId);
                    String vassalName = vassal != null ? vassal.getName() : "Вассал";

                    cache.breakVassalage(vassalId);
                    cache.setRelation(new DiplomacyRelation(pState.getId(), vassalId, RelationType.NON_AGGRESSION, player.getUUID(), System.currentTimeMillis(), "Вольная грамота"));

                    String releaseMsg = "§a§l[ВОЛЬНАЯ ГРАМОТА] §2Сюзерен «" + pState.getName() + "» даровал полную независимость государству «" + vassalName + "»!";
                    player.server.getPlayerList().broadcastSystemMessage(Component.literal(releaseMsg), false);
                    broadcastTerritoryUpdate(player.server);
                }
            }
        });
    }
}
