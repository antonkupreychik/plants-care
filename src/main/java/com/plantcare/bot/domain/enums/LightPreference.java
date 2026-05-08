package com.plantcare.bot.domain.enums;

public enum LightPreference {
    SHADE("Тень"), PARTIAL("Полутень"), BRIGHT("Яркий"), DIRECT("Прямые лучи");

    public final String label;
    LightPreference(String label) { this.label = label; }
}