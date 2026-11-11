package net.frontier.api;

public enum JurisdictionType {
    WILD("Дикие земли", 0x888888),
    NATIONAL("Территория государства", 0x33AA33),
    NEUTRAL("Нейтральная зона", 0x3388EE),
    OUTPOST("Форпост", 0xFFAA00);

    private final String label;
    private final int defaultColor;

    JurisdictionType(String label, int defaultColor) {
        this.label = label;
        this.defaultColor = defaultColor;
    }

    public String getLabel() {
        return label;
    }

    public int getDefaultColor() {
        return defaultColor;
    }
}
