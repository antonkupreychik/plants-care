package com.plantcare.bot.service;

import com.plantcare.bot.domain.TransplantSupplySuggestion;
import com.plantcare.bot.repository.TransplantSupplySuggestionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Вспомогательный компонент: вставка строки-трекера подсказки в ОТДЕЛЬНОЙ
 * транзакции ({@link Propagation#REQUIRES_NEW}).
 *
 * <p>Зачем отдельный бин и отдельная транзакция: id у
 * {@link TransplantSupplySuggestion} генерируется стратегией IDENTITY, поэтому
 * {@code saveAndFlush} выполняет INSERT немедленно. При нарушении UNIQUE
 * {@code (user_id, plant_id, source_event_id)} (гонка/повтор тика) откат
 * конфликтной вставки изолирован в этой вложенной транзакции и НЕ помечает
 * внешнюю транзакцию {@code recordSuggested} как rollback-only. Благодаря этому
 * вызывающий метод может поймать {@code DataIntegrityViolationException} и
 * корректно вернуть {@code Optional.empty()} вместо падения
 * {@code UnexpectedRollbackException} на коммите внешней транзакции.
 *
 * <p>Вынесено в отдельный бин намеренно: self-invocation внутри
 * {@code TransplantSuggestionService} не прошёл бы через прокси и не дал бы
 * новую транзакцию.
 */
@Component
@RequiredArgsConstructor
class TransplantSuggestionInserter {

    private final TransplantSupplySuggestionRepository suggestionRepository;

    /**
     * Вставляет строку-трекер во вложенной транзакции и сразу флашит, чтобы
     * нарушение UNIQUE всплыло здесь (в изолированной транзакции), а не на
     * коммите внешней.
     *
     * @return id созданной строки
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long insert(TransplantSupplySuggestion suggestion) {
        return suggestionRepository.saveAndFlush(suggestion).getId();
    }
}
