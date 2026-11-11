package net.frontier.data.model;

import java.util.UUID;

public class StatePetition {
    private int id;
    private final UUID requesterUuid;
    private String requesterName;
    private String stateName;
    private String stateTag;
    private int color;
    private String dimension;
    private int capitalChunkX;
    private int capitalChunkZ;
    private String status; // "PENDING", "APPROVED", "REJECTED"
    private final long submittedAt;

    public StatePetition(int id, UUID requesterUuid, String requesterName, String stateName,
                         String stateTag, int color, String dimension, int capitalChunkX,
                         int capitalChunkZ, String status, long submittedAt) {
        this.id = id;
        this.requesterUuid = requesterUuid;
        this.requesterName = requesterName;
        this.stateName = stateName;
        this.stateTag = stateTag;
        this.color = color;
        this.dimension = dimension;
        this.capitalChunkX = capitalChunkX;
        this.capitalChunkZ = capitalChunkZ;
        this.status = status;
        this.submittedAt = submittedAt;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public UUID getRequesterUuid() {
        return requesterUuid;
    }

    public String getRequesterName() {
        return requesterName;
    }

    public void setRequesterName(String requesterName) {
        this.requesterName = requesterName;
    }

    public String getStateName() {
        return stateName;
    }

    public void setStateName(String stateName) {
        this.stateName = stateName;
    }

    public String getStateTag() {
        return stateTag;
    }

    public void setStateTag(String stateTag) {
        this.stateTag = stateTag;
    }

    public int getColor() {
        return color;
    }

    public void setColor(int color) {
        this.color = color;
    }

    public String getDimension() {
        return dimension;
    }

    public void setDimension(String dimension) {
        this.dimension = dimension;
    }

    public int getCapitalChunkX() {
        return capitalChunkX;
    }

    public void setCapitalChunkX(int capitalChunkX) {
        this.capitalChunkX = capitalChunkX;
    }

    public int getCapitalChunkZ() {
        return capitalChunkZ;
    }

    public void setCapitalChunkZ(int capitalChunkZ) {
        this.capitalChunkZ = capitalChunkZ;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public long getSubmittedAt() {
        return submittedAt;
    }
}
