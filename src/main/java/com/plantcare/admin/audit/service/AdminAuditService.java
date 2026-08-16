package com.plantcare.admin.audit.service;

import com.plantcare.admin.audit.AdminAuditAction;
import com.plantcare.admin.audit.dto.AdminAuditEntryDto;
import com.plantcare.admin.audit.dto.AdminAuditFilter;
import com.plantcare.admin.audit.dto.AdminAuditPageDto;
import com.plantcare.admin.audit.repository.AdminAuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Запись и чтение аудит-лога админских действий (issue #98).
 *
 * <p>Запись — ручным вызовом {@link #log}, а не AOP-аспектом с {@code @Audited}.
 * Аспект «не забывчивее», но у него нет доступа к тому, что на самом деле
 * изменилось: осмысленный {@code details} (старое → новое значение, размер
 * аудитории рассылки, длина сообщения) собирается только в теле метода.
 * Явный вызов рядом с уже существующим {@code log.info(...)} стоит одну
 * строку и не прячет поведение за прокси.
 *
 * <p>Инвариант: аудит никогда не ломает само действие. Любая ошибка записи
 * логируется как ERROR и проглатывается — админ не должен получить 500 от
 * того, что не записался лог уже выполненной операции.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AdminAuditService {

    /** Размер страницы на /admin/audit. */
    public static final int PAGE_SIZE = 50;

    /** Сколько записей показываем в секции истории на карточке объекта. */
    public static final int TARGET_HISTORY_LIMIT = 10;

    private static final DateTimeFormatter CSV_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private static final String CSV_HEADER =
            "id,occurred_at_utc,admin,action,target_type,target_id,request_ip,details";

    private final AdminAuditLogRepository repository;
    private final ObjectMapper objectMapper;

    // ------------------------------------------------------------------ запись

    /**
     * Записывает событие аудита.
     *
     * @param targetId идентификатор объекта; приводится к строке, чтобы
     *                 колонка одинаково принимала id юзера, вида и рассылки
     * @param details  произвольный контекст (before/after и т.п.); {@code null}
     *                 или пустая map пишутся как SQL NULL
     */
    public void log(AdminAuditAction action, String adminUsername, String targetType,
                    Object targetId, Map<String, Object> details) {
        try {
            repository.insert(
                    action.name(),
                    adminUsername == null || adminUsername.isBlank() ? "unknown" : adminUsername,
                    targetType,
                    targetId == null ? null : String.valueOf(targetId),
                    serializeDetails(details),
                    currentRequestIp());
        } catch (Exception e) {
            log.error("Не удалось записать аудит: action={} admin={} target={}:{} — {}",
                    action, adminUsername, targetType, targetId, e.getMessage(), e);
        }
    }

    /** Короткая форма для действий без дополнительного контекста. */
    public void log(AdminAuditAction action, String adminUsername, String targetType, Object targetId) {
        log(action, adminUsername, targetType, targetId, null);
    }

    /**
     * Сборка {@code details} без литералов {@code Map.of(...)} на вызывающей
     * стороне: сохраняет порядок ключей и не падает на {@code null}-значениях
     * (в отличие от {@code Map.of}), которые для before/after — норма.
     */
    public static Map<String, Object> details(Object... keyValuePairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValuePairs.length; i += 2) {
            result.put(String.valueOf(keyValuePairs[i]), keyValuePairs[i + 1]);
        }
        return result;
    }

    // ------------------------------------------------------------------ чтение

    /** Страница ленты аудита с учётом фильтров. Номер страницы — с единицы. */
    public AdminAuditPageDto search(AdminAuditFilter filter, int page) {
        int currentPage = Math.max(1, page);
        long total = repository.count(filter);
        int totalPages = total == 0 ? 1 : (int) Math.ceil((double) total / PAGE_SIZE);
        if (currentPage > totalPages) {
            currentPage = totalPages;
        }
        List<AdminAuditEntryDto> items =
                repository.search(filter, PAGE_SIZE, (long) (currentPage - 1) * PAGE_SIZE).stream()
                        .map(this::prettifyDetails)
                        .toList();
        return new AdminAuditPageDto(items, currentPage, PAGE_SIZE, total, totalPages);
    }

    /** Последние {@value #TARGET_HISTORY_LIMIT} действий над конкретным объектом. */
    public List<AdminAuditEntryDto> recentForTarget(String targetType, Object targetId) {
        return repository.search(
                        AdminAuditFilter.forTarget(targetType, String.valueOf(targetId)),
                        TARGET_HISTORY_LIMIT, 0).stream()
                .map(this::prettifyDetails)
                .toList();
    }

    public List<String> knownAdmins() {
        return repository.distinctAdmins();
    }

    public List<String> knownTargetTypes() {
        return repository.distinctTargetTypes();
    }

    /**
     * CSV по тем же фильтрам, что и страница, но без пагинации (до
     * {@link AdminAuditLogRepository#EXPORT_LIMIT} строк). Разделитель —
     * запятая, все поля закавычены, кавычка внутри поля удваивается (RFC 4180),
     * поэтому JSON в {@code details} переживает выгрузку целиком.
     */
    public String exportCsv(AdminAuditFilter filter) {
        StringBuilder sb = new StringBuilder(CSV_HEADER).append('\n');
        for (AdminAuditEntryDto e : repository.search(filter, AdminAuditLogRepository.EXPORT_LIMIT, 0)) {
            sb.append(e.id()).append(',')
                    .append(csv(CSV_TIMESTAMP.format(e.occurredAt()))).append(',')
                    .append(csv(e.adminUsername())).append(',')
                    .append(csv(e.action())).append(',')
                    .append(csv(e.targetType())).append(',')
                    .append(csv(e.targetId())).append(',')
                    .append(csv(e.requestIp())).append(',')
                    .append(csv(e.details())).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ helpers

    private String serializeDetails(Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }
        return objectMapper.writeValueAsString(details);
    }

    /**
     * IP запроса, если действие выполнено в HTTP-потоке. Для фоновых потоков
     * (цикл рассылки в собственном executor'е) request-scope недоступен —
     * тогда {@code null}, и это честнее, чем подставлять адрес сервера.
     */
    private static String currentRequestIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            String ip = attrs.getRequest().getRemoteAddr();
            return ip == null || ip.length() > 45 ? null : ip;
        }
        return null;
    }

    /** Форматирует JSON для показа в раскрывающейся деталке; мусор оставляет как есть. */
    private AdminAuditEntryDto prettifyDetails(AdminAuditEntryDto entry) {
        if (!entry.hasDetails()) {
            return entry;
        }
        String pretty = entry.details();
        try {
            pretty = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(objectMapper.readTree(entry.details()));
        } catch (Exception e) {
            log.debug("details не разобрались как JSON (id={}): {}", entry.id(), e.getMessage());
        }
        return new AdminAuditEntryDto(entry.id(), entry.occurredAt(), entry.adminUsername(),
                entry.action(), entry.targetType(), entry.targetId(), pretty, entry.requestIp());
    }

    private static String csv(String value) {
        if (value == null) {
            return "\"\"";
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
