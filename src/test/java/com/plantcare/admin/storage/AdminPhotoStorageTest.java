package com.plantcare.admin.storage;

import com.plantcare.admin.storage.dto.PhotoRowDto;
import com.plantcare.admin.storage.dto.StorageOverviewDto;
import com.plantcare.admin.storage.dto.StoragePageDto;
import com.plantcare.admin.storage.service.AdminPhotoService;
import com.plantcare.admin.storage.service.AdminStorageService;
import com.plantcare.admin.storage.service.PhotoPurgeService;
import com.plantcare.admin.storage.service.StorageMetricService;
import com.plantcare.bot.support.IntegrationTestBase;
import com.plantcare.core.service.PhotoStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /admin/storage и жизненный цикл удаления фото (issue #101).
 *
 * <p>{@link PhotoStorageService} подменён моком: реальных вызовов к S3 в тестах
 * быть не должно. Именно через этот мок и проверяется главный AC —
 * «удалили → через retention объект физически ушёл из бакета»: факт удаления
 * наблюдаем как вызов {@code delete(storageKey)}.
 *
 * <p>Возраст soft-delete задаётся прямо в БД (deleted_at в прошлом), а не
 * подменой {@code Clock}: перемотка часов на 31 день в общем контексте задела бы
 * шедулеры и напоминания, а нужен здесь ровно один предикат — истёк retention
 * или нет.
 */
@AutoConfigureMockMvc
@DisplayName("Admin photo storage — дашборд, удаление и retention (issue #101)")
class AdminPhotoStorageTest extends IntegrationTestBase {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AdminStorageService storageService;
    @Autowired private AdminPhotoService photoService;
    @Autowired private PhotoPurgeService purgeService;
    @Autowired private StorageMetricService metricService;

    /** Мок S3: реальных сетевых вызовов в тестах нет. */
    @MockitoBean private PhotoStorageService photoStorageService;

    private long userId;

    @BeforeEach
    void setUp() {
        userId = jdbc.queryForObject("""
                INSERT INTO users (telegram_chat_id, username, is_blocked)
                VALUES (?, ?, false) RETURNING id
                """, Long.class, 900_101L, "photo_owner");
    }

    @AfterEach
    void cleanup() {
        jdbc.execute("DELETE FROM storage_metrics");
        jdbc.execute("DELETE FROM plant_progress_photos");
        jdbc.execute("DELETE FROM photos");
        jdbc.execute("DELETE FROM care_schedules");
        jdbc.execute("DELETE FROM plants");
        jdbc.execute("DELETE FROM locations WHERE name LIKE 'Тест-локация %'");
        jdbc.execute("DELETE FROM users WHERE telegram_chat_id = 900101");
    }

    // ===== Хелперы =====

    /** Кладёт запись фото. {@code deletedDaysAgo == null} — активное фото. */
    private long insertPhoto(String key, long sizeBytes, Integer deletedDaysAgo) {
        return insertPhoto(key, sizeBytes, deletedDaysAgo, userId);
    }

    private long insertPhoto(String key, long sizeBytes, Integer deletedDaysAgo, long owner) {
        Timestamp deletedAt = deletedDaysAgo == null
                ? null
                : Timestamp.from(Instant.now().minus(deletedDaysAgo, ChronoUnit.DAYS));
        return jdbc.queryForObject("""
                        INSERT INTO photos (storage_key, content_type, size_bytes, user_id, created_at, deleted_at)
                        VALUES (?, 'image/jpeg', ?, ?, now(), ?) RETURNING id
                        """,
                Long.class, key, sizeBytes, owner, deletedAt);
    }

    /** Растение с обязательной локацией — {@code plants.location_id} NOT NULL с V48. */
    private long insertPlant(String name) {
        Long locationId = jdbc.queryForObject("""
                INSERT INTO locations (user_id, name, is_default)
                VALUES (?, ?, false) RETURNING id
                """, Long.class, userId, "Тест-локация " + name);
        return jdbc.queryForObject("""
                INSERT INTO plants (user_id, location_id, name) VALUES (?, ?, ?) RETURNING id
                """, Long.class, userId, locationId, name);
    }

    private Timestamp purgedAt(long photoId) {
        return jdbc.queryForObject(
                "SELECT purged_at FROM photos WHERE id = ?", Timestamp.class, photoId);
    }

    private Timestamp deletedAt(long photoId) {
        return jdbc.queryForObject(
                "SELECT deleted_at FROM photos WHERE id = ?", Timestamp.class, photoId);
    }

    // ===== Retention: главный AC =====

    @Nested
    @DisplayName("Retention — физическое удаление из бакета")
    class Retention {

        @Test
        @DisplayName("should_delete_object_from_bucket_when_retention_expired")
        void should_delete_object_from_bucket_when_retention_expired() {
            long photoId = insertPhoto("photos/expired", 1_000, 31);

            PhotoPurgeService.PurgeResult result = purgeService.purgeExpired();

            assertThat(result.purged()).isEqualTo(1);
            verify(photoStorageService).delete("photos/expired");
            assertThat(purgedAt(photoId)).isNotNull();
        }

        @Test
        @DisplayName("should_keep_object_when_retention_not_expired_yet")
        void should_keep_object_when_retention_not_expired_yet() {
            // Граница: удалено 29 дней назад при retention 30 — трогать рано.
            long photoId = insertPhoto("photos/fresh-delete", 1_000, 29);

            PhotoPurgeService.PurgeResult result = purgeService.purgeExpired();

            assertThat(result.total()).isZero();
            verify(photoStorageService, never()).delete(anyString());
            assertThat(purgedAt(photoId)).isNull();
        }

        @Test
        @DisplayName("should_never_purge_active_photo")
        void should_never_purge_active_photo() {
            long photoId = insertPhoto("photos/active", 1_000, null);

            purgeService.purgeExpired();

            verify(photoStorageService, never()).delete(anyString());
            assertThat(purgedAt(photoId)).isNull();
        }

        @Test
        @DisplayName("should_be_idempotent_when_purge_runs_twice")
        void should_be_idempotent_when_purge_runs_twice() {
            long photoId = insertPhoto("photos/twice", 1_000, 45);

            purgeService.purgeExpired();
            Timestamp firstPurge = purgedAt(photoId);
            PhotoPurgeService.PurgeResult second = purgeService.purgeExpired();

            // Второй прогон кандидата уже не видит — и момент первой чистки цел.
            assertThat(second.total()).isZero();
            assertThat(purgedAt(photoId)).isEqualTo(firstPurge);
            verify(photoStorageService).delete("photos/twice");
        }

        @Test
        @DisplayName("should_keep_photo_row_when_purged_so_progress_timeline_survives")
        void should_keep_photo_row_when_purged_so_progress_timeline_survives() {
            long photoId = insertPhoto("photos/linked", 2_000, 40);
            long plantId = insertPlant("Фикус");
            jdbc.update("""
                    INSERT INTO plant_progress_photos (plant_id, user_id, telegram_file_id, photo_id, taken_at)
                    VALUES (?, ?, NULL, ?, now())
                    """, plantId, userId, photoId);

            purgeService.purgeExpired();

            // Строка photos жива: её удаление обнулило бы photo_id и уронило
            // CHECK chk_progress_photo_source на таймлайне (см. V54/V58).
            Long stillThere = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM photos WHERE id = ?", Long.class, photoId);
            assertThat(stillThere).isEqualTo(1);
            Long linkAlive = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM plant_progress_photos WHERE photo_id = ?", Long.class, photoId);
            assertThat(linkAlive).isEqualTo(1);
        }
    }

    // ===== Удаление админом =====

    @Nested
    @DisplayName("Действия админа над фото")
    class Actions {

        @Test
        @DisplayName("should_soft_delete_without_touching_s3")
        void should_soft_delete_without_touching_s3() throws Exception {
            long photoId = insertPhoto("photos/to-delete", 1_000, null);

            mockMvc.perform(post("/admin/photos/" + photoId + "/delete")
                            .param("returnTo", "/admin/users/" + userId)
                            .with(user("admin").roles("ADMIN")).with(csrf()))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/admin/users/" + userId));

            assertThat(deletedAt(photoId)).isNotNull();
            assertThat(purgedAt(photoId)).isNull();
            // Ключевое: удаление в админке НЕ ходит в S3 — только помечает запись.
            verify(photoStorageService, never()).delete(anyString());
        }

        @Test
        @DisplayName("should_ignore_open_redirect_in_returnTo")
        void should_ignore_open_redirect_in_returnTo() throws Exception {
            long photoId = insertPhoto("photos/redirect", 1_000, null);

            mockMvc.perform(post("/admin/photos/" + photoId + "/delete")
                            .param("returnTo", "https://evil.example.com")
                            .with(user("admin").roles("ADMIN")).with(csrf()))
                    .andExpect(redirectedUrl("/admin/storage"));
        }

        @Test
        @DisplayName("should_not_reset_retention_clock_when_deleted_twice")
        void should_not_reset_retention_clock_when_deleted_twice() {
            long photoId = insertPhoto("photos/double", 1_000, 20);
            Timestamp original = deletedAt(photoId);

            boolean changed = photoService.softDelete(photoId, "admin");

            // Иначе повторный клик каждый раз откладывал бы чистку на 30 дней.
            assertThat(changed).isFalse();
            assertThat(deletedAt(photoId)).isEqualTo(original);
        }

        @Test
        @DisplayName("should_restore_photo_within_retention_window")
        void should_restore_photo_within_retention_window() throws Exception {
            long photoId = insertPhoto("photos/oops", 1_000, 3);

            mockMvc.perform(post("/admin/photos/" + photoId + "/restore")
                            .with(user("admin").roles("ADMIN")).with(csrf()))
                    .andExpect(status().is3xxRedirection());

            assertThat(deletedAt(photoId)).isNull();
        }

        @Test
        @DisplayName("should_refuse_restore_when_object_already_purged")
        void should_refuse_restore_when_object_already_purged() {
            long photoId = insertPhoto("photos/gone", 1_000, 40);
            purgeService.purgeExpired();

            assertThat(purgedAt(photoId)).isNotNull();
            // Бинаря в бакете больше нет — восстанавливать нечего.
            org.assertj.core.api.Assertions
                    .assertThatThrownBy(() -> photoService.restore(photoId, "admin"))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("should_soft_delete_all_user_photos_on_gdpr_request")
        void should_soft_delete_all_user_photos_on_gdpr_request() throws Exception {
            insertPhoto("photos/g1", 1_000, null);
            insertPhoto("photos/g2", 2_000, null);
            long alreadyDeleted = insertPhoto("photos/g3", 3_000, 10);
            Timestamp untouched = deletedAt(alreadyDeleted);

            mockMvc.perform(post("/admin/users/" + userId + "/photos/delete-all")
                            .with(user("admin").roles("ADMIN")).with(csrf()))
                    .andExpect(redirectedUrl("/admin/users/" + userId));

            Long active = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM photos WHERE user_id = ? AND deleted_at IS NULL",
                    Long.class, userId);
            assertThat(active).isZero();
            // Уже удалённому дату не переписали — retention не сброшен.
            assertThat(deletedAt(alreadyDeleted)).isEqualTo(untouched);
            verify(photoStorageService, never()).delete(anyString());
        }

        @Test
        @DisplayName("should_return_404_when_photo_missing")
        void should_return_404_when_photo_missing() {
            org.assertj.core.api.Assertions
                    .assertThatThrownBy(() -> photoService.softDelete(999_999L, "admin"))
                    .hasMessageContaining("Photo not found");
        }
    }

    // ===== Дашборд =====

    @Nested
    @DisplayName("Дашборд /admin/storage")
    class Dashboard {

        @Test
        @DisplayName("should_render_page_when_storage_empty")
        void should_render_page_when_storage_empty() throws Exception {
            mockMvc.perform(get("/admin/storage").with(user("admin").roles("ADMIN")))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Объём в бакете")))
                    .andExpect(content().string(containsString("Топ юзеров по объёму")))
                    .andExpect(content().string(containsString("Последние загрузки")));
        }

        @Test
        @DisplayName("should_separate_bucket_active_and_pending_volumes")
        void should_separate_bucket_active_and_pending_volumes() {
            insertPhoto("photos/a", 1_000, null);       // активное
            insertPhoto("photos/b", 2_000, 5);          // удалено, ещё в бакете
            long purged = insertPhoto("photos/c", 4_000, 40);
            purgeService.purgeExpired();                // c уходит из бакета

            StorageOverviewDto overview = storageService.currentOverview();

            assertThat(purgedAt(purged)).isNotNull();
            // В бакете остались только a и b — за c мы больше не платим.
            assertThat(overview.bucketBytes()).isEqualTo(3_000);
            assertThat(overview.bucketCount()).isEqualTo(2);
            assertThat(overview.activeBytes()).isEqualTo(1_000);
            assertThat(overview.pendingPurgeBytes()).isEqualTo(2_000);
            assertThat(overview.purgedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("should_list_top_users_and_recent_uploads")
        void should_list_top_users_and_recent_uploads() {
            insertPhoto("photos/x1", 5_000, null);
            insertPhoto("photos/x2", 1_000, null);

            StoragePageDto page = storageService.loadPage(0);

            assertThat(page.topUsers()).hasSize(1);
            assertThat(page.topUsers().get(0).totalBytes()).isEqualTo(6_000);
            assertThat(page.topUsers().get(0).photoCount()).isEqualTo(2);
            assertThat(page.recentUploads()).hasSize(2);
            assertThat(page.totalUploads()).isEqualTo(2);
        }

        @Test
        @DisplayName("should_not_duplicate_row_when_photo_linked_to_progress_timeline")
        void should_not_duplicate_row_when_photo_linked_to_progress_timeline() {
            long photoId = insertPhoto("photos/linked-once", 1_000, null);
            long plantId = insertPlant("Монстера");
            jdbc.update("""
                    INSERT INTO plant_progress_photos (plant_id, user_id, telegram_file_id, photo_id, taken_at)
                    VALUES (?, ?, NULL, ?, now())
                    """, plantId, userId, photoId);

            StoragePageDto page = storageService.loadPage(0);

            // Список и счётчик пагинации должны сходиться, даже когда фото
            // присоединено к таймлайну растения.
            assertThat(page.recentUploads()).hasSize(1);
            assertThat(page.totalUploads()).isEqualTo(1);
            assertThat(page.recentUploads().get(0).plantName()).isEqualTo("Монстера");
        }

        @Test
        @DisplayName("should_leave_preview_null_when_bucket_not_configured")
        void should_leave_preview_null_when_bucket_not_configured() {
            insertPhoto("photos/no-bucket", 1_000, null);

            List<PhotoRowDto> photos = storageService.loadUserPhotos(userId);

            // В тестовом окружении AWS_S3_BUCKET_NAME пуст — подписывать нечего,
            // и страница обязана рендериться без обращения к хранилищу.
            assertThat(photos).hasSize(1);
            assertThat(photos.get(0).previewUrl()).isNull();
        }

        @Test
        @DisplayName("should_show_photo_section_on_user_page")
        void should_show_photo_section_on_user_page() throws Exception {
            insertPhoto("photos/on-user-page", 1_000, null);

            mockMvc.perform(get("/admin/users/" + userId).with(user("admin").roles("ADMIN")))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Удалить все фото (GDPR)")));
        }
    }

    // ===== Суточная метрика =====

    @Nested
    @DisplayName("Суточный снапшот объёма")
    class Metrics {

        @Test
        @DisplayName("should_upsert_snapshot_when_captured_twice_same_day")
        void should_upsert_snapshot_when_captured_twice_same_day() {
            insertPhoto("photos/m1", 1_500, null);
            metricService.captureDailySnapshot();

            insertPhoto("photos/m2", 2_500, null);
            metricService.captureDailySnapshot();

            // PK — дата: второй прогон за те же сутки перезаписывает строку,
            // а не падает на конфликте.
            Long rows = jdbc.queryForObject("SELECT COUNT(*) FROM storage_metrics", Long.class);
            Long bytes = jdbc.queryForObject("SELECT total_bytes FROM storage_metrics", Long.class);
            assertThat(rows).isEqualTo(1);
            assertThat(bytes).isEqualTo(4_000);
        }

        @Test
        @DisplayName("should_exclude_purged_objects_from_snapshot")
        void should_exclude_purged_objects_from_snapshot() {
            insertPhoto("photos/kept", 1_000, null);
            insertPhoto("photos/goes-away", 9_000, 40);
            purgeService.purgeExpired();

            metricService.captureDailySnapshot();

            Long bytes = jdbc.queryForObject("SELECT total_bytes FROM storage_metrics", Long.class);
            Long count = jdbc.queryForObject("SELECT total_count FROM storage_metrics", Long.class);
            assertThat(bytes).isEqualTo(1_000);
            assertThat(count).isEqualTo(1);
        }

        @Test
        @DisplayName("should_feed_growth_chart_from_snapshots")
        void should_feed_growth_chart_from_snapshots() {
            insertPhoto("photos/growth", 3_000, null);
            metricService.captureDailySnapshot();

            StoragePageDto page = storageService.loadPage(0);

            assertThat(page.growth()).hasSize(1);
            assertThat(page.growth().get(0).totalBytes()).isEqualTo(3_000);
            assertThat(page.barPercent(page.growth().get(0))).isEqualTo(100);
        }
    }
}
