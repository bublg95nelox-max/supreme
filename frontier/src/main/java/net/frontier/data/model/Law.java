package net.frontier.data.model;

import net.frontier.api.LawStatus;
import java.util.UUID;

public class Law {
    private int id;
    private final UUID stateId;
    private String title;
    private String content;
    private String penalty;
    private final UUID createdBy;
    private String authorName;
    private final long createdAt;
    private LawStatus status;

    public Law(int id, UUID stateId, String title, String content, String penalty, UUID createdBy, String authorName, long createdAt, LawStatus status) {
        this.id = id;
        this.stateId = stateId;
        this.title = title;
        this.content = content;
        this.penalty = penalty;
        this.createdBy = createdBy;
        this.authorName = authorName;
        this.createdAt = createdAt;
        this.status = status;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public UUID getStateId() {
        return stateId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getPenalty() {
        return penalty;
    }

    public void setPenalty(String penalty) {
        this.penalty = penalty;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public String getAuthorName() {
        return authorName;
    }

    public void setAuthorName(String authorName) {
        this.authorName = authorName;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public LawStatus getStatus() {
        return status;
    }

    public void setStatus(LawStatus status) {
        this.status = status;
    }
}
