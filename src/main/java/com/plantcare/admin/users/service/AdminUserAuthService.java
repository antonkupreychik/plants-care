package com.plantcare.admin.users.service;

import com.plantcare.admin.users.dto.AuthProviderDto;
import com.plantcare.admin.users.dto.AuthProviderKind;
import com.plantcare.admin.users.dto.LinkHistoryItemDto;
import com.plantcare.admin.users.dto.LinkHistoryPageDto;
import com.plantcare.admin.users.dto.UserIdentitiesDto;
import com.plantcare.admin.users.repository.AdminUserAuthRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Секция «Аутентификация» на странице юзера в админке (issue #93):
 * какие провайдеры привязаны, история привязок и разрыв привязки.
 *
 * <p><b>Что здесь НЕ реализовано и почему.</b> Issue заявлена как зависимая от
 * issue #89 (связь User ↔ mobile_account через QR-код). Сущности
 * {@code mobile_account} и отдельной таблицы привязок в схеме нет, поэтому:
 * слияние аккаунтов бот↔приложение, миграция привязки между аккаунтами,
 * per-provider дата привязки, IP и {@code source/target_provider} в истории —
 * вне этой задачи. Здесь работает всё, что выводится из колонок-идентификаторов
 * в {@code users} (V31) и из {@code magic_link_tokens} (V32).
 *
 * <p><b>Логи.</b> Ни email, ни {@code apple_subject}/{@code google_subject},
 * ни chat_id в логи не попадают — только {@code user_id} и имя провайдера.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserAuthService {

    /** Размер страницы истории привязок. */
    public static final int HISTORY_PAGE_SIZE = 20;

    private final AdminUserAuthRepository repository;
    private final Clock clock;

    /**
     * Все четыре провайдера в фиксированном порядке — и привязанные, и нет:
     * саппорту важно видеть «Google не привязан» так же, как и привязанный.
     */
    @Transactional(readOnly = true)
    public List<AuthProviderDto> loadProviders(long userId) {
        UserIdentitiesDto identities = requireIdentities(userId);

        List<AuthProviderDto> providers = new ArrayList<>(4);
        providers.add(telegram(identities));
        providers.add(email(identities));
        providers.add(subject(AuthProviderKind.APPLE, identities.appleSubject()));
        providers.add(subject(AuthProviderKind.GOOGLE, identities.googleSubject()));
        return providers;
    }

    private AuthProviderDto telegram(UserIdentitiesDto identities) {
        Long chatId = identities.telegramChatId();
        return chatId == null
                ? AuthProviderDto.absent(AuthProviderKind.TELEGRAM)
                : new AuthProviderDto(AuthProviderKind.TELEGRAM, true,
                        IdentifierMasker.maskChatId(chatId), false, null, null);
    }

    private AuthProviderDto email(UserIdentitiesDto identities) {
        String value = identities.email();
        if (value == null || value.isBlank()) {
            return AuthProviderDto.absent(AuthProviderKind.EMAIL);
        }
        Instant lastUsed = repository.findLastMagicLinkUse(value).orElse(null);
        return new AuthProviderDto(AuthProviderKind.EMAIL, true,
                IdentifierMasker.maskEmail(value), identities.emailVerified(), null, lastUsed);
    }

    private static AuthProviderDto subject(AuthProviderKind kind, String value) {
        return value == null || value.isBlank()
                ? AuthProviderDto.absent(kind)
                : new AuthProviderDto(kind, true, IdentifierMasker.maskSubject(value), false, null, null);
    }

    /** Сколько провайдеров из четырёх привязано прямо сейчас. */
    @Transactional(readOnly = true)
    public int countLinked(long userId) {
        return countLinked(requireIdentities(userId));
    }

    private static int countLinked(UserIdentitiesDto identities) {
        int count = 0;
        if (identities.telegramChatId() != null) count++;
        if (isSet(identities.email())) count++;
        if (isSet(identities.appleSubject())) count++;
        if (isSet(identities.googleSubject())) count++;
        return count;
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Разрывает привязку провайдера: обнуляет соответствующий идентификатор и
     * инвалидирует ранее выданные refresh-токены.
     *
     * <p>Действие деструктивное и необратимое (в частности, {@code apple_subject}
     * восстановить нельзя — его отдаёт только сам Apple при входе), поэтому UI
     * требует двойного подтверждения. Разрыв последней привязки не запрещается,
     * но возвращается в {@link UnlinkResult#remainingProviders()} = 0, и вызывающий
     * обязан показать предупреждение «юзер не сможет залогиниться».
     *
     * @throws ResponseStatusException 404, если юзера нет
     * @throws IllegalArgumentException если провайдер не был привязан
     */
    @Transactional
    public UnlinkResult unlink(long userId, AuthProviderKind provider, String adminName) {
        UserIdentitiesDto identities = requireIdentities(userId);
        if (!isLinked(identities, provider)) {
            throw new IllegalArgumentException(
                    "Провайдер " + provider.getLabel() + " не привязан к этому юзеру");
        }

        Instant epoch = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        int updated = repository.unlink(userId, provider, epoch);
        if (updated == 0) {
            throw notFound(userId);
        }

        int remaining = countLinked(identities) - 1;
        boolean canStillAuthenticate = remaining > 0 || identities.hasGuestDevice();
        log.info("Admin action AUTH_UNLINK: user_id={}, provider={}, remaining_providers={}, "
                        + "can_still_authenticate={}, admin={}",
                userId, provider, remaining, canStillAuthenticate, adminName);
        if (!canStillAuthenticate) {
            log.warn("Admin action AUTH_UNLINK left user_id={} without any auth provider", userId);
        }
        return new UnlinkResult(provider, remaining, canStillAuthenticate);
    }

    private static boolean isLinked(UserIdentitiesDto identities, AuthProviderKind provider) {
        return switch (provider) {
            case TELEGRAM -> identities.telegramChatId() != null;
            case EMAIL -> isSet(identities.email());
            case APPLE -> isSet(identities.appleSubject());
            case GOOGLE -> isSet(identities.googleSubject());
        };
    }

    /**
     * История привязок за период. Источник — {@code magic_link_tokens}, ключ там
     * email, а не {@code user_id}, поэтому у юзера без email история недоступна
     * в принципе (см. {@link LinkHistoryPageDto#available()}).
     *
     * @param page номер страницы с 1; значения меньше 1 подтягиваются к 1
     * @param from нижняя граница по дате (включительно), может быть {@code null}
     * @param to   верхняя граница по дате (включительно), может быть {@code null}
     */
    @Transactional(readOnly = true)
    public LinkHistoryPageDto loadLinkHistory(long userId, LocalDate from, LocalDate to, int page) {
        UserIdentitiesDto identities = requireIdentities(userId);
        int currentPage = Math.max(1, page);
        if (!isSet(identities.email())) {
            return LinkHistoryPageDto.unavailable(HISTORY_PAGE_SIZE, from, to);
        }

        // to трактуем включительно: пользователь выбирает «по 5 мая» и ждёт,
        // что события 5 мая попадут в выборку → в SQL это < 6 мая 00:00 UTC.
        Instant fromInstant = from == null ? null : from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toInstant = to == null ? null : to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        int offset = (currentPage - 1) * HISTORY_PAGE_SIZE;
        var rows = repository.findLinkHistory(identities.email(), fromInstant, toInstant,
                offset, HISTORY_PAGE_SIZE + 1);
        boolean hasNext = rows.size() > HISTORY_PAGE_SIZE;
        Instant now = clock.instant();
        List<LinkHistoryItemDto> items = rows.stream()
                .limit(HISTORY_PAGE_SIZE)
                .map(r -> LinkHistoryItemDto.of(r.createdAt(), r.expiresAt(), r.consumedAt(), now))
                .toList();

        return new LinkHistoryPageDto(items, currentPage, HISTORY_PAGE_SIZE, hasNext, from, to, true);
    }

    private UserIdentitiesDto requireIdentities(long userId) {
        return repository.findIdentities(userId).orElseThrow(() -> notFound(userId));
    }

    private static ResponseStatusException notFound(long userId) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + userId);
    }

    /**
     * @param remainingProviders      сколько привязок осталось после разрыва
     * @param canStillAuthenticate    остался ли юзеру хоть какой-то способ входа
     *                                (провайдер или гостевое устройство)
     */
    public record UnlinkResult(AuthProviderKind provider, int remainingProviders,
                               boolean canStillAuthenticate) {
    }
}
