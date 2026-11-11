package net.frontier.data.model;

import net.frontier.api.Role;
import java.util.UUID;

public class StateMember {
    private final UUID stateId;
    private final UUID playerUuid;
    private String playerName;
    private Role role;
    private final long joinedAt;

    public StateMember(UUID stateId, UUID playerUuid, String playerName, Role role, long joinedAt) {
        this.stateId = stateId;
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.role = role;
        this.joinedAt = joinedAt;
    }

    public UUID getStateId() {
        return stateId;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public String getPlayerName() {
        return playerName;
    }

    public void setPlayerName(String playerName) {
        this.playerName = playerName;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public long getJoinedAt() {
        return joinedAt;
    }
}
