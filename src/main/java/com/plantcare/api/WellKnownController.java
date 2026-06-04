package com.plantcare.api;

import com.plantcare.api.config.DeepLinkProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Issue #215: Well-known эндпоинты для Universal Links (iOS) и App Links (Android).
 *
 * <p>Оба пути находятся вне {@code /api/v1/**}, поэтому попадают в дефолтную
 * permitAll-цепочку Spring Security — авторизация не нужна.
 *
 * <p>Ответы отдаются без редиректов и без файлового расширения,
 * {@code Content-Type: application/json} — именно так требуют валидаторы Apple и Google.
 */
@RestController
@RequiredArgsConstructor
public class WellKnownController {

    private final DeepLinkProperties deepLinkProperties;

    /**
     * Apple App Site Association (AASA) для Universal Links (iOS).
     *
     * <p>Путь {@code /.well-known/apple-app-site-association} — Apple запрашивает его
     * при установке приложения, чтобы связать домен с приложением.
     */
    @GetMapping(
            value = "/.well-known/apple-app-site-association",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<Map<String, Object>> appleAppSiteAssociation() {
        var ios = deepLinkProperties.getIos();

        Map<String, Object> applinksDetail = new LinkedHashMap<>();
        applinksDetail.put("apps", List.of());
        applinksDetail.put("details", ios.getAppIds().stream()
                .map(appId -> {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("appID", appId);
                    detail.put("paths", ios.getPaths());
                    return detail;
                })
                .toList());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applinks", applinksDetail);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /**
     * Digital Asset Links для App Links (Android).
     *
     * <p>Путь {@code /.well-known/assetlinks.json} — Android запрашивает его
     * при autoVerify intent-filter, чтобы верифицировать связь домена с приложением.
     */
    @GetMapping(
            value = "/.well-known/assetlinks.json",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<List<Map<String, Object>>> assetLinks() {
        var android = deepLinkProperties.getAndroid();

        Map<String, Object> target = new LinkedHashMap<>();
        target.put("namespace", "android_app");
        target.put("package_name", android.getPackageName());
        target.put("sha256_cert_fingerprints", android.getSha256CertFingerprints());

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("relation", List.of("delegate_permission/common.handle_all_urls"));
        entry.put("target", target);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(List.of(entry));
    }
}
