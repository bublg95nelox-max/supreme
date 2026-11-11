package net.frontier.api;

public enum CrimeType {
    BLOCK_BREAK("Разрушение блоков", "Следы сколов и повреждения построек"),
    BLOCK_PLACE("Установка блоков / Саботаж", "Чужеродные материалы и свежая кладка"),
    THEFT("Вскрытие хранилища / Кража", "Следы перебора предметов в сундуке"),
    EXPLOSION("Взрыв / Применение взрывчатки", "Пороховой нагар и осколки снарядов"),
    PVP_KILL("Убийство игрока", "Следы борьбы и пятна крови"),
    TRESPASS("Нарушение границы", "Следы проникновения в закрытую зону");

    private final String title;
    private final String description;

    CrimeType(String title, String description) {
        this.title = title;
        this.description = description;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }
}
