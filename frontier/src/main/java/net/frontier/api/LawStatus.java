package net.frontier.api;

public enum LawStatus {
    DRAFT("Законопроект"),
    ENACTED("Вступил в силу"),
    REPEALED("Отменён");

    private final String label;

    LawStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
