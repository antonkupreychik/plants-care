package com.plantcare.admin.storage.repository;

import com.plantcare.admin.storage.dto.PhotoRowDto;
import com.plantcare.admin.storage.dto.StorageDailyPointDto;
import com.plantcare.admin.storage.dto.StorageOverviewDto;
import com.plantcare.admin.storage.dto.TopUserStorageDto;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Агрегаты и выборки для /admin/storage (issue #101).
 *
 * <p>Узкий админский репозиторий на {@code JdbcTemplate} — как
 * {@code AdminStuckRepository}: плоские проекции под конкретную страницу, без
 * втягивания JPA-графов Photo/Plant/User по lazy-fetch.
 *
 * <p>Ключевое различие в предикатах, которое легко перепутать:
 * {@code purged_at IS NULL} — «объект физически в бакете» (за это платим),
 * {@code deleted_at IS NULL} — «фото видно пользователю».
 */
@Repository
@RequiredArgsConstructor
public class AdminStorageRepository {

    /**
     * Один проход по photos вместо четырёх: агрегаты считаются
     * FILTER-выражениями. COALESCE — на пустой таблице SUM даёт NULL.
     */
    private static final String OVERVIEW_SQL = """
            SELECT
                COALESCE(SUM(size_bytes) FILTER (WHERE purged_at IS NULL), 0)  AS bucket_bytes,
                COUNT(*)                 FILTER (WHERE purged_at IS NULL)      AS bucket_count,
                COALESCE(SUM(size_bytes) FILTER (WHERE deleted_at IS NULL
                                                   AND purged_at IS NULL), 0)  AS active_bytes,
                COUNT(*)                 FILTER (WHERE deleted_at IS NULL
                                                   AND purged_at IS NULL)      AS active_count,
                COALESCE(SUM(size_bytes) FILTER (WHERE deleted_at IS NOT NULL
                                                   AND purged_at IS NULL), 0)  AS pending_bytes,
                COUNT(*)                 FILTER (WHERE deleted_at IS NOT NULL
                                                   AND purged_at IS NULL)      AS pending_count,
                COUNT(*)                 FILTER (WHERE purged_at IS NOT NULL)  AS purged_count
            FROM photos
            """;

    /**
     * Плоская проекция фото + владелец + (опционально) растение из таймлайна.
     *
     * <p>LEFT JOIN LATERAL с {@code LIMIT 1}, а не обычный join: на
     * {@code plant_progress_photos.photo_id} нет UNIQUE, и обычный join
     * размножил бы строку фото при нескольких ссылках — счётчик пагинации
     * разошёлся бы с длиной списка. Растения может не быть вовсе (фото
     * загружено через REST само по себе), отсюда LEFT.
     */
    private static final String PHOTO_ROW_SELECT = """
            SELECT p.id, p.user_id, u.telegram_chat_id, u.username,
                   pl.id AS plant_id, pl.name AS plant_name,
                   p.storage_key, p.content_type, p.size_bytes,
                   p.created_at, p.deleted_at, p.purged_at
            FROM photos p
            JOIN users u ON u.id = p.user_id
            LEFT JOIN LATERAL (
                SELECT ppp.plant_id
                FROM plant_progress_photos ppp
                WHERE ppp.photo_id = p.id
                LIMIT 1
            ) link ON true
            LEFT JOIN plants pl ON pl.id = link.plant_id
            """;

    private static final RowMapper<PhotoRowDto> PHOTO_ROW_MAPPER = (rs, rowNum) -> new PhotoRowDto(
            rs.getLong("id"),
            rs.getLong("user_id"),
            nullableLong(rs, "telegram_chat_id"),
            rs.getString("username"),
            nullableLong(rs, "plant_id"),
            rs.getString("plant_name"),
            rs.getString("storage_key"),
            rs.getString("content_type"),
            rs.getLong("size_bytes"),
            instant(rs, "created_at"),
            instant(rs, "deleted_at"),
            instant(rs, "purged_at"),
            null);

    private final JdbcTemplate jdbcTemplate;

    /** Карточки дашборда. Цена и retention приходят из конфига, не из БД. */
    public StorageOverviewDto loadOverview(double pricePerGbMonth, int retentionDays) {
        StorageOverviewDto result = jdbcTemplate.query(OVERVIEW_SQL, rs -> {
            if (!rs.next()) {
                return StorageOverviewDto.empty(pricePerGbMonth, retentionDays);
            }
            return new StorageOverviewDto(
                    rs.getLong("bucket_bytes"),
                    rs.getLong("bucket_count"),
                    rs.getLong("active_bytes"),
                    rs.getLong("active_count"),
                    rs.getLong("pending_bytes"),
                    rs.getLong("pending_count"),
                    rs.getLong("purged_count"),
                    pricePerGbMonth,
                    retentionDays);
        });
        return result == null ? StorageOverviewDto.empty(pricePerGbMonth, retentionDays) : result;
    }

    /** График роста: снапшоты из storage_metrics начиная с указанной даты. */
    public List<StorageDailyPointDto> findGrowthSince(LocalDate from) {
        return jdbcTemplate.query("""
                        SELECT metric_date, total_bytes, total_count
                        FROM storage_metrics
                        WHERE metric_date >= ?
                        ORDER BY metric_date ASC
                        """,
                (rs, rowNum) -> new StorageDailyPointDto(
                        rs.getObject("metric_date", LocalDate.class),
                        rs.getLong("total_bytes"),
                        rs.getLong("total_count")),
                from);
    }

    /** Топ юзеров по объёму, физически занятому в бакете. */
    public List<TopUserStorageDto> findTopUsersByVolume(int limit) {
        return jdbcTemplate.query("""
                        SELECT p.user_id, u.telegram_chat_id, u.username,
                               SUM(p.size_bytes) AS total_bytes,
                               COUNT(*)          AS photo_count
                        FROM photos p
                        JOIN users u ON u.id = p.user_id
                        WHERE p.purged_at IS NULL
                        GROUP BY p.user_id, u.telegram_chat_id, u.username
                        ORDER BY total_bytes DESC
                        LIMIT ?
                        """,
                (rs, rowNum) -> new TopUserStorageDto(
                        rs.getLong("user_id"),
                        nullableLong(rs, "telegram_chat_id"),
                        rs.getString("username"),
                        rs.getLong("total_bytes"),
                        rs.getLong("photo_count")),
                limit);
    }

    /** Всего записей в реестре фото — знаменатель пагинации «последних загрузок». */
    public long countAllPhotos() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM photos", Long.class);
        return count == null ? 0L : count;
    }

    /** Страница последних загрузок, свежие сверху. Показываем и удалённые — для quick check. */
    public List<PhotoRowDto> findRecentUploads(int limit, long offset) {
        return jdbcTemplate.query(
                PHOTO_ROW_SELECT + " ORDER BY p.created_at DESC, p.id DESC LIMIT ? OFFSET ?",
                PHOTO_ROW_MAPPER, limit, offset);
    }

    /** Все фото юзера для грида на /admin/users/{id}, свежие сверху. */
    public List<PhotoRowDto> findPhotosByUser(long userId, int limit) {
        return jdbcTemplate.query(
                PHOTO_ROW_SELECT + " WHERE p.user_id = ? ORDER BY p.created_at DESC, p.id DESC LIMIT ?",
                PHOTO_ROW_MAPPER, userId, limit);
    }

    /**
     * Снапшот текущего объёма в бакете под суточную метрику.
     * UPSERT по {@code metric_date}: повторный прогон за те же сутки
     * перезаписывает строку, а не падает на PK.
     */
    public void upsertMetric(LocalDate date, long totalBytes, long totalCount, Instant collectedAt) {
        jdbcTemplate.update("""
                        INSERT INTO storage_metrics (metric_date, total_bytes, total_count, collected_at)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT (metric_date) DO UPDATE
                            SET total_bytes  = EXCLUDED.total_bytes,
                                total_count  = EXCLUDED.total_count,
                                collected_at = EXCLUDED.collected_at
                        """,
                date, totalBytes, totalCount, Timestamp.from(collectedAt));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
