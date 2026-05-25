package com.plantcare.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Issue #127: фитнес-функция границ модульного монолита.
 *
 * <p>Правило зависимостей: всё течёт внутрь, к {@code core}. Ядро (domain / repository /
 * бизнес-сервисы) не должно знать про слои доставки — Telegram-бот ({@code bot}),
 * веб-админку ({@code admin}) и REST API ({@code api}). Слои доставки зависеть от
 * {@code core} могут — это разрешённое направление.
 *
 * <p>На момент Фазы 1 код {@code admin}/{@code api} физически ещё лежит под
 * {@code com.plantcare.bot.*}, поэтому ключевой барьер — {@code core ∌ bot}. Паттерны
 * {@code admin..}/{@code api..} перечислены заранее, чтобы правило не пришлось менять,
 * когда эти слои переедут в свои пакеты (Фазы 3–4).
 */
@AnalyzeClasses(packages = "com.plantcare", importOptions = ImportOption.DoNotIncludeTests.class)
class LayeredArchitectureTest {

    @ArchTest
    static final ArchRule core_must_not_depend_on_delivery_layers =
            noClasses()
                    .that().resideInAPackage("com.plantcare.core..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            "com.plantcare.bot..",
                            "com.plantcare.admin..",
                            "com.plantcare.api..")
                    .because("core — ядро модульного монолита и не должно знать про слои доставки "
                            + "(bot/admin/api). Зависимости текут только внутрь, к core (#127).");

    /**
     * Telegram API ({@code org.telegram.*}) — деталь слоя доставки (бот). Если core строит
     * клавиатуры / SendMessage, значит delivery протёк в ядро (как было с weather/seasonal/
     * diagnosis menu-сервисами). Правило {@code core ∌ bot} такие утечки НЕ ловит, потому что
     * telegrambots — сторонняя библиотека, а не пакет {@code com.plantcare}.
     */
    @ArchTest
    static final ArchRule core_must_not_use_telegram_api =
            noClasses()
                    .that().resideInAPackage("com.plantcare.core..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("org.telegram..")
                    .because("Telegram API — деталь доставки; core не должен формировать "
                            + "сообщения/клавиатуры Telegram (#127).");
}
