package net.frontier.api;

public enum EvidenceGrade {
    CLEAR("Явный след", 0x55FF55, "Чёткие очертания и глубокие отпечатки"),
    PARTIAL("Частичный след", 0xFFFF55, "Смазанные очертания или частичные следы"),
    FAINT("Слабый след", 0xFFAA00, "Едва различимые частицы и микроследы");

    private final String label;
    private final int color;
    private final String description;

    EvidenceGrade(String label, int color, String description) {
        this.label = label;
        this.color = color;
        this.description = description;
    }

    public String getLabel() {
        return label;
    }

    public int getColor() {
        return color;
    }

    public String getDescription() {
        return description;
    }
}
