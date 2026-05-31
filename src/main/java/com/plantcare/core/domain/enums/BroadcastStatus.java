package com.plantcare.core.domain.enums;

/**
 * Жизненный цикл админ-рассылки (issue #60). Хранится в БД с CHECK-констрейнтом.
 */
public enum BroadcastStatus {

    /** Сейчас идёт цикл отправки. В этом состоянии возможен только один broadcast. */
    RUNNING,

    /** Цикл завершился сам. {@code sent_count + failed_count + blocked_count = total}. */
    DONE,

    /** Админ нажал «Остановить» в процессе. Уже отправленные не отзываются. */
    STOPPED,

    /** Цикл упал по неожиданной причине (например, БД отвалилась). См. логи. */
    FAILED
}
