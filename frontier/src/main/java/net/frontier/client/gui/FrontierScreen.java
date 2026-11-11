package net.frontier.client.gui;

import net.frontier.api.JurisdictionType;
import net.frontier.api.RelationType;
import net.frontier.api.Role;
import net.frontier.client.FrontierClientCache;
import net.frontier.client.compat.XaeroMapCompat;
import net.frontier.network.payloads.ClientRequestActionPayload;
import net.frontier.network.payloads.SyncAdminPetitionsPayload;
import net.frontier.network.payloads.SyncCasesAndLawsPayload;
import net.frontier.network.payloads.SyncTerritoryDataPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;

public class FrontierScreen extends Screen {
    private int currentTab;
    private final UUID playerStateId;
    private final Role playerRole;
    private final boolean isAdmin;

    // Sub-UI state
    private boolean isCreatingPetition = false;
    private EditBox petitionNameBox;
    private EditBox petitionTagBox;

    private boolean isCreatingLaw = false;
    private EditBox lawTitleBox;
    private EditBox lawContentBox;
    private EditBox lawPenaltyBox;

    private boolean isCreatingCase = false;
    private EditBox caseTitleBox;
    private EditBox caseAccusedBox;

    // Vassal Sub-UI state
    private boolean isPledgingVassalage = false;
    private EditBox vassalSuzerainTagBox;
    private EditBox vassalTitleBox;
    private EditBox vassalTributeBox;

    // Map Pan/Zoom (smooth sub-pixel)
    private double mapCenterX;
    private double mapCenterZ;
    private int tileSize = 20; // 12 to 44 px per chunk
    private boolean isDraggingMap = false;
    private double lastDragMouseX;
    private double lastDragMouseY;

    // Selected chunk
    private int selectedChunkX;
    private int selectedChunkZ;
    private boolean hasSelectedChunk = false;

    // Map viewport bounds
    private int mapViewportX;
    private int mapViewportY;
    private int mapViewportW;
    private int mapViewportH;

    public FrontierScreen(int defaultTab) {
        super(Component.literal("Кодекс Фронтира"));
        this.currentTab = defaultTab;
        FrontierClientCache cache = FrontierClientCache.get();
        this.playerStateId = cache.getPlayerStateId();
        this.playerRole = cache.getPlayerRole();
        this.isAdmin = Minecraft.getInstance().player != null && Minecraft.getInstance().player.hasPermissions(2);

        if (Minecraft.getInstance().player != null) {
            int px = Minecraft.getInstance().player.blockPosition().getX() >> 4;
            int pz = Minecraft.getInstance().player.blockPosition().getZ() >> 4;
            this.mapCenterX = px;
            this.mapCenterZ = pz;
            this.selectedChunkX = px;
            this.selectedChunkZ = pz;
            this.hasSelectedChunk = false;
        }

        // Request laws and cases for player's state
        if (playerStateId != null) {
            PacketDistributor.sendToServer(new ClientRequestActionPayload(
                    ClientRequestActionPayload.ACTION_REQUEST_CASES_LAWS, 0, 0, "", "", "", playerStateId
            ));
        }
    }

