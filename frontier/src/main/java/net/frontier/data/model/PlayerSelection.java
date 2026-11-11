package net.frontier.data.model;

import net.minecraft.core.BlockPos;
import java.util.UUID;

public class PlayerSelection {
    private final UUID playerUuid;
    private String dimension;
    private Integer x1, y1, z1;
    private Integer x2, y2, z2;

    public PlayerSelection(UUID playerUuid) {
        this.playerUuid = playerUuid;
    }

    public UUID getPlayerUuid() { return playerUuid; }
    public String getDimension() { return dimension; }
    public void setDimension(String dimension) { this.dimension = dimension; }

    public void setPos1(int x, int y, int z) {
        this.x1 = x;
        this.y1 = y;
        this.z1 = z;
    }

    public void setPos1(BlockPos pos) {
        if (pos != null) setPos1(pos.getX(), pos.getY(), pos.getZ());
    }

    public void setPos2(int x, int y, int z) {
        this.x2 = x;
        this.y2 = y;
        this.z2 = z;
    }

    public void setPos2(BlockPos pos) {
        if (pos != null) setPos2(pos.getX(), pos.getY(), pos.getZ());
    }

    public BlockPos getPos1() {
        return (x1 != null && y1 != null && z1 != null) ? new BlockPos(x1, y1, z1) : null;
    }

    public BlockPos getPos2() {
        return (x2 != null && y2 != null && z2 != null) ? new BlockPos(x2, y2, z2) : null;
    }

    public boolean hasPos1() { return x1 != null && y1 != null && z1 != null; }
    public boolean hasPos2() { return x2 != null && y2 != null && z2 != null; }

    public boolean isComplete() {
        return hasPos1() && hasPos2() && dimension != null;
    }

    public int getMinX() { return Math.min(x1, x2); }
    public int getMaxX() { return Math.max(x1, x2); }
    public int getMinY() { return Math.min(y1, y2); }
    public int getMaxY() { return Math.max(y1, y2); }
    public int getMinZ() { return Math.min(z1, z2); }
    public int getMaxZ() { return Math.max(z1, z2); }

    public int getWidth() { return getMaxX() - getMinX() + 1; }
    public int getLength() { return getMaxZ() - getMinZ() + 1; }
    public int getHeight() { return getMaxY() - getMinY() + 1; }
    public long getArea() { return (long) getWidth() * getLength(); }
    public long getVolume() { return getArea() * getHeight(); }

    public int getChunkCount() {
        int minCx = getMinX() >> 4;
        int maxCx = getMaxX() >> 4;
        int minCz = getMinZ() >> 4;
        int maxCz = getMaxZ() >> 4;
        return (maxCx - minCx + 1) * (maxCz - minCz + 1);
    }
}
