package com.plantcare.api;

import com.plantcare.api.config.WellKnownConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-слайс тесты {@link WellKnownController}.
 *
 * <p>Issue #215: проверяем 200 OK, Content-Type: application/json и структуру тела
 * для {@code /.well-known/apple-app-site-association} и {@code /.well-known/assetlinks.json}.
 *
 * <p>{@code addFilters = false} — тестируем только контроллер, не security-цепочку
 * (пути вне /api/v1/** попадают в default permitAll, это проверяется отдельным IT).
 */
@WebMvcTest(controllers = WellKnownController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(WellKnownConfig.class)
@TestPropertySource(properties = {
        "plantcare.deep-link.ios.app-ids=TESTTEAM123.com.plantcare.test",
        "plantcare.deep-link.ios.paths=/auth/verify*",
        "plantcare.deep-link.android.package-name=com.plantcare.test",
        "plantcare.deep-link.android.sha256-cert-fingerprints=AA:BB:CC:DD"
})
@DisplayName("Web-слайс WellKnownController — /.well-known/")
class WellKnownControllerTest {

    @Autowired
    private MockMvc mockMvc;

    // ── AASA (iOS) ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AASA: 200 OK + Content-Type application/json")
    void should_return_200_with_json_content_type_for_aasa() throws Exception {
        mockMvc.perform(get("/.well-known/apple-app-site-association"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("AASA: тело содержит applinks.details с appID из конфига")
    void should_return_aasa_body_with_app_id_from_config() throws Exception {
        mockMvc.perform(get("/.well-known/apple-app-site-association"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applinks").exists())
                .andExpect(jsonPath("$.applinks.details[0].appID")
                        .value("TESTTEAM123.com.plantcare.test"))
                .andExpect(jsonPath("$.applinks.details[0].paths[0]")
                        .value("/auth/verify*"));
    }

    @Test
    @DisplayName("AASA: тело содержит applinks.apps = []")
    void should_return_aasa_with_empty_apps_array() throws Exception {
        mockMvc.perform(get("/.well-known/apple-app-site-association"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applinks.apps").isArray())
                .andExpect(jsonPath("$.applinks.apps").isEmpty());
    }

    // ── assetlinks.json (Android) ───────────────────────────────────────────────

    @Test
    @DisplayName("assetlinks: 200 OK + Content-Type application/json")
    void should_return_200_with_json_content_type_for_assetlinks() throws Exception {
        mockMvc.perform(get("/.well-known/assetlinks.json"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("assetlinks: тело содержит android_app с package_name и sha256 из конфига")
    void should_return_assetlinks_body_with_android_config() throws Exception {
        mockMvc.perform(get("/.well-known/assetlinks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].relation[0]")
                        .value("delegate_permission/common.handle_all_urls"))
                .andExpect(jsonPath("$[0].target.namespace").value("android_app"))
                .andExpect(jsonPath("$[0].target.package_name").value("com.plantcare.test"))
                .andExpect(jsonPath("$[0].target.sha256_cert_fingerprints[0]").value("AA:BB:CC:DD"));
    }

    @Test
    @DisplayName("assetlinks: endpoint отвечает на запрос без параметров (edge-case)")
    void should_return_assetlinks_without_any_query_params() throws Exception {
        mockMvc.perform(get("/.well-known/assetlinks.json"))
                .andExpect(status().isOk());
    }
}
