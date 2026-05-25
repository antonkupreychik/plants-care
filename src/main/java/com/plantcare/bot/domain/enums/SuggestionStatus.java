package com.plantcare.bot.domain.enums;

/**
 * Статус подсказки расходников перед пересадкой (issue #141).
 *
 * <ul>
 *   <li>{@link #SUGGESTED} — нотификация отправлена, ждём реакции юзера;</li>
 *   <li>{@link #ADDED} — юзер нажал «В список покупок», позиции добавлены в shopping_items;</li>
 *   <li>{@link #DISMISSED} — юзер нажал «Не нужно».</li>
 * </ul>
 */
public enum SuggestionStatus {

    SUGGESTED,
    ADDED,
    DISMISSED
}
