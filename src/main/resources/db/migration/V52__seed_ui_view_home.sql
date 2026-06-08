-- Server-Driven UI: сид композиции главного экрана `home` (issue #284).
--
-- Отдельная forward-миграция от DDL (V51): сидинг данных не смешиваем с DDL.
--
-- layout_json хранит ТОЛЬКО композицию (шаблон), без живых данных:
-- упорядоченный список блоков с их `type` и (опционально) per-block
-- `minCatalogVersion`. Динамические блоки (weather_strip/today_tasks/
-- location_chips/plant_grid) гидрируются на лету из сервисов при запросе;
-- статичные блоки (если появятся, напр. banner) отдаются verbatim.
--
-- home несёт `today_tasks` (тапабельный список задач на сегодня), а не
-- счётчиковый `today_summary`: на главном экране нужен интерактив — тап
-- задачи открывает нативный sheet ухода с предвыбранным типом. Тип `today_summary`
-- остаётся поддерживаемым в сервисе для других экранов, но из композиции home убран.

INSERT INTO ui_views (category, screen, layout_json, min_catalog_version)
VALUES (
    'screen',
    'home',
    '{
       "screenId": "home",
       "version": 1,
       "blocks": [
         { "type": "weather_strip",  "minCatalogVersion": 1 },
         { "type": "today_tasks",    "minCatalogVersion": 1 },
         { "type": "location_chips", "minCatalogVersion": 1 },
         { "type": "plant_grid",     "minCatalogVersion": 1 }
       ]
     }'::jsonb,
    1
);
