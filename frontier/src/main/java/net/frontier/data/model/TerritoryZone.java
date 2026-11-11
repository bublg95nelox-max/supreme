package net.frontier.data.model;

import net.frontier.api.JurisdictionType;
import net.minecraft.core.BlockPos;

import java.util.UUID;

public class TerritoryZone {
    private final UUID id;
    private final UUID stateId; // nullable for NEUTRAL buffer zones
    private final String name;
    private final String dimension;
    private final int minX, minY, minZ;
    private final int maxX, maxY, maxZ;
    private final JurisdictionType jurisdictionType;
    private final UUID createdBy;
    private final long createdAt;

    public TerritoryZone(UUID id, UUID stateId, String name, String dimension,
                         int minX, int minY, int minZ,
                         int maxX, int maxY, int maxZ,
                         JurisdictionType jurisdictionType, UUID createdBy, long createdAt) {
        this.id = id;
        this.stateId = stateId;
        this.name = name;
        this.dimension = dimension;
        this.minX = Math.min(minX, maxX);
        this.minY = Math.min(minY, maxY);
        this.minZ = Math.min(minZ, maxZ);
        this.maxX = Math.max(minX, maxX);
        this.maxY = Math.max(minY, maxY);
        this.maxZ = Math.max(minZ, maxZ);
        this.jurisdictionType = jurisdictionType;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getStateId() { return stateId; }
    public String getName() { return name; }
    public String getDimension() { return dimension; }
    public int getMinX() { return minX; }
    public int getMinY() { return minY; }
    public int getMinZ() { return minZ; }
    public int getMaxX() { return maxX; }
    public int getMaxY() { return maxY; }
    public int getMaxZ() { return maxZ; }
    public JurisdictionType getJurisdictionType() { return jurisdictionType; }
    public UUID getCreatedBy() { return createdBy; }
    public long getCreatedAt() { return createdAt; }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    public boolean contains(BlockPos pos) {
        return contains(pos.getX(), pos.getY(), pos.getZ());
    }

    public boolean contains2D(int x, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    public boolean intersects2D(String dim, int otherMinX, int otherMinZ, int otherMaxX, int otherMaxZ) {
        if (!this.dimension.equals(dim)) return false;
        return !(maxX < otherMinX || minX > otherMaxX || maxZ < otherMinZ || minZ > otherMaxZ);
    }

    public int getWidth() { return maxX - minX + 1; }
    public int getLength() { return maxZ - minZ + 1; }
    public int getHeight() { return maxY - minY + 1; }
    public long getArea() { return (long) getWidth() * getLength(); }
    public long getVolume() { return getArea() * getHeight(); }
}
