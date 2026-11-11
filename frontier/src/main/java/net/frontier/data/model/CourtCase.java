package net.frontier.data.model;

import net.frontier.api.CaseStatus;
import java.util.UUID;

public class CourtCase {
    private int id;
    private final UUID stateId;
    private String title;
    private UUID accusedUuid;
    private String accusedName;
    private UUID plaintiffUuid;
    private String plaintiffName;
    private UUID judgeUuid;
    private String judgeName;
    private CaseStatus status;
    private String verdict;
    private final long createdAt;
    private long resolvedAt;

    public CourtCase(int id, UUID stateId, String title, UUID accusedUuid, String accusedName,
                     UUID plaintiffUuid, String plaintiffName, UUID judgeUuid, String judgeName,
                     CaseStatus status, String verdict, long createdAt, long resolvedAt) {
        this.id = id;
        this.stateId = stateId;
        this.title = title;
        this.accusedUuid = accusedUuid;
        this.accusedName = accusedName;
        this.plaintiffUuid = plaintiffUuid;
        this.plaintiffName = plaintiffName;
        this.judgeUuid = judgeUuid;
        this.judgeName = judgeName;
        this.status = status;
        this.verdict = verdict != null ? verdict : "";
        this.createdAt = createdAt;
        this.resolvedAt = resolvedAt;
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

    public UUID getAccusedUuid() {
        return accusedUuid;
    }

    public void setAccusedUuid(UUID accusedUuid) {
        this.accusedUuid = accusedUuid;
    }

    public String getAccusedName() {
        return accusedName;
    }

    public void setAccusedName(String accusedName) {
        this.accusedName = accusedName;
    }

    public UUID getPlaintiffUuid() {
        return plaintiffUuid;
    }

    public void setPlaintiffUuid(UUID plaintiffUuid) {
        this.plaintiffUuid = plaintiffUuid;
    }

    public String getPlaintiffName() {
        return plaintiffName;
    }

    public void setPlaintiffName(String plaintiffName) {
        this.plaintiffName = plaintiffName;
    }

    public UUID getJudgeUuid() {
        return judgeUuid;
    }

    public void setJudgeUuid(UUID judgeUuid) {
        this.judgeUuid = judgeUuid;
    }

    public String getJudgeName() {
        return judgeName;
    }

    public void setJudgeName(String judgeName) {
        this.judgeName = judgeName;
    }

    public CaseStatus getStatus() {
        return status;
    }

    public void setStatus(CaseStatus status) {
        this.status = status;
    }

    public String getVerdict() {
        return verdict;
    }

    public void setVerdict(String verdict) {
        this.verdict = verdict;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(long resolvedAt) {
        this.resolvedAt = resolvedAt;
    }
}
