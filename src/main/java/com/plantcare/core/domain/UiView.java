package com.plantcare.core.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.plantcare.core.domain.base.BaseEntity;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;

/**
 * SDUI: шаблон композиции одного экрана (issue #284).
 *
 * <p>{@code layout_json} хранит упорядоченный список блоков с их {@code type}
 * и статичными пропсами — БЕЗ живых данных. Данные динамических блоков
 * гидрируются на лету из существующих сервисов при запросе экрана.
 */
@Entity
@Table(name = "ui_views")
@Getter
@NoArgsConstructor
public class UiView extends BaseEntity {

    @Column(name = "category", nullable = false)
    private String category;

    @Column(name = "screen", nullable = false)
    private String screen;

    /** JSONB-шаблон композиции: {@code { screenId, version, blocks: [...] }}. */
    @Type(JsonType.class)
    @Column(name = "layout_json", nullable = false, columnDefinition = "jsonb")
    private JsonNode layoutJson;

    @Column(name = "min_catalog_version", nullable = false)
    private int minCatalogVersion;
}
