package net.frontier.api;

public enum CaseStatus {
    OPEN("Новое дело"),
    INVESTIGATION("Ведётся следствие"),
    GUILTY("Виновен (В розыске)"),
    INNOCENT("Оправдан"),
    ARCHIVED("В архиве");

    private final String label;

    CaseStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public boolean isResolved() {
        return this == GUILTY || this == INNOCENT || this == ARCHIVED;
    }
}
