package net.frontier.api;

public enum Role {
    LEADER("Лидер", 4),
    OFFICER("Офицер", 3),
    JUDGE("Судья / Дознаватель", 2),
    CITIZEN("Гражданин", 1),
    RECRUIT("Новобранец", 0);

    private final String displayName;
    private final int level;

    Role(String displayName, int level) {
        this.displayName = displayName;
        this.level = level;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getLevel() {
        return level;
    }

    public boolean canManageLaws() {
        return this.level >= OFFICER.level;
    }

    public boolean canInvestigate() {
        return this.level >= JUDGE.level;
    }

    public boolean canDeclareWarOrDiplomacy() {
        return this == LEADER;
    }

    public boolean canManageTerritory() {
        return this.level >= OFFICER.level;
    }
}
