package com.plantcare.core.domain.featureflag;

import java.util.List;

/**
 * Реестр известных feature flag'ов (issue #78). Это константы для code-side
 * type-safety: вместо «волшебной строки» в местах проверки используется
 * {@code user.hasFeature(FeatureFlag.SHARING)}.
 *
 * <p>Каталог обновляется по мере появления experimental-фич. Не все флаги,
 * которые могут быть у юзера, обязаны быть в этом списке — админка позволяет
 * также сетить ad-hoc флаги без правки кода (для совсем экспериментальных
 * вещей до того, как они доехали до релиза).
 *
 * <p>В админ-UI этот список рендерится как набор toggle'ов «известные флаги»,
 * а ad-hoc флаги, отсутствующие здесь, показываются ниже отдельной секцией.
 */
public final class FeatureFlag {

    private FeatureFlag() {}

    /** Шеринг растений между юзерами (issue ещё в работе). */
    public static final String SHARING = "sharing";

    /** AI-диагностика по фотографии. */
    public static final String AUTO_DIAGNOSIS = "auto_diagnosis";

    /**
     * Календарь ухода — недельный обзор задач (issue #52). Сейчас закрыт
     * флагом для гранулярного rollout'а: включаем у себя и тестировщиков
     * прежде чем показывать всем.
     */
    public static final String CALENDAR = "calendar";

    /** Все известные коды флагов — рендерятся как toggle'ы в админке. */
    public static final List<String> CATALOG = List.of(
            SHARING,
            AUTO_DIAGNOSIS,
            CALENDAR
    );

    /**
     * Краткое описание флага для UI — что именно тестирует флаг.
     * Возвращает {@code null} для неизвестных кодов.
     */
    public static String describe(String code) {
        if (code == null) return null;
        return switch (code) {
            case SHARING        -> "Шеринг растений между юзерами";
            case AUTO_DIAGNOSIS -> "AI-диагностика по фотографии";
            case CALENDAR       -> "Календарь ухода на неделю (/calendar)";
            default             -> null;
        };
    }
}
