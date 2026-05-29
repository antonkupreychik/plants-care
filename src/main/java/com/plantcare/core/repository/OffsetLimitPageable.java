package com.plantcare.core.repository;

import org.springframework.data.domain.AbstractPageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * {@link Pageable} с произвольным абсолютным {@code offset}, не привязанным к
 * номеру страницы. Стандартный {@code PageRequest.of(page, size)} требует, чтобы
 * offset был кратен size (offset = page * size); ленте уведомлений (issue #183)
 * нужен свободный offset/limit, поэтому здесь — отдельная минимальная реализация.
 *
 * <p>Сортировка передаётся снаружи. Навигация по «страницам» здесь не нужна
 * (клиент сам считает offset), поэтому методы перехода кидают
 * {@link UnsupportedOperationException}.
 */
public final class OffsetLimitPageable extends AbstractPageRequest {

    private final long offset;
    private final Sort sort;

    private OffsetLimitPageable(long offset, int limit, Sort sort) {
        super(0, limit);
        this.offset = offset;
        this.sort = sort;
    }

    public static OffsetLimitPageable of(long offset, int limit, Sort sort) {
        return new OffsetLimitPageable(offset, limit, sort);
    }

    @Override
    public long getOffset() {
        return offset;
    }

    @Override
    public Sort getSort() {
        return sort;
    }

    @Override
    public Pageable next() {
        return OffsetLimitPageable.of(offset + getPageSize(), getPageSize(), sort);
    }

    @Override
    public Pageable previous() {
        long previousOffset = Math.max(0, offset - getPageSize());
        return OffsetLimitPageable.of(previousOffset, getPageSize(), sort);
    }

    @Override
    public Pageable first() {
        return OffsetLimitPageable.of(0, getPageSize(), sort);
    }

    @Override
    public Pageable withPage(int pageNumber) {
        return OffsetLimitPageable.of((long) pageNumber * getPageSize(), getPageSize(), sort);
    }
}
