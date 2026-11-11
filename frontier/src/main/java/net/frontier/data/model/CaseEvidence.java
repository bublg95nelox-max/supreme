package net.frontier.data.model;

public class CaseEvidence {
    private final int caseId;
    private final int clueId;
    private final String notes;
    private final long addedAt;

    public CaseEvidence(int caseId, int clueId, String notes, long addedAt) {
        this.caseId = caseId;
        this.clueId = clueId;
        this.notes = notes != null ? notes : "";
        this.addedAt = addedAt;
    }

    public int getCaseId() {
        return caseId;
    }

    public int getClueId() {
        return clueId;
    }

    public String getNotes() {
        return notes;
    }

    public long getAddedAt() {
        return addedAt;
    }
}
