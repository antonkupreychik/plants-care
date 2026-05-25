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
 * <p>Все три слоя доставки выделены в свои top-level пакеты: {@code com.plantcare.bot},
 * {@code com.plantcare.admin}, {@code com.plantcare.api}. {@code core} не должен зависеть
 * ни от одного из них.
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

    /**
     * Слои доставки между собой не связаны: REST API ({@code api}) не должен знать про
     * Telegram-бот ({@code bot}) и веб-админку ({@code admin}). API зависит только от
     * {@code core} (бизнес-сервисы) и собственного сгенерированного слоя
     * {@code api.generated}. Это держит мобильный канал доставки изолированным (#127, Mobile Phase 0).
     */
    @ArchTest
    static final ArchRule api_must_not_depend_on_other_delivery_layers =
            noClasses()
                    .that().resideInAPackage("com.plantcare.api..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            "com.plantcare.bot..",
                            "com.plantcare.admin..")
                    .because("REST API — независимый слой доставки и не должен зависеть от "
                            + "Telegram-бота или админки; только от core (#127).");
}
