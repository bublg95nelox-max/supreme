package net.frontier.data.model;

import java.util.UUID;

public class FrontierState {
    private final UUID id;
    private String name;
    private String tag;
    private int color;
    private UUID leaderUuid;
    private String leaderName;
    private final long createdAt;
    private String motd;

    public FrontierState(UUID id, String name, String tag, int color, UUID leaderUuid, String leaderName, long createdAt, String motd) {
        this.id = id;
        this.name = name;
        this.tag = tag;
        this.color = color;
        this.leaderUuid = leaderUuid;
        this.leaderName = leaderName;
        this.createdAt = createdAt;
        this.motd = motd != null ? motd : "";
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name != null ? name.trim() : "";
    }

    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag != null ? tag.trim() : "";
    }

    public int getColor() {
        return color;
    }

    public void setColor(int color) {
        this.color = color;
    }

    public UUID getLeaderUuid() {
        return leaderUuid;
    }

    public void setLeaderUuid(UUID leaderUuid) {
        this.leaderUuid = leaderUuid;
    }

    public String getLeaderName() {
        return leaderName;
    }

    public void setLeaderName(String leaderName) {
        this.leaderName = leaderName != null ? leaderName.trim() : "";
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public String getMotd() {
        return motd;
    }

    public void setMotd(String motd) {
        this.motd = motd != null ? motd.trim() : "";
    }
}
