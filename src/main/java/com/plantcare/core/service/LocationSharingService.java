package com.plantcare.core.service;

import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.LocationAccess;
import com.plantcare.core.domain.LocationInvite;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.AccessRole;
import com.plantcare.core.repository.LocationAccessRepository;
import com.plantcare.core.repository.LocationInviteRepository;
import com.plantcare.core.repository.LocationRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Совместный уход (issue #77). Управляет приглашениями к локации и доступами по
 * модели SUBO: Subject (пользователь) ↔ Object (локация) ↔ Role.
 *
 * <p>Поток:
 * <ol>
 *   <li>{@link #createInvite(Long, Long)} — владелец генерирует одноразовый токен.</li>
 *   <li>Бот строит deep link {@code t.me/<bot>?start=invite_<token>}.</li>
 *   <li>{@link #acceptInvite(String, User)} — приглашённый переходит по ссылке,
 *       создаётся {@link LocationAccess}.</li>
 *   <li>{@link #subjectUserIdsForLocation(Long)} — воркер шедулера собирает всех
 *       caretaker'ов для веерной рассылки уведомлений.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocationSharingService {

    /** Срок жизни приглашения. Просроченная ссылка перестаёт приниматься. */
    static final Duration INVITE_TTL = Duration.ofDays(7);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final LocationRepository locationRepository;
    private final LocationAccessRepository locationAccessRepository;
    private final LocationInviteRepository locationInviteRepository;
    private final Clock clock;

    /**
     * Сгенерировать одноразовое приглашение к локации владельца. Возвращает
     * только что выпущенный токен — бот вставляет его в deep link.
     *
     * @throws IllegalArgumentException если локация не принадлежит пользователю
     */
    @Transactional
    public LocationInvite createInvite(Long ownerUserId, Long locationId) {
        Location location = locationRepository.findByUserIdAndId(ownerUserId, locationId)
                .orElseThrow(() -> new IllegalArgumentException("Комната не найдена"));

        User owner = location.getUser();
        String token = generateToken();
        Instant expiresAt = clock.instant().plus(INVITE_TTL);

        LocationInvite invite = locationInviteRepository.save(
                new LocationInvite(token, location, owner, AccessRole.CARETAKER, expiresAt));

        log.info("Created sharing invite {} for location {} by owner {}",
                invite.getId(), locationId, ownerUserId);

        return invite;
    }

    /**
     * Принять приглашение по токену. Идемпотентно по паре (subject, location):
     * повторный переход по той же ссылке или второй ссылке на ту же локацию не
     * плодит дублей доступа. Одноразовость токена обеспечивается отметкой
     * {@code acceptedAt}.
     */
    @Transactional
    public AcceptResult acceptInvite(String token, User invitee) {
        LocationInvite invite = locationInviteRepository.findByToken(token).orElse(null);
        if (invite == null) {
            return AcceptResult.notFound();
        }

        Instant now = clock.instant();
        if (invite.isExpired(now)) {
            return AcceptResult.expired();
        }
        if (invite.isAccepted()) {
            // Если токен принял тот же пользователь — идемпотентно возвращаем успех.
            // Если другой — токен одноразовый и уже использован.
            Location loc = invite.getLocation();
            if (invite.getAcceptedBy() != null && invite.getAcceptedBy().getId().equals(invitee.getId())) {
                return AcceptResult.accepted(loc.getDisplayName(), loc.getId(), invite.getRole());
            }
            return AcceptResult.alreadyUsed();
        }

        Location location = invite.getLocation();

        // Владелец не приглашает сам себя — это не доступ, а владение.
        if (location.getUser().getId().equals(invitee.getId())) {
            return AcceptResult.ownInvite(location.getDisplayName());
        }

        // Идемпотентность: доступ уже есть → не создаём дубль, но токен гасим.
        boolean alreadyHadAccess = locationAccessRepository
                .existsBySubjectIdAndObjectId(invitee.getId(), location.getId());
        if (!alreadyHadAccess) {
            locationAccessRepository.save(
                    new LocationAccess(invitee, location, invite.getRole()));
        }

        invite.setAcceptedAt(now);
        invite.setAcceptedBy(invitee);
        locationInviteRepository.save(invite);

        log.info("Invite {} accepted by user {} for location {} (newAccess={})",
                invite.getId(), invitee.getId(), location.getId(), !alreadyHadAccess);

        return AcceptResult.accepted(location.getDisplayName(), location.getId(), invite.getRole());
    }

    /**
     * Id всех subject-пользователей (caretaker'ов) с доступом к локации — для
     * веерной рассылки уведомлений шедулером (issue #77). Владелец сюда не входит.
     */
    @Transactional(readOnly = true)
    public List<Long> subjectUserIdsForLocation(Long locationId) {
        if (locationId == null) {
            return List.of();
        }
        return locationAccessRepository.findSubjectUserIdsByLocationId(locationId);
    }

    /**
     * Telegram chat id всех caretaker'ов локации (не владелец) для веерной
     * рассылки уведомлений (issue #77). Возвращает только активных юзеров
     * с непустым chat id.
     */
    @Transactional(readOnly = true)
    public List<Long> caretakerChatIdsForLocation(Long locationId) {
        if (locationId == null) {
            return List.of();
        }
        return locationAccessRepository.findCaretakerChatIdsByLocationId(locationId);
    }

    /**
     * Список всех участников локации (OWNER + CARETAKER'ы) для REST API (issue #251).
     * Доступно OWNER'у и всем CARETAKER'ам локации.
     *
     * @throws EntityNotFoundException  если локация не существует
     * @throws AccessDeniedException    если у caller нет доступа к локации
     */
    @Transactional(readOnly = true)
    public List<MemberInfo> listMembers(Long callerUserId, Long locationId) {
        Location location = locationRepository.findById(locationId)
                .orElseThrow(() -> new EntityNotFoundException("Локация не найдена: " + locationId));

        boolean isOwner = location.getUser().getId().equals(callerUserId);
        boolean isCaretaker = locationAccessRepository.existsBySubjectIdAndObjectId(callerUserId, locationId);

        if (!isOwner && !isCaretaker) {
            throw new AccessDeniedException("Нет доступа к локации");
        }

        List<MemberInfo> members = new ArrayList<>();

        // OWNER — владелец локации (из locations.user_id)
        User owner = location.getUser();
        Instant ownerJoinedAt = location.getCreatedAt() != null
                ? location.getCreatedAt().toInstant(ZoneOffset.UTC)
                : Instant.EPOCH;
        members.add(new MemberInfo(owner.getId(), displayName(owner), AccessRole.OWNER, ownerJoinedAt));

        // CARETAKER'ы — из location_access
        for (LocationAccess access : locationAccessRepository.findAllByObjectId(locationId)) {
            User subject = access.getSubject();
            members.add(new MemberInfo(subject.getId(), displayName(subject), access.getRole(), access.getCreatedAt()));
        }

        return members;
    }

    /**
     * Отозвать доступ пользователя к локации (REST API, issue #251).
     * Только OWNER может отзывать доступ. Нельзя удалить последнего OWNER'а.
     *
     * @throws EntityNotFoundException  если локация или пользователь не найдены
     * @throws AccessDeniedException    если caller не является OWNER'ом
     * @throws IllegalStateException    если попытка удалить единственного OWNER'а
     */
    @Transactional
    public void revokeAccess(Long callerUserId, Long locationId, Long targetUserId) {
        Location location = locationRepository.findById(locationId)
                .orElseThrow(() -> new EntityNotFoundException("Локация не найдена: " + locationId));

        if (!location.getUser().getId().equals(callerUserId)) {
            throw new AccessDeniedException("Только владелец может отзывать доступ");
        }

        // Нельзя удалить самого владельца (единственного OWNER'а)
        if (location.getUser().getId().equals(targetUserId)) {
            throw new IllegalStateException("Нельзя удалить последнего владельца локации");
        }

        LocationAccess access = locationAccessRepository
                .findBySubjectIdAndObjectId(targetUserId, locationId)
                .orElseThrow(() -> new EntityNotFoundException("У пользователя нет доступа к этой локации"));

        locationAccessRepository.delete(access);

        log.info("Revoked access for user {} to location {} by owner {}", targetUserId, locationId, callerUserId);
    }

    /**
     * Список локаций, к которым у пользователя есть доступ как CARETAKER (REST API, issue #251).
     * Не включает собственные локации пользователя.
     */
    @Transactional(readOnly = true)
    public List<SharedLocationInfo> listSharedLocations(Long userId) {
        return locationAccessRepository.findAllBySubjectId(userId).stream()
                .map(access -> {
                    Location loc = access.getObject();
                    return new SharedLocationInfo(
                            loc.getId(),
                            loc.getName(),
                            loc.getEmoji(),
                            loc.getUser().getId(),
                            access.getRole(),
                            access.getCreatedAt()
                    );
                })
                .toList();
    }

    // ===== DTOs для REST API =====

    /**
     * Участник локации для REST-ответа (issue #251).
     *
     * @param userId      id пользователя
     * @param displayName отображаемое имя (username или email)
     * @param role        роль в локации (OWNER/CARETAKER)
     * @param joinedAt    когда появился доступ (createdAt локации для OWNER'а)
     */
    public record MemberInfo(Long userId, String displayName, AccessRole role, Instant joinedAt) {}

    /**
     * Расшаренная локация для REST-ответа (issue #251).
     *
     * @param locationId   id локации
     * @param name         название
     * @param emoji        emoji или null
     * @param ownerUserId  id владельца
     * @param role         роль caller'а (CARETAKER)
     * @param joinedAt     когда выдан доступ
     */
    public record SharedLocationInfo(Long locationId, String name, String emoji,
                                      Long ownerUserId, AccessRole role, Instant joinedAt) {}

    private static String displayName(User user) {
        if (user.getUsername() != null && !user.getUsername().isBlank()) {
            return "@" + user.getUsername();
        }
        return user.getEmail();
    }

    private String generateToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return URL_ENCODER.encodeToString(bytes);
    }

    /** Результат попытки принять приглашение. */
    public record AcceptResult(Status status, String locationName, Long locationId, AccessRole role) {
        public enum Status { ACCEPTED, ALREADY_USED, EXPIRED, NOT_FOUND, OWN_INVITE }

        public static AcceptResult accepted(String locationName, Long locationId, AccessRole role) {
            return new AcceptResult(Status.ACCEPTED, locationName, locationId, role);
        }

        public static AcceptResult alreadyUsed() {
            return new AcceptResult(Status.ALREADY_USED, null, null, null);
        }

        public static AcceptResult expired() {
            return new AcceptResult(Status.EXPIRED, null, null, null);
        }

        public static AcceptResult notFound() {
            return new AcceptResult(Status.NOT_FOUND, null, null, null);
        }

        public static AcceptResult ownInvite(String locationName) {
            return new AcceptResult(Status.OWN_INVITE, locationName, null, null);
        }

        public boolean isAccepted() {
            return status == Status.ACCEPTED;
        }
    }
}
