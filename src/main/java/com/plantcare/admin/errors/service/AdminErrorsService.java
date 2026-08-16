package com.plantcare.admin.errors.service;

import com.plantcare.admin.errors.dto.ErrorGroupDto;
import com.plantcare.admin.errors.dto.ErrorLogDetailDto;
import com.plantcare.admin.errors.dto.ErrorLogFilter;
import com.plantcare.admin.errors.dto.ErrorLogItemDto;
import com.plantcare.admin.errors.dto.ErrorLogPageDto;
import com.plantcare.admin.errors.repository.AdminErrorLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * Логика страницы {@code /admin/errors} (issue #97): топ-10 групп, постраничный список с
 * фильтрами и контекст пользователя вокруг конкретной ошибки.
 */
@Service
@RequiredArgsConstructor
public class AdminErrorsService {

    /** Сколько строк на странице списка. */
    public static final int PAGE_SIZE = 50;

    /** Сколько групп в топе — ровно как в AC. */
    public static final int TOP_GROUPS_LIMIT = 10;

    /** Окно топа по умолчанию — «за 24h» из AC. */
    public static final Duration TOP_WINDOW = Duration.ofHours(24);

    /** «Все события юзера за 1 час до ошибки» — окно из AC. */
    public static final Duration USER_CONTEXT_WINDOW = Duration.ofHours(1);

    /** Потолок строк в контексте юзера, чтобы шумный час не положил страницу. */
    private static final int USER_CONTEXT_LIMIT = 200;

    private final AdminErrorLogRepository repository;
    private final Clock clock;

    /** Топ-10 уникальных ошибок за последние 24 часа. */
    public List<ErrorGroupDto> topGroups() {
        return repository.findTopGroups(clock.instant().minus(TOP_WINDOW), TOP_GROUPS_LIMIT);
    }

    /** Страница списка под фильтром. Номер страницы нормализуется к допустимому диапазону. */
    public ErrorLogPageDto page(ErrorLogFilter filter, int requestedPage) {
        long totalItems = repository.count(filter);
        int totalPages = totalItems == 0 ? 1 : (int) Math.ceil((double) totalItems / PAGE_SIZE);
        int page = Math.min(Math.max(1, requestedPage), totalPages);

        List<ErrorLogItemDto> items = repository.find(filter, page, PAGE_SIZE);
        return new ErrorLogPageDto(items, page, PAGE_SIZE, totalItems, totalPages);
    }

    /** Деталка либо {@code null}, если запись уже удалена retention'ом. */
    public ErrorLogDetailDto detail(long id) {
        return repository.findById(id);
    }

    /**
     * События юзера за час до указанной ошибки. Пусто, если у ошибки не было юзера
     * (фон/шедулер/аноним) или самой ошибки уже нет.
     */
    public List<ErrorLogItemDto> userContext(long errorId) {
        ErrorLogDetailDto error = repository.findById(errorId);
        if (error == null || error.userId() == null) {
            return List.of();
        }
        return repository.findUserContext(
                error.userId(), error.createdAt(), USER_CONTEXT_WINDOW, USER_CONTEXT_LIMIT);
    }
}
