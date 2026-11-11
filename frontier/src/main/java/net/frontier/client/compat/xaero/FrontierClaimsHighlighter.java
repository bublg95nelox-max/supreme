package net.frontier.client.compat.xaero;

import net.frontier.api.JurisdictionType;
import net.frontier.client.FrontierClientCache;
import net.frontier.network.payloads.SyncTerritoryDataPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import xaero.map.highlight.ChunkHighlighter;

import java.util.List;

public class FrontierClaimsHighlighter extends ChunkHighlighter {

    public FrontierClaimsHighlighter() {
        super(true); // coveringOutsideDiscovered
    }

    private boolean isTargetDimension(ResourceKey<Level> dimension) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && dimension != null) {
            return mc.level.dimension().equals(dimension);
        }
        return true;
    }

    @Override
    public boolean regionHasHighlights(ResourceKey<Level> dimension, int regionX, int regionZ) {
        if (!isTargetDimension(dimension)) return false;

        FrontierClientCache cache = FrontierClientCache.get();
        int minCx = regionX << 5;
        int maxCx = minCx + 31;
        int minCz = regionZ << 5;
        int maxCz = minCz + 31;

        for (SyncTerritoryDataPayload.ClientClaimData claim : cache.getAllClaims().values()) {
            if (claim.chunkX() >= minCx && claim.chunkX() <= maxCx &&
                claim.chunkZ() >= minCz && claim.chunkZ() <= maxCz) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean chunkIsHighlit(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        if (!isTargetDimension(dimension)) return false;

        SyncTerritoryDataPayload.ClientClaimData claim = FrontierClientCache.get().getClaim(chunkX, chunkZ);
        return claim != null && (claim.stateId() != null || claim.jurisdictionType() == JurisdictionType.NEUTRAL);
    }

    @Override
    protected int[] getColors(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        if (!isTargetDimension(dimension)) return null;

        FrontierClientCache cache = FrontierClientCache.get();
        SyncTerritoryDataPayload.ClientClaimData claim = cache.getClaim(chunkX, chunkZ);
        if (claim == null) return null;

        int rgb;
        if (claim.jurisdictionType() == JurisdictionType.NEUTRAL) {
            rgb = 0x2864A8; // Blue neutral buffer
        } else if (claim.stateId() != null) {
            SyncTerritoryDataPayload.ClientStateData st = cache.getState(claim.stateId());
            rgb = st != null ? st.color() : 0x35A035;
        } else {
            return null;
        }

        // Convert 0xRRGGBB to Xaero Little-Endian RGBA format: (B << 24) | (G << 16) | (R << 8) | A
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;

        int baseColor = (b << 24) | (g << 16) | (r << 8);
        int fillColor = baseColor | 0x48;    // ~28% opacity translucent fill
        int borderColor = baseColor | 0xE0;  // ~88% opacity solid border

        SyncTerritoryDataPayload.ClientClaimData north = cache.getClaim(chunkX, chunkZ - 1);
        SyncTerritoryDataPayload.ClientClaimData east = cache.getClaim(chunkX + 1, chunkZ);
        SyncTerritoryDataPayload.ClientClaimData south = cache.getClaim(chunkX, chunkZ + 1);
        SyncTerritoryDataPayload.ClientClaimData west = cache.getClaim(chunkX - 1, chunkZ);

        int northColor = sameOwner(claim, north) ? fillColor : borderColor;
        int eastColor = sameOwner(claim, east) ? fillColor : borderColor;
        int southColor = sameOwner(claim, south) ? fillColor : borderColor;
        int westColor = sameOwner(claim, west) ? fillColor : borderColor;

        return new int[]{ fillColor, northColor, eastColor, southColor, westColor };
    }

    private boolean sameOwner(SyncTerritoryDataPayload.ClientClaimData a, SyncTerritoryDataPayload.ClientClaimData b) {
        if (a == null || b == null) return false;
        if (a.jurisdictionType() == JurisdictionType.NEUTRAL && b.jurisdictionType() == JurisdictionType.NEUTRAL) return true;
        if (a.stateId() != null && b.stateId() != null) {
            return a.stateId().equals(b.stateId());
        }
        return false;
    }

    @Override
    public int calculateRegionHash(ResourceKey<Level> dimension, int regionX, int regionZ) {
        if (!isTargetDimension(dimension)) return 0;

        FrontierClientCache cache = FrontierClientCache.get();
        int minCx = regionX << 5;
        int maxCx = minCx + 31;
        int minCz = regionZ << 5;
        int maxCz = minCz + 31;

        int hash = 1;
        for (SyncTerritoryDataPayload.ClientClaimData c : cache.getAllClaims().values()) {
            if (c.chunkX() >= minCx && c.chunkX() <= maxCx && c.chunkZ() >= minCz && c.chunkZ() <= maxCz) {
                hash = 31 * hash + c.chunkX();
                hash = 31 * hash + c.chunkZ();
                hash = 31 * hash + (c.stateId() != null ? c.stateId().hashCode() : 0);
                hash = 31 * hash + c.jurisdictionType().ordinal();
            }
        }
        return hash;
    }

    @Override
    public Component getChunkHighlightSubtleTooltip(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        if (!isTargetDimension(dimension)) return null;

        FrontierClientCache cache = FrontierClientCache.get();
        SyncTerritoryDataPayload.ClientClaimData claim = cache.getClaim(chunkX, chunkZ);
        if (claim == null) return null;

        if (claim.jurisdictionType() == JurisdictionType.NEUTRAL) {
            return Component.literal("§b§lНейтральная территория §7[Фронтир]");
        }

        if (claim.stateId() != null) {
            SyncTerritoryDataPayload.ClientStateData st = cache.getState(claim.stateId());
            String name = st != null ? st.name() : "Государство";
            String tag = st != null ? " [" + st.tag() + "]" : "";

            String vassalInfo = "";
            SyncTerritoryDataPayload.ClientVassalData vPact = cache.getSuzerainPact(claim.stateId());
            if (vPact != null) {
                SyncTerritoryDataPayload.ClientStateData suz = cache.getState(vPact.suzerainId());
                if (suz != null) {
                    vassalInfo = " §8(Сюзерен: " + suz.name() + ")";
                }
            }

            return Component.literal("§6§l" + name + tag + " §7(Юрисдикция: §a" + claim.jurisdictionType().getLabel() + "§7)" + vassalInfo);
        }

        return null;
    }

    @Override
    public Component getChunkHighlightBluntTooltip(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        return getChunkHighlightSubtleTooltip(dimension, chunkX, chunkZ);
    }

    @Override
    public void addMinimapBlockHighlightTooltips(List<Component> list, ResourceKey<Level> dimension, int x, int y, int z) {
        Component tip = getChunkHighlightSubtleTooltip(dimension, x >> 4, z >> 4);
        if (tip != null && !list.contains(tip)) {
            list.add(tip);
        }
    }
}