    @Override
    protected void init() {
        this.clearWidgets();
        int totalTabs = isAdmin ? 7 : 6;
        int tabW = Math.min(90, (this.width - 20 - (totalTabs - 1) * 2) / totalTabs);
        int tabY = 25;
        int startX = (this.width - (tabW * totalTabs + (totalTabs - 1) * 2)) / 2;

        addRenderableWidget(Button.builder(Component.literal("Карта"), b -> setTab(0))
                .bounds(startX, tabY, tabW, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Государство"), b -> setTab(1))
                .bounds(startX + (tabW + 2), tabY, tabW, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Законы"), b -> setTab(2))
                .bounds(startX + (tabW + 2) * 2, tabY, tabW, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Дипломатия"), b -> setTab(3))
                .bounds(startX + (tabW + 2) * 3, tabY, tabW, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Суд"), b -> setTab(4))
                .bounds(startX + (tabW + 2) * 4, tabY, tabW, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Вассалы"), b -> setTab(5))
                .bounds(startX + (tabW + 2) * 5, tabY, tabW, 20).build());

        if (isAdmin) {
            addRenderableWidget(Button.builder(Component.literal("Куратор"), b -> setTab(6))
                    .bounds(startX + (tabW + 2) * 6, tabY, tabW, 20).build());
        }

        initTabWidgets();
    }

    private void setTab(int tab) {
        this.currentTab = tab;
        this.isCreatingPetition = false;
        this.isCreatingLaw = false;
        this.isCreatingCase = false;
        this.isPledgingVassalage = false;
        init();
    }

    private void initTabWidgets() {
        int contentX = 20;
        int contentY = 52;
        int contentW = this.width - 40;
        int contentH = this.height - 62;
        FrontierClientCache cache = FrontierClientCache.get();

        switch (currentTab) {
            case 0 -> { // Map Controls
                // Open Xaero's World Map button if Xaero is loaded
                if (XaeroMapCompat.isXaeroLoaded()) {
                    addRenderableWidget(Button.builder(Component.literal("🗺 Карта Xaero (M)"), b -> {
                        XaeroMapCompat.openXaeroWorldMap(this);
                    }).bounds(contentX + 90, contentY + 6, 120, 18).build());
                }

                // Center on Player button
                addRenderableWidget(Button.builder(Component.literal("⌖ Моя позиция"), b -> {
                    if (minecraft != null && minecraft.player != null) {
                        mapCenterX = minecraft.player.blockPosition().getX() >> 4;
                        mapCenterZ = minecraft.player.blockPosition().getZ() >> 4;
                    }
                }).bounds(contentX + contentW - 220, contentY + 6, 95, 18).build());

                // Zoom buttons
                addRenderableWidget(Button.builder(Component.literal("+"), b -> {
                    tileSize = Math.min(44, tileSize + 4);
                }).bounds(contentX + contentW - 120, contentY + 6, 20, 18).build());

                addRenderableWidget(Button.builder(Component.literal("-"), b -> {
                    tileSize = Math.max(12, tileSize - 4);
                }).bounds(contentX + contentW - 96, contentY + 6, 20, 18).build());

                // Admin territory action buttons on map
                if (isAdmin) {
                    addRenderableWidget(Button.builder(Component.literal("+ Буферная зона"), b -> {
                        if (!hasSelectedChunk) return;
                        PacketDistributor.sendToServer(new ClientRequestActionPayload(
                                ClientRequestActionPayload.ACTION_ADMIN_ASSIGN_CHUNK, selectedChunkX, selectedChunkZ, "", "", "", null
                        ));
                    }).bounds(contentX + contentW - 270, contentY + contentH - 24, 130, 20).build());

                    addRenderableWidget(Button.builder(Component.literal("✖ Освободить в Дикие"), b -> {
                        if (!hasSelectedChunk) return;
                        PacketDistributor.sendToServer(new ClientRequestActionPayload(
                                ClientRequestActionPayload.ACTION_UNCLAIM_CHUNK, selectedChunkX, selectedChunkZ, "", "", "", null
                        ));
                    }).bounds(contentX + contentW - 135, contentY + contentH - 24, 130, 20).build());
                }
            }

            case 1 -> { // State
                if (playerStateId == null) {
                    if (!isCreatingPetition) {
                        addRenderableWidget(Button.builder(Component.literal("Подать заявку на создание государства"), b -> {
                            isCreatingPetition = true;
                            init();
                        }).bounds(this.width / 2 - 120, contentY + 80, 240, 24).build());
                    } else {
                        petitionNameBox = new EditBox(font, this.width / 2 - 110, contentY + 50, 220, 20, Component.literal("Название"));
                        petitionNameBox.setMaxLength(32);
                        petitionNameBox.setHint(Component.literal("Пример: Республика Север"));
                        addRenderableWidget(petitionNameBox);

                        petitionTagBox = new EditBox(font, this.width / 2 - 110, contentY + 85, 220, 20, Component.literal("Тег флага"));
                        petitionTagBox.setMaxLength(5);
                        petitionTagBox.setHint(Component.literal("Пример: SEV (3-5 букв)"));
                        addRenderableWidget(petitionTagBox);

                        addRenderableWidget(Button.builder(Component.literal("Отправить заявку"), b -> {
                            String name = petitionNameBox.getValue().trim();
                            String tag = petitionTagBox.getValue().trim();
                            int color = 0x3388EE;
                            if (!name.isEmpty() && !tag.isEmpty()) {
                                PacketDistributor.sendToServer(new ClientRequestActionPayload(
                                        ClientRequestActionPayload.ACTION_CREATE_PETITION, color, 0, name, tag, "", null
                                ));
                            }
                            isCreatingPetition = false;
                            init();
                        }).bounds(this.width / 2 - 110, contentY + 120, 105, 20).build());

                        addRenderableWidget(Button.builder(Component.literal("Отмена"), b -> {
                            isCreatingPetition = false;
                            init();
                        }).bounds(this.width / 2 + 5, contentY + 120, 105, 20).build());
                    }
                }
            }

            case 2 -> { // Laws
                if (playerStateId != null && playerRole != null && playerRole.canManageLaws()) {
                    if (!isCreatingLaw) {
                        addRenderableWidget(Button.builder(Component.literal("+ Внести закон"), b -> {
                            isCreatingLaw = true;
                            init();
                        }).bounds(contentX + contentW - 120, contentY + 4, 110, 20).build());
                    } else {
                        lawTitleBox = new EditBox(font, contentX + 15, contentY + 30, 260, 20, Component.literal("Заголовок"));
                        lawTitleBox.setHint(Component.literal("Пример: Запрет несанкционированных взрывов"));
                        addRenderableWidget(lawTitleBox);

                        lawContentBox = new EditBox(font, contentX + 15, contentY + 65, 340, 20, Component.literal("Суть"));
                        lawContentBox.setHint(Component.literal("Пример: Запрещено взрывать динамит в черте города"));
                        addRenderableWidget(lawContentBox);

                        lawPenaltyBox = new EditBox(font, contentX + 15, contentY + 100, 340, 20, Component.literal("Наказание"));
                        lawPenaltyBox.setHint(Component.literal("Пример: Штраф или объявление вне закона"));
                        addRenderableWidget(lawPenaltyBox);

                        addRenderableWidget(Button.builder(Component.literal("Опубликовать закон"), b -> {
                            String t = lawTitleBox.getValue().trim();
                            String c = lawContentBox.getValue().trim();
                            String p = lawPenaltyBox.getValue().trim();
                            if (!t.isEmpty() && !c.isEmpty()) {
                                PacketDistributor.sendToServer(new ClientRequestActionPayload(
                                        ClientRequestActionPayload.ACTION_CREATE_LAW, 0, 0, t, c, p, null
                                ));
                            }
                            isCreatingLaw = false;
                            init();
                        }).bounds(contentX + 15, contentY + 130, 130, 20).build());

                        addRenderableWidget(Button.builder(Component.literal("Отмена"), b -> {
                            isCreatingLaw = false;
                            init();
                        }).bounds(contentX + 155, contentY + 130, 80, 20).build());
                    }
                }
            }

            case 4 -> { // Court
                if (playerStateId != null && playerRole != null && playerRole.canInvestigate()) {
                    if (!isCreatingCase) {
                        addRenderableWidget(Button.builder(Component.literal("+ Возбудить дело"), b -> {
                            isCreatingCase = true;
                            init();
                        }).bounds(contentX + contentW - 130, contentY + 4, 120, 20).build());
                    } else {
                        caseTitleBox = new EditBox(font, contentX + 15, contentY + 30, 260, 20, Component.literal("Дело"));
                        caseTitleBox.setHint(Component.literal("Пример: Ограбление склада ратуши"));
                        addRenderableWidget(caseTitleBox);

                        caseAccusedBox = new EditBox(font, contentX + 15, contentY + 65, 220, 20, Component.literal("Ник"));
                        caseAccusedBox.setHint(Component.literal("Пример: Steve (точный ник)"));
                        addRenderableWidget(caseAccusedBox);

                        addRenderableWidget(Button.builder(Component.literal("Открыть производство"), b -> {
                            String t = caseTitleBox.getValue().trim();
                            String acc = caseAccusedBox.getValue().trim();
                            if (!t.isEmpty() && !acc.isEmpty()) {
                                PacketDistributor.sendToServer(new ClientRequestActionPayload(
                                        ClientRequestActionPayload.ACTION_CREATE_CASE, 0, 0, t, acc, "", null
                                ));
                            }
                            isCreatingCase = false;
                            init();
                        }).bounds(contentX + 15, contentY + 100, 140, 20).build());

                        addRenderableWidget(Button.builder(Component.literal("Отмена"), b -> {
                            isCreatingCase = false;
                            init();
                        }).bounds(contentX + 165, contentY + 100, 80, 20).build());
                    }
                }
            }

            case 5 -> { // Vassals
                if (playerStateId != null && playerRole == Role.LEADER) {
                    SyncTerritoryDataPayload.ClientVassalData mySuzerain = cache.getSuzerainPact(playerStateId);
                    if (mySuzerain != null) {
                        addRenderableWidget(Button.builder(Component.literal("⚔ Объявить восстание (Независимость)"), b -> {
                            PacketDistributor.sendToServer(new ClientRequestActionPayload(
                                    ClientRequestActionPayload.ACTION_REBEL_VASSALAGE, 0, 0, "", "", "", null
                            ));
                        }).bounds(contentX + 15, contentY + 115, 210, 20).build());
                    } else {
                        if (!isPledgingVassalage) {
                            addRenderableWidget(Button.builder(Component.literal("+ Принести присягу Сюзерену"), b -> {
                                isPledgingVassalage = true;
                                init();
                            }).bounds(contentX + 15, contentY + 115, 200, 20).build());
                        } else {
                            vassalSuzerainTagBox = new EditBox(font, contentX + 15, contentY + 55, 90, 20, Component.literal("Тег"));
                            vassalSuzerainTagBox.setMaxLength(5);
                            vassalSuzerainTagBox.setHint(Component.literal("Тег сюзерена"));
                            addRenderableWidget(vassalSuzerainTagBox);

                            vassalTitleBox = new EditBox(font, contentX + 110, contentY + 55, 110, 20, Component.literal("Титул"));
                            vassalTitleBox.setValue("Герцогство");
                            addRenderableWidget(vassalTitleBox);

                            vassalTributeBox = new EditBox(font, contentX + 15, contentY + 80, 70, 20, Component.literal("Дань %"));
                            vassalTributeBox.setValue("10");
                            addRenderableWidget(vassalTributeBox);

                            addRenderableWidget(Button.builder(Component.literal("Присягнуть"), b -> {
                                String sTag = vassalSuzerainTagBox.getValue().trim();
                                String title = vassalTitleBox.getValue().trim();
                                int tribute = 10;
                                try { tribute = Integer.parseInt(vassalTributeBox.getValue().trim()); } catch (Exception ignored) {}
                                SyncTerritoryDataPayload.ClientStateData target = null;
                                for (SyncTerritoryDataPayload.ClientStateData st : cache.getAllStates()) {
                                    if (st.tag().equalsIgnoreCase(sTag)) {
                                        target = st;
                                        break;
                                    }
                                }
                                if (target != null) {
                                    PacketDistributor.sendToServer(new ClientRequestActionPayload(
                                            ClientRequestActionPayload.ACTION_PLEDGE_VASSALAGE, tribute, 0, title, "", "", target.id()
                                    ));
                                }
                                isPledgingVassalage = false;
                                init();
                            }).bounds(contentX + 90, contentY + 80, 75, 20).build());

                            addRenderableWidget(Button.builder(Component.literal("Отмена"), b -> {
                                isPledgingVassalage = false;
                                init();
                            }).bounds(contentX + 170, contentY + 80, 55, 20).build());
                        }
                    }

                    // Release buttons for direct vassals
                    List<SyncTerritoryDataPayload.ClientVassalData> directVassals = cache.getDirectVassals(playerStateId);
                    int btnY = contentY + 68;
                    for (SyncTerritoryDataPayload.ClientVassalData v : directVassals) {
                        if (btnY > contentY + contentH - 30) break;
                        final UUID vId = v.vassalId();
                        addRenderableWidget(Button.builder(Component.literal("Даровать вольную"), b -> {
                            PacketDistributor.sendToServer(new ClientRequestActionPayload(
                                    ClientRequestActionPayload.ACTION_RELEASE_VASSAL, 0, 0, "", "", "", vId
                            ));
                        }).bounds(contentX + contentW - 125, btnY - 2, 115, 18).build());
                        btnY += 34;
                    }
                }
            }

            case 6 -> { // Admin / Curator Panel
                if (isAdmin) {
                    List<SyncAdminPetitionsPayload.AdminPetitionData> petitions = cache.getAdminPetitions();
                    int petY = contentY + 44;
                    for (SyncAdminPetitionsPayload.AdminPetitionData p : petitions) {
                        if (!"PENDING".equals(p.status())) continue;
                        if (petY > contentY + contentH - 45) break;

                        final int pId = p.id();
                        // 1-Click Approve
                        addRenderableWidget(Button.builder(Component.literal("✔ Утвердить государство"), b -> {
                            PacketDistributor.sendToServer(new ClientRequestActionPayload(
                                    ClientRequestActionPayload.ACTION_APPROVE_PETITION, pId, 0, "", "", "", null
                            ));
                        }).bounds(contentX + contentW - 240, petY, 140, 20).build());

                        // 1-Click Reject
                        addRenderableWidget(Button.builder(Component.literal("✖ Отклонить"), b -> {
                            PacketDistributor.sendToServer(new ClientRequestActionPayload(
                                    ClientRequestActionPayload.ACTION_REJECT_PETITION, pId, 0, "", "", "", null
                            ));
                        }).bounds(contentX + contentW - 95, petY, 80, 20).build());

                        petY += 36;
                    }
                }
            }
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fillGradient(0, 0, this.width, this.height, 0xF5101014, 0xFA14141A);
        graphics.fill(0, 0, this.width, 2, 0xFFFFAA00);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 1. Draw clean sharp background (NO blur pass!)
        this.renderBackground(graphics, mouseX, mouseY, partialTick);

        // 2. Main content container
        int contentX = 20;
        int contentY = 52;
        int contentW = this.width - 40;
        int contentH = this.height - 62;
        graphics.fill(contentX, contentY, contentX + contentW, contentY + contentH, 0xD81B1C22);
        graphics.fill(contentX + 1, contentY + 1, contentX + contentW - 1, contentY + 25, 0xFF22242C);
        graphics.fill(contentX + 10, contentY + 26, contentX + contentW - 10, contentY + 27, 0xFF3A3E4A);
        graphics.renderOutline(contentX, contentY, contentW, contentH, 0xFF4A4E5C);
        graphics.drawString(font, "Кодекс Фронтира", contentX, 9, 0xFFFFCC80, true);

        // 3. Render current tab content
        switch (currentTab) {
            case 0 -> renderMapTab(graphics, contentX, contentY, contentW, contentH, mouseX, mouseY);
            case 1 -> renderStateTab(graphics, contentX, contentY, contentW, contentH);
            case 2 -> renderLawsTab(graphics, contentX, contentY, contentW, contentH);
            case 3 -> renderDiplomacyTab(graphics, contentX, contentY, contentW, contentH);
            case 4 -> renderCourtTab(graphics, contentX, contentY, contentW, contentH);
            case 5 -> renderVassalsTab(graphics, contentX, contentY, contentW, contentH);
            case 6 -> renderAdminTab(graphics, contentX, contentY, contentW, contentH);
        }

        // 4. Render Minecraft standard widgets CRISP on top
        for (Renderable r : this.renderables) {
            r.render(graphics, mouseX, mouseY, partialTick);
        }

        // 5. Tooltips
        if (currentTab == 0) {
            renderMapTooltip(graphics, mouseX, mouseY);
        }
    }

    /**
     * Highly optimized zero-lag map renderer:
     * Fills the background as wild land in one operation, and only renders the claimed chunks.
     */
    private void renderMapTab(GuiGraphics g, int x, int y, int w, int h, int mouseX, int mouseY) {
        FrontierClientCache cache = FrontierClientCache.get();

        g.drawString(font, "§6Территории", x + 10, y + 8, 0xFFFFFFFF, true);
        if (XaeroMapCompat.isXaeroLoaded()) {
            g.drawString(font, "§a✓ Интегрировано с картой Xaero (клавиша M)", x + 215, y + 10, 0xFF88FF88, true);
        }
        g.drawString(font, "§7" + (tileSize * 100 / 20) + "%", x + w - 65, y + 10, 0xFFCCCCCC, true);

        int mapX = x + 8;
        int mapY = y + 26;
        int mapW = w - 16;
        int mapH = h - 56;

        this.mapViewportX = mapX;
        this.mapViewportY = mapY;
        this.mapViewportW = mapW;
        this.mapViewportH = mapH;

        // Viewport background: single fill representing wild lands (Zero-Lag!)
        g.fill(mapX, mapY, mapX + mapW, mapY + mapH, 0xFF181A20);
        g.renderOutline(mapX, mapY, mapW, mapH, 0xFF3A3E4A);

        g.enableScissor(mapX + 1, mapY + 1, mapX + mapW - 1, mapY + mapH - 1);

        int midX = mapX + mapW / 2;
        int midY = mapY + mapH / 2;

        int playerCx = (minecraft != null && minecraft.player != null) ? (minecraft.player.blockPosition().getX() >> 4) : 0;
        int playerCz = (minecraft != null && minecraft.player != null) ? (minecraft.player.blockPosition().getZ() >> 4) : 0;


        // Render ONLY claimed chunks from cache (Fast $O(N)$ where $N$ is small, completely eliminating zoom lag!)
        for (SyncTerritoryDataPayload.ClientClaimData claim : cache.getAllClaims().values()) {
            int tileX = (int) (midX + (claim.chunkX() - mapCenterX) * tileSize);
            int tileY = (int) (midY + (claim.chunkZ() - mapCenterZ) * tileSize);

            if (tileX + tileSize < mapX || tileX > mapX + mapW || tileY + tileSize < mapY || tileY > mapY + mapH) {
                continue;
            }

            int color;
            if (claim.jurisdictionType() == JurisdictionType.NEUTRAL) {
                color = 0xFF2864A8;
            } else if (claim.stateId() != null) {
                SyncTerritoryDataPayload.ClientStateData st = cache.getState(claim.stateId());
                color = st != null ? (0xFF000000 | st.color()) : 0xFF35A035;
            } else {
                color = 0xFF24262C;
            }

            g.fill(tileX, tileY, tileX + tileSize, tileY + tileSize, color);
            if (!sameTerritory(claim, cache.getClaim(claim.chunkX() - 1, claim.chunkZ())))
                g.fill(tileX, tileY, tileX + 1, tileY + tileSize, 0xFFDFD5B8);
            if (!sameTerritory(claim, cache.getClaim(claim.chunkX() + 1, claim.chunkZ())))
                g.fill(tileX + tileSize - 1, tileY, tileX + tileSize, tileY + tileSize, 0xFFDFD5B8);
            if (!sameTerritory(claim, cache.getClaim(claim.chunkX(), claim.chunkZ() - 1)))
                g.fill(tileX, tileY, tileX + tileSize, tileY + 1, 0xFFDFD5B8);
            if (!sameTerritory(claim, cache.getClaim(claim.chunkX(), claim.chunkZ() + 1)))
                g.fill(tileX, tileY + tileSize - 1, tileX + tileSize, tileY + tileSize, 0xFFDFD5B8);
        }

        // Selected chunk outline
        if (isAdmin && hasSelectedChunk) {
            int selTileX = (int) (midX + (selectedChunkX - mapCenterX) * tileSize);
            int selTileY = (int) (midY + (selectedChunkZ - mapCenterZ) * tileSize);
            g.renderOutline(selTileX - 1, selTileY - 1, tileSize + 2, tileSize + 2, 0xFFFFAA00);
        }

        // Player marker
        int pTileX = (int) (midX + (playerCx - mapCenterX) * tileSize);
        int pTileY = (int) (midY + (playerCz - mapCenterZ) * tileSize);
        if (pTileX + tileSize >= mapX && pTileX <= mapX + mapW && pTileY + tileSize >= mapY && pTileY <= mapY + mapH) {
            int pDotSize = Math.max(4, tileSize / 4);
            int pDotX = pTileX + (tileSize - pDotSize) / 2;
            int pDotY = pTileY + (tileSize - pDotSize) / 2;
            g.fill(pDotX, pDotY, pDotX + pDotSize, pDotY + pDotSize, 0xFFFFFF00);
            g.renderOutline(pDotX - 1, pDotY - 1, pDotSize + 2, pDotSize + 2, 0xFF000000);
        }

        g.disableScissor();

        // Bottom Selected Chunk Action & Status Bar
        int bottomBarY = y + h - 26;
        g.fill(x + 8, bottomBarY, x + w - 8, bottomBarY + 24, 0x90181A20);
        g.renderOutline(x + 8, bottomBarY, w - 16, 24, 0xFF3A3E4A);

        if (hasSelectedChunk) {
            SyncTerritoryDataPayload.ClientClaimData selClaim = cache.getClaim(selectedChunkX, selectedChunkZ);
            String ownerDesc;
            if (selClaim == null || selClaim.stateId() == null) {
                JurisdictionType jt = selClaim != null ? selClaim.jurisdictionType() : JurisdictionType.WILD;
                ownerDesc = "§7" + jt.getLabel();
            } else {
                SyncTerritoryDataPayload.ClientStateData st = cache.getState(selClaim.stateId());
                String sName = st != null ? st.name() : "Государство";
                String sTag = st != null ? (" [" + st.tag() + "]") : "";
                ownerDesc = "§6" + sName + sTag;
            }

            g.drawString(font, font.plainSubstrByWidth("§e[" + selectedChunkX + ", " + selectedChunkZ + "] " + ownerDesc, Math.max(0, w - 300)), x + 16, bottomBarY + 8, 0xFFFFFFFF, true);
        } else {
            g.drawString(font, font.plainSubstrByWidth(isAdmin ? "§7Выберите чанк" : "§7ЛКМ — перемещение · Колесо — масштаб", Math.max(0, w - (isAdmin ? 300 : 32))), x + 16, bottomBarY + 8, 0xFFAAAAAA, true);
        }
    }

    private void renderMapTooltip(GuiGraphics g, int mouseX, int mouseY) {
        if (mouseX < mapViewportX || mouseX > mapViewportX + mapViewportW || mouseY < mapViewportY || mouseY > mapViewportY + mapViewportH) {
            return;
        }

        int midX = mapViewportX + mapViewportW / 2;
        int midY = mapViewportY + mapViewportH / 2;
        int hovCx = (int) Math.floor(mapCenterX + (double) (mouseX - midX) / tileSize);
        int hovCz = (int) Math.floor(mapCenterZ + (double) (mouseY - midY) / tileSize);

        FrontierClientCache cache = FrontierClientCache.get();
        SyncTerritoryDataPayload.ClientClaimData claim = cache.getClaim(hovCx, hovCz);

        List<Component> tooltip = new ArrayList<>();
        tooltip.add(Component.literal("§eЧанк [" + hovCx + ", " + hovCz + "]"));

        if (claim == null || claim.stateId() == null) {
            JurisdictionType type = claim != null ? claim.jurisdictionType() : JurisdictionType.WILD;
            tooltip.add(Component.literal("§7Юрисдикция: §f" + type.getLabel()));
        } else {
            SyncTerritoryDataPayload.ClientStateData st = cache.getState(claim.stateId());
            String stName = st != null ? st.name() : "Государство";
            String stTag = st != null ? st.tag() : "?";
            tooltip.add(Component.literal("§6" + stName + " §7[" + stTag + "]"));
            tooltip.add(Component.literal("§7Юрисдикция: §a" + claim.jurisdictionType().getLabel()));

            SyncTerritoryDataPayload.ClientVassalData vPact = cache.getSuzerainPact(claim.stateId());
            if (vPact != null) {
                SyncTerritoryDataPayload.ClientStateData suz = cache.getState(vPact.suzerainId());
                String suzName = suz != null ? suz.name() : "Сюзерен";
                tooltip.add(Component.literal("§e§oФеод: " + vPact.title() + " (Сюзерен: " + suzName + ")"));
            }

            if (playerStateId != null && !playerStateId.equals(claim.stateId())) {
                RelationType rel = cache.getRelation(playerStateId, claim.stateId());
                tooltip.add(Component.literal("§7Отношения: §f" + rel.getLabel()));
            }
        }
        if (isAdmin) tooltip.add(Component.literal("§8[Кликните для выбора]"));
        g.renderTooltip(font, tooltip, Optional.empty(), mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (currentTab == 0 && mouseX >= mapViewportX && mouseX <= mapViewportX + mapViewportW && mouseY >= mapViewportY && mouseY <= mapViewportY + mapViewportH) {
            if (button != 0) {
                return super.mouseClicked(mouseX, mouseY, button);
            }
            isDraggingMap = true;
            lastDragMouseX = mouseX;
            lastDragMouseY = mouseY;

            int midX = mapViewportX + mapViewportW / 2;
            int midY = mapViewportY + mapViewportH / 2;
            selectedChunkX = (int) Math.floor(mapCenterX + (double) (mouseX - midX) / tileSize);
            selectedChunkZ = (int) Math.floor(mapCenterZ + (double) (mouseY - midY) / tileSize);
            hasSelectedChunk = isAdmin;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (currentTab == 0 && isDraggingMap) {
            double dx = mouseX - lastDragMouseX;
            double dy = mouseY - lastDragMouseY;
            mapCenterX -= dx / (double) tileSize;
            mapCenterZ -= dy / (double) tileSize;
            lastDragMouseX = mouseX;
            lastDragMouseY = mouseY;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        isDraggingMap = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (currentTab == 0 && mouseX >= mapViewportX && mouseX <= mapViewportX + mapViewportW && mouseY >= mapViewportY && mouseY <= mapViewportY + mapViewportH && scrollY != 0) {
            if (scrollY > 0) {
                tileSize = Math.min(44, tileSize + 3);
            } else if (scrollY < 0) {
                tileSize = Math.max(12, tileSize - 3);
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void renderStateTab(GuiGraphics g, int x, int y, int w, int h) {
        FrontierClientCache cache = FrontierClientCache.get();
        if (playerStateId == null) {
            if (!isCreatingPetition) {
                g.drawCenteredString(font, "§cВы не состоите ни в одном государстве.", this.width / 2, y + 30, 0xFFFFFFFF);
                g.drawCenteredString(font, "§7Вы являетесь независимым исследователем Фронтира.", this.width / 2, y + 45, 0xFFAAAAAA);
                g.drawCenteredString(font, "§7Вы можете создать собственное объединение, подав заявку куратору.", this.width / 2, y + 60, 0xFFAAAAAA);
            } else {
                g.drawCenteredString(font, "§6Подача заявки на создание Государства", this.width / 2, y + 16, 0xFFFFAA00);
                g.drawString(font, "Название объединения (полное):", this.width / 2 - 110, y + 38, 0xFFCCCCCC, true);
                g.drawString(font, "Тег флага (от 3 до 5 заглавных букв):", this.width / 2 - 110, y + 73, 0xFFCCCCCC, true);
            }
            return;
        }

        SyncTerritoryDataPayload.ClientStateData st = cache.getState(playerStateId);
        if (st == null) return;

        g.drawString(font, "§6§l" + st.name() + " §7[" + st.tag() + "]", x + 20, y + 15, 0xFFFFFFFF, true);
        g.drawString(font, "§7Лидер: §f" + st.leaderName(), x + 20, y + 30, 0xFFCCCCCC, true);
        g.drawString(font, "§7Ваш статус: §e" + (playerRole != null ? playerRole.getDisplayName() : "Гражданин"), x + 20, y + 45, 0xFFCCCCCC, true);
        g.drawString(font, "§7Девиз: §o" + (st.motd().isEmpty() ? "—" : st.motd()), x + 20, y + 60, 0xFFAAAAAA, true);

        long claimedCount = cache.getAllClaims().values().stream().filter(c -> playerStateId.equals(c.stateId())).count();
        g.drawString(font, "§7Подконтрольных чанков территории: §b" + claimedCount, x + 20, y + 80, 0xFFCCCCCC, true);

        SyncTerritoryDataPayload.ClientVassalData suzerainPact = cache.getSuzerainPact(playerStateId);
        if (suzerainPact != null) {
            SyncTerritoryDataPayload.ClientStateData suz = cache.getState(suzerainPact.suzerainId());
            String suzName = suz != null ? suz.name() : "Сюзерен";
            g.drawString(font, "§eФеодальный статус: §6Вассал государства «" + suzName + "» (" + suzerainPact.title() + ")", x + 20, y + 100, 0xFFFFAA00, true);
        } else {
            g.drawString(font, "§aФеодальный статус: §2Полный суверенитет", x + 20, y + 100, 0xFF55FF55, true);
        }
    }

    private void renderLawsTab(GuiGraphics g, int x, int y, int w, int h) {
        FrontierClientCache cache = FrontierClientCache.get();

        if (isCreatingLaw) {
            // Field labels occupy this form; the page title stays in the top bar.
            g.drawString(font, "§7Краткий заголовок закона:", x + 15, y + 20, 0xFFCCCCCC, true);
            g.drawString(font, "§7Суть и формулировка правовой нормы:", x + 15, y + 55, 0xFFCCCCCC, true);
            g.drawString(font, "§7Мера наказания при установлении вины:", x + 15, y + 90, 0xFFCCCCCC, true);
            return;
        }

        g.drawString(font, "§6Свод законов и правовых норм государства", x + 15, y + 10, 0xFFFFFFFF, true);

        if (playerStateId == null) {
            g.drawString(font, "§7Вы не гражданин. Действуют лишь общие правила сервера.", x + 15, y + 30, 0xFF888888, true);
            return;
        }

        List<SyncCasesAndLawsPayload.ClientLawData> laws = cache.getLaws(playerStateId);
        if (laws.isEmpty()) {
            g.drawString(font, "§7В государстве пока не принято ни одной статьи закона.", x + 15, y + 35, 0xFF888888, true);
            return;
        }

        int curY = y + 35;
        for (SyncCasesAndLawsPayload.ClientLawData law : laws) {
            if (curY > y + h - 30) break;
            g.drawString(font, "§e§lСтатья: §f" + law.title() + " §8[" + law.status().getLabel() + "§8]", x + 15, curY, 0xFFFFFFFF, true);
            g.drawString(font, "  §7Суть: §f" + law.content(), x + 15, curY + 12, 0xFFCCCCCC, true);
            g.drawString(font, "  §cМера пресечения: §7" + law.penalty(), x + 15, curY + 24, 0xFFCCCCCC, true);
            curY += 40;
        }
    }

    private void renderDiplomacyTab(GuiGraphics g, int x, int y, int w, int h) {
        FrontierClientCache cache = FrontierClientCache.get();
        g.drawString(font, "§6Международные отношения и дипломатические договоры", x + 15, y + 10, 0xFFFFFFFF, true);

        Collection<SyncTerritoryDataPayload.ClientStateData> allStates = cache.getAllStates();
        int curY = y + 35;
        for (SyncTerritoryDataPayload.ClientStateData other : allStates) {
            if (playerStateId != null && other.id().equals(playerStateId)) continue;
            if (curY > y + h - 25) break;

            RelationType rel = (playerStateId != null) ? cache.getRelation(playerStateId, other.id()) : RelationType.NEUTRAL;
            g.drawString(font, "§e" + other.name() + " §7[" + other.tag() + "] — Лидер: §f" + other.leaderName(), x + 15, curY, 0xFFFFFFFF, true);
            g.drawString(font, "  §7Статус отношений: §f" + rel.getLabel(), x + 15, curY + 12, 0xFF000000 | rel.getColor(), true);
            curY += 30;
        }
    }

    private void renderCourtTab(GuiGraphics g, int x, int y, int w, int h) {
        FrontierClientCache cache = FrontierClientCache.get();

        if (isCreatingCase) {
            // Avoid overlapping the first field label.
            g.drawString(font, "§7Название дела / инцидента:", x + 15, y + 20, 0xFFCCCCCC, true);
            g.drawString(font, "§7Точный игровой никнейм подозреваемого:", x + 15, y + 55, 0xFFCCCCCC, true);
            return;
        }

        g.drawString(font, "§6Судебный трибунал и реестр расследований", x + 15, y + 10, 0xFFFFFFFF, true);

        if (playerStateId == null) {
            g.drawString(font, "§7Доступно только гражданам признанных государств.", x + 15, y + 30, 0xFF888888, true);
            return;
        }

        List<SyncCasesAndLawsPayload.ClientCaseData> cases = cache.getCases(playerStateId);
        if (cases.isEmpty()) {
            g.drawString(font, "§7Нет открытых дел или зарегистрированных производств.", x + 15, y + 35, 0xFF888888, true);
            return;
        }

        int curY = y + 35;
        for (SyncCasesAndLawsPayload.ClientCaseData c : cases) {
            if (curY > y + h - 35) break;
            g.drawString(font, "§eДело №" + c.id() + ": §f" + c.title() + " §8[" + c.status().getLabel() + "§8]", x + 15, curY, 0xFFFFFFFF, true);
            g.drawString(font, "  §7Обвиняемый: §c" + c.accusedName() + " §7| Дознаватель: §b" + c.judgeName(), x + 15, curY + 12, 0xFFCCCCCC, true);
            if (!c.verdict().isEmpty()) {
                g.drawString(font, "  §6Вердикт: §f" + c.verdict(), x + 15, curY + 24, 0xFFFFAA00, true);
                curY += 12;
            }
            curY += 30;
        }
    }

    private void renderVassalsTab(GuiGraphics g, int x, int y, int w, int h) {
        FrontierClientCache cache = FrontierClientCache.get();

        int bannerY = y + 6;
        g.fill(x + 10, bannerY, x + w - 10, bannerY + 34, 0xFF18181C);
        g.renderOutline(x + 10, bannerY, w - 20, 34, 0xFFFFAA00);

        g.drawCenteredString(font, "§6§l«Вассал моего вассала — не мой вассал...»", x + w / 2, bannerY + 6, 0xFFFFAA00);
        g.drawCenteredString(font, "§7§oДревний принцип феодального права: власть сюзерена распространяется лишь на прямых вассалов.", x + w / 2, bannerY + 20, 0xFFAAAAAA);

        if (playerStateId == null) {
            g.drawCenteredString(font, "§7Вы свободный скиталец Фронтира. Феодальные узы вас не связывают.", x + w / 2, y + 80, 0xFF888888);
            return;
        }

        int halfW = (w - 30) / 2;
        int col1X = x + 12;
        int col2X = x + halfW + 18;
        int topCardsY = y + 46;

        // Column 1: Сюзерен
        g.fill(col1X, topCardsY, col1X + halfW, y + h - 10, 0xFF1E2028);
        g.renderOutline(col1X, topCardsY, halfW, h - 56, 0xFF4A4E5C);

        g.drawString(font, "§e§lВЕРХОВНЫЙ СЮЗЕРЕН", col1X + 10, topCardsY + 8, 0xFFFFFFFF, true);

        SyncTerritoryDataPayload.ClientVassalData suzerainPact = cache.getSuzerainPact(playerStateId);
        if (suzerainPact != null) {
            SyncTerritoryDataPayload.ClientStateData suzerain = cache.getState(suzerainPact.suzerainId());
            String suzName = suzerain != null ? suzerain.name() : "Сюзерен";
            String suzTag = suzerain != null ? suzerain.tag() : "?";

            g.drawString(font, "§6" + suzName + " §7[" + suzTag + "]", col1X + 10, topCardsY + 25, 0xFFFFFFFF, true);
            g.drawString(font, "§7Титул автономии: §f" + suzerainPact.title(), col1X + 10, topCardsY + 38, 0xFFCCCCCC, true);
            g.drawString(font, "§7Дань сюзерену: §b" + suzerainPact.tributePercent() + "%", col1X + 10, topCardsY + 51, 0xFFCCCCCC, true);
            g.drawString(font, "§7Статус: §aВоенная защита Сюзерена", col1X + 10, topCardsY + 64, 0xFF55FF55, true);
        } else {
            g.drawString(font, "§a§lПолный суверенитет", col1X + 10, topCardsY + 25, 0xFF55FF55, true);
            g.drawString(font, "§7Ваше государство никому не присягало.", col1X + 10, topCardsY + 38, 0xFFCCCCCC, true);
            g.drawString(font, "§7Вы сами являетесь верховной властью.", col1X + 10, topCardsY + 51, 0xFFAAAAAA, true);
        }

        // Column 2: Прямые вассалы
        g.fill(col2X, topCardsY, col2X + halfW, y + h - 10, 0xFF1E2028);
        g.renderOutline(col2X, topCardsY, halfW, h - 56, 0xFF4A4E5C);

        List<SyncTerritoryDataPayload.ClientVassalData> directVassals = cache.getDirectVassals(playerStateId);
        g.drawString(font, "§6§lПРЯМЫЕ ВАССАЛЫ (" + directVassals.size() + ")", col2X + 10, topCardsY + 8, 0xFFFFFFFF, true);

        if (directVassals.isEmpty()) {
            g.drawString(font, "§7У вас нет вассальных объединений.", col2X + 10, topCardsY + 28, 0xFF888888, true);
            g.drawString(font, "§7Приведите соседей к покорности", col2X + 10, topCardsY + 42, 0xFF888888, true);
            g.drawString(font, "§7или предложите им военное покровительство.", col2X + 10, topCardsY + 56, 0xFF888888, true);
        } else {
            int vY = topCardsY + 24;
            for (SyncTerritoryDataPayload.ClientVassalData v : directVassals) {
                if (vY > y + h - 35) break;
                SyncTerritoryDataPayload.ClientStateData vState = cache.getState(v.vassalId());
                String vName = vState != null ? vState.name() : "Вассал";
                String vTag = vState != null ? vState.tag() : "?";

                g.drawString(font, "§e" + vName + " §7[" + vTag + "] §8(" + v.title() + "§8)", col2X + 10, vY, 0xFFFFFFFF, true);
                g.drawString(font, "  §7Дань: §b" + v.tributePercent() + "% §7| Лидер: §f" + (vState != null ? vState.leaderName() : "?"), col2X + 10, vY + 11, 0xFFCCCCCC, true);

                List<SyncTerritoryDataPayload.ClientVassalData> subVassals = cache.getDirectVassals(v.vassalId());
                if (!subVassals.isEmpty()) {
                    g.drawString(font, "  §8↳ Под-вассалов: " + subVassals.size() + " §o(вам не подчиняются!)", col2X + 10, vY + 22, 0xFFAAAAAA, true);
                    vY += 11;
                }
                vY += 25;
            }
        }
    }

    private void renderAdminTab(GuiGraphics g, int x, int y, int w, int h) {
        FrontierClientCache cache = FrontierClientCache.get();

        g.drawString(font, "§6§lПанель Куратора (Администрация сервера)", x + 15, y + 10, 0xFFFFAA00, true);
        g.drawString(font, "§7Рассмотрение заявок на регистрацию государств и разметка территорий", x + 15, y + 22, 0xFFAAAAAA, true);

        List<SyncAdminPetitionsPayload.AdminPetitionData> petitions = cache.getAdminPetitions();
        int petY = y + 40;
        boolean hasPending = false;

        for (SyncAdminPetitionsPayload.AdminPetitionData p : petitions) {
            if (!"PENDING".equals(p.status())) continue;
            hasPending = true;
            if (petY > y + h - 40) break;

            g.fill(x + 15, petY - 2, x + w - 15, petY + 26, 0xFF20222A);
            g.renderOutline(x + 15, petY - 2, w - 30, 28, 0xFF5A5E70);

            g.drawString(font, "§6«" + p.stateName() + "» §7[" + p.stateTag() + "]", x + 25, petY + 2, 0xFFFFFFFF, true);
            g.drawString(font, "§7Заявитель: §b" + p.requesterName() + " §7| Столица: §e[" + p.capitalChunkX() + ", " + p.capitalChunkZ() + "]", x + 25, petY + 14, 0xFFCCCCCC, true);

            petY += 36;
        }

        if (!hasPending) {
            g.drawString(font, "§a✔ Ожидающих заявок на создание государств нет.", x + 20, y + 50, 0xFF55FF55, true);
            g.drawString(font, "§7Когда игрок подаст заявку, здесь появится карточка с кнопками утверждения в 1 клик.", x + 20, y + 65, 0xFFAAAAAA, true);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static boolean sameTerritory(SyncTerritoryDataPayload.ClientClaimData a,
                                         SyncTerritoryDataPayload.ClientClaimData b) {
        return b != null && Objects.equals(a.stateId(), b.stateId())
                && a.jurisdictionType() == b.jurisdictionType();
    }
}
