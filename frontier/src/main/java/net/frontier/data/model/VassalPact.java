package net.frontier.data.model;

import java.util.UUID;

public class VassalPact {
    private final UUID suzerainId;
    private final UUID vassalId;
    private final long pledgedAt;
    private String title; // e.g. "Герцогство", "Маркграфство", "Протекторат"
    private int tributePercent;

    public VassalPact(UUID suzerainId, UUID vassalId, long pledgedAt, String title, int tributePercent) {
        this.suzerainId = suzerainId;
        this.vassalId = vassalId;
        this.pledgedAt = pledgedAt;
        this.title = title != null && !title.isEmpty() ? title : "Вассалитет";
        this.tributePercent = tributePercent;
    }

    public UUID getSuzerainId() {
        return suzerainId;
    }

    public UUID getVassalId() {
        return vassalId;
    }

    public long getPledgedAt() {
        return pledgedAt;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public int getTributePercent() {
        return tributePercent;
    }

    public void setTributePercent(int tributePercent) {
        this.tributePercent = tributePercent;
    }
}
