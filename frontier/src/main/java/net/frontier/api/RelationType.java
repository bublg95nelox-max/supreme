package net.frontier.api;

public enum RelationType {
    NEUTRAL("Нейтралитет", 0xAAAAAA, false),
    ALLIANCE("Военный союз", 0x55FF55, false),
    NON_AGGRESSION("Пакт о ненападении", 0x55FFFF, false),
    COLD_WAR("Холодная война / Эмбарго", 0xFFAA00, true),
    WAR("Война", 0xFF3333, true);

    private final String label;
    private final int color;
    private final boolean hostile;

    RelationType(String label, int color, boolean hostile) {
        this.label = label;
        this.color = color;
        this.hostile = hostile;
    }

    public String getLabel() {
        return label;
    }

    public int getColor() {
        return color;
    }

    public boolean isHostile() {
        return hostile;
    }
}
