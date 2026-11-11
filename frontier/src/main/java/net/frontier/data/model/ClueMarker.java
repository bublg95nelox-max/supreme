package net.frontier.data.model;

import net.frontier.api.CrimeType;
import net.frontier.api.EvidenceGrade;
import java.util.UUID;

public class ClueMarker {
    private int id;
    private final String dimension;
    private final int x;
    private final int y;
    private final int z;
    private final int chunkX;
    private final int chunkZ;
    private final CrimeType crimeType;
    private final EvidenceGrade grade;
    private final String description;
    private final UUID actorUuid;
    private final String actorName;
    private final String toolUsed;
    private final long timestamp;

    public ClueMarker(int id, String dimension, int x, int y, int z, int chunkX, int chunkZ,
                      CrimeType crimeType, EvidenceGrade grade, String description,
                      UUID actorUuid, String actorName, String toolUsed, long timestamp) {
        this.id = id;
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.crimeType = crimeType;
        this.grade = grade;
        this.description = description;
        this.actorUuid = actorUuid;
        this.actorName = actorName;
        this.toolUsed = toolUsed != null ? toolUsed : "";
        this.timestamp = timestamp;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getDimension() {
        return dimension;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getZ() {
        return z;
    }

    public int getChunkX() {
        return chunkX;
    }

    public int getChunkZ() {
        return chunkZ;
    }

    public CrimeType getCrimeType() {
        return crimeType;
    }

    public EvidenceGrade getGrade() {
        return grade;
    }

    public String getDescription() {
        return description;
    }

    public UUID getActorUuid() {
        return actorUuid;
    }

    public String getActorName() {
        return actorName;
    }

    public String getToolUsed() {
        return toolUsed;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getTimeAgoDesc(long now) {
        long diffSec = (now - timestamp) / 1000L;
        if (diffSec < 60) {
            return "Только что (менее 1 мин назад)";
        } else if (diffSec < 3600) {
            return (diffSec / 60) + " мин. назад";
        } else if (diffSec < 86400) {
            return (diffSec / 3600) + " ч. назад";
        } else {
            return (diffSec / 86400) + " дн. назад";
        }
    }
}
