-- Server-Driven UI: сид композиции главного экрана `home` (issue #284).
--
-- Отдельная forward-миграция от DDL (V47): сидинг данных не смешиваем с DDL.
--
-- layout_json хранит ТОЛЬКО композицию (шаблон), без живых данных:
-- упорядоченный список блоков с их `type` и (опционально) per-block
-- `minCatalogVersion`. Динамические блоки (weather_strip/today_summary/
-- location_chips/plant_grid) гидрируются на лету из сервисов при запросе;
-- статичные блоки (если появятся, напр. banner) отдаются verbatim.

INSERT INTO ui_views (category, screen, layout_json, min_catalog_version)
VALUES (
    'screen',
    'home',
    '{
       "screenId": "home",
       "version": 1,
       "blocks": [
         { "type": "weather_strip",  "minCatalogVersion": 1 },
         { "type": "today_summary",  "minCatalogVersion": 1 },
         { "type": "location_chips", "minCatalogVersion": 1 },
         { "type": "plant_grid",     "minCatalogVersion": 1 }
       ]
     }'::jsonb,
    1
);
