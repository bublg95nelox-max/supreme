package net.frontier.data.model;

import net.frontier.api.JurisdictionType;
import java.util.UUID;

public class TerritoryChunk {
    private final String dimension;
    private final int chunkX;
    private final int chunkZ;
    private UUID stateId;
    private JurisdictionType jurisdictionType;
    private UUID claimedBy;
    private long claimedAt;

    public TerritoryChunk(String dimension, int chunkX, int chunkZ, UUID stateId, JurisdictionType jurisdictionType, UUID claimedBy, long claimedAt) {
        this.dimension = dimension;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.stateId = stateId;
        this.jurisdictionType = jurisdictionType != null ? jurisdictionType : JurisdictionType.NATIONAL;
        this.claimedBy = claimedBy;
        this.claimedAt = claimedAt;
    }

    public static long toChunkKey(int x, int z) {
        return (((long) x) << 32) | (z & 0xFFFFFFFFL);
    }

    public long getChunkKey() {
        return toChunkKey(chunkX, chunkZ);
    }

    public String getDimension() {
        return dimension;
    }

    public int getChunkX() {
        return chunkX;
    }

    public int getChunkZ() {
        return chunkZ;
    }

    public UUID getStateId() {
        return stateId;
    }

    public void setStateId(UUID stateId) {
        this.stateId = stateId;
    }

    public JurisdictionType getJurisdictionType() {
        return jurisdictionType;
    }

    public void setJurisdictionType(JurisdictionType jurisdictionType) {
        this.jurisdictionType = jurisdictionType;
    }

    public UUID getClaimedBy() {
        return claimedBy;
    }

    public void setClaimedBy(UUID claimedBy) {
        this.claimedBy = claimedBy;
    }

    public long getClaimedAt() {
        return claimedAt;
    }

    public void setClaimedAt(long claimedAt) {
        this.claimedAt = claimedAt;
    }
}
