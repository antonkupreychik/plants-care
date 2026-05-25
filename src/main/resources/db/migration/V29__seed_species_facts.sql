-- V29__seed_species_facts.sql
-- Сид энциклопедического контента (species_facts) и токсичности (species.toxic_to_*)
-- для топ-видов по popularity. ADR-011, issue #132. Зависит от V22 (#129) и V26 (#130).
--
-- Чистый сид, БЕЗ DDL (схема в V22/V26). Только INSERT/UPDATE.
--
-- Привязка к виду — ТОЛЬКО через подзапрос по name/latin_name (id = BIGSERIAL,
--   не гарантирован между средами, хардкод id запрещён).
--
-- Идемпотентность:
--   * Факты — INSERT ... SELECT ... WHERE NOT EXISTS по (species_id, category, body):
--     на species_facts нет уникального constraint, ON CONFLICT не сработает.
--     Повторный прогон не плодит дубли.
--   * Токсичность — UPDATE ... SET, идемпотентен по природе. Трогаем только перечисленные
--     виды; виды без достоверных данных остаются NULL ("нет данных").
--
-- Токсичность выверена по общеизвестным данным ASPCA (toxic/non-toxic to cats & dogs).
--   Где по виду нет уверенности — флаг НЕ трогается (остаётся NULL).
--
-- display_order: глобальный порядок показа факта внутри (species_id, category):
--   ORIGIN=10, CARE=20/21, TOXICITY=30, CURIOSITY=40. Внутри категории — по шагу 1.
--
-- Backward-compat note: чистый сид справочных данных в кураторскую таблицу + установка
--   nullable-флагов. Старый код это только читает (или игнорирует). Rolling deploy безопасен.

BEGIN;

-- =====================================================
-- Факты по видам. Шаблон на каждый факт:
--   INSERT INTO species_facts (species_id, category, title, body, source, display_order)
--   SELECT s.id, '<CAT>', '<title>', '<body>', '<source|NULL>', <order>
--   FROM species s
--   WHERE s.latin_name = '<latin>'           -- стабильный ключ
--     AND NOT EXISTS (
--       SELECT 1 FROM species_facts f
--       WHERE f.species_id = s.id AND f.category = '<CAT>' AND f.body = '<body>'
--     );
-- Дедуп по (species_id, category, body) — body уникально внутри (вид, категория).
-- =====================================================

-- ---------- Монстера (Monstera deliciosa) ----------
INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'ORIGIN', 'Родина', 'Происходит из тропических лесов Центральной Америки (юг Мексики, Панама), где как лиана взбирается по стволам деревьев к свету.', 'Royal Botanic Gardens, Kew', 10
FROM species s WHERE s.latin_name = 'Monstera deliciosa'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'ORIGIN' AND f.body = 'Происходит из тропических лесов Центральной Америки (юг Мексики, Панама), где как лиана взбирается по стволам деревьев к свету.');

INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'CARE', 'Уход', 'Любит яркий рассеянный свет и опору для воздушных корней. Поливают после просыхания верхнего слоя; характерные дырки в листьях появляются только при достаточном освещении.', NULL, 20
FROM species s WHERE s.latin_name = 'Monstera deliciosa'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'CARE' AND f.body = 'Любит яркий рассеянный свет и опору для воздушных корней. Поливают после просыхания верхнего слоя; характерные дырки в листьях появляются только при достаточном освещении.');

INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'TOXICITY', 'Токсичность', 'Содержит нерастворимые оксалаты кальция: сок раздражает слизистые. Токсична для кошек и собак, опасна при разжёвывании листьев детьми и питомцами.', 'ASPCA', 30
FROM species s WHERE s.latin_name = 'Monstera deliciosa'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'TOXICITY' AND f.body = 'Содержит нерастворимые оксалаты кальция: сок раздражает слизистые. Токсична для кошек и собак, опасна при разжёвывании листьев детьми и питомцами.');

INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'CURIOSITY', 'Интересный факт', 'Видовое имя deliciosa дано за съедобное соплодие, по вкусу напоминающее смесь ананаса и банана; есть его можно только полностью созревшим.', NULL, 40
FROM species s WHERE s.latin_name = 'Monstera deliciosa'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'CURIOSITY' AND f.body = 'Видовое имя deliciosa дано за съедобное соплодие, по вкусу напоминающее смесь ананаса и банана; есть его можно только полностью созревшим.');

-- ---------- Сансевиерия (Sansevieria trifasciata) ----------
INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'ORIGIN', 'Родина', 'Родом из засушливых регионов Западной Африки (Нигерия, Конго). Приспособлена к скудному поливу и бедным почвам.', 'Royal Botanic Gardens, Kew', 10
FROM species s WHERE s.latin_name = 'Sansevieria trifasciata'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'ORIGIN' AND f.body = 'Родом из засушливых регионов Западной Африки (Нигерия, Конго). Приспособлена к скудному поливу и бедным почвам.');

INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'CARE', 'Уход', 'Одно из самых неприхотливых растений: терпит тень и редкий полив. Главная опасность — перелив, ведущий к загниванию корневища. Зимой поливают раз в 2–3 недели.', NULL, 20
FROM species s WHERE s.latin_name = 'Sansevieria trifasciata'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'CARE' AND f.body = 'Одно из самых неприхотливых растений: терпит тень и редкий полив. Главная опасность — перелив, ведущий к загниванию корневища. Зимой поливают раз в 2–3 недели.');

INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'TOXICITY', 'Токсичность', 'Содержит сапонины. Токсична для кошек и собак: при поедании вызывает слюнотечение, тошноту и рвоту.', 'ASPCA', 30
FROM species s WHERE s.latin_name = 'Sansevieria trifasciata'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'TOXICITY' AND f.body = 'Содержит сапонины. Токсична для кошек и собак: при поедании вызывает слюнотечение, тошноту и рвоту.');

INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'CURIOSITY', 'Интересный факт', 'Ночью поглощает углекислый газ и выделяет кислород (CAM-фотосинтез), за что её часто советуют ставить в спальню.', NULL, 40
FROM species s WHERE s.latin_name = 'Sansevieria trifasciata'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'CURIOSITY' AND f.body = 'Ночью поглощает углекислый газ и выделяет кислород (CAM-фотосинтез), за что её часто советуют ставить в спальню.');

-- ---------- Орхидея фаленопсис (Phalaenopsis) ----------
INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'ORIGIN', 'Родина', 'Эпифит из влажных тропических лесов Юго-Восточной Азии и севера Австралии. В природе растёт на стволах деревьев, цепляясь воздушными корнями.', 'Royal Botanic Gardens, Kew', 10
FROM species s WHERE s.latin_name = 'Phalaenopsis'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'ORIGIN' AND f.body = 'Эпифит из влажных тропических лесов Юго-Восточной Азии и севера Австралии. В природе растёт на стволах деревьев, цепляясь воздушными корнями.');

INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'CARE', 'Уход', 'Поливают погружением, давая субстрату из коры полностью просохнуть между поливами. Зелёные корни любят свет — отсюда прозрачные горшки. Перепад дневной и ночной температуры стимулирует цветение.', NULL, 20
FROM species s WHERE s.latin_name = 'Phalaenopsis'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'CARE' AND f.body = 'Поливают погружением, давая субстрату из коры полностью просохнуть между поливами. Зелёные корни любят свет — отсюда прозрачные горшки. Перепад дневной и ночной температуры стимулирует цветение.');

INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'TOXICITY', 'Токсичность', 'Не токсична для кошек и собак — одно из немногих по-настоящему безопасных цветущих комнатных растений.', 'ASPCA', 30
FROM species s WHERE s.latin_name = 'Phalaenopsis'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'TOXICITY' AND f.body = 'Не токсична для кошек и собак — одно из немногих по-настоящему безопасных цветущих комнатных растений.');

-- ---------- Фикус Бенджамина (Ficus benjamina) ----------
INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'ORIGIN', 'Родина', 'Происходит из тропиков Южной и Юго-Восточной Азии и севера Австралии, где вырастает в крупное дерево с воздушными корнями.', 'Royal Botanic Gardens, Kew', 10
FROM species s WHERE s.latin_name = 'Ficus benjamina'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'ORIGIN' AND f.body = 'Происходит из тропиков Южной и Юго-Восточной Азии и севера Австралии, где вырастает в крупное дерево с воздушными корнями.');

INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'CARE', 'Уход', 'Не любит перестановок, сквозняков и резких перемен: в ответ сбрасывает листья. Нужен стабильный яркий свет и умеренный полив без пересушки и залива.', NULL, 20
FROM species s WHERE s.latin_name = 'Ficus benjamina'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'CARE' AND f.body = 'Не любит перестановок, сквозняков и резких перемен: в ответ сбрасывает листья. Нужен стабильный яркий свет и умеренный полив без пересушки и залива.');

INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'TOXICITY', 'Токсичность', 'Млечный сок содержит раздражающие вещества. Токсичен для кошек и собак, вызывает раздражение кожи и слизистых; у людей возможна контактная аллергия.', 'ASPCA', 30
FROM species s WHERE s.latin_name = 'Ficus benjamina'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'TOXICITY' AND f.body = 'Млечный сок содержит раздражающие вещества. Токсичен для кошек и собак, вызывает раздражение кожи и слизистых; у людей возможна контактная аллергия.');

-- ---------- Замиокулькас (Zamioculcas zamiifolia) ----------
INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'ORIGIN', 'Родина', 'Родом из Восточной Африки (Кения, Танзания, Занзибар). В сезон засухи сбрасывает листья и переживает его за счёт запасов в подземном клубне.', 'Royal Botanic Gardens, Kew', 10
FROM species s WHERE s.latin_name = 'Zamioculcas zamiifolia'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'ORIGIN' AND f.body = 'Родом из Восточной Африки (Кения, Танзания, Занзибар). В сезон засухи сбрасывает листья и переживает его за счёт запасов в подземном клубне.');

INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'CARE', 'Уход', 'Переносит длительную засуху и тень. Поливают редко и только после полного просыхания грунта; перелив быстро губит клубень. Растёт медленно.', NULL, 20
FROM species s WHERE s.latin_name = 'Zamioculcas zamiifolia'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'CARE' AND f.body = 'Переносит длительную засуху и тень. Поливают редко и только после полного просыхания грунта; перелив быстро губит клубень. Растёт медленно.');

INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'TOXICITY', 'Токсичность', 'Содержит оксалаты кальция во всех частях. Токсичен для кошек и собак, сок раздражает кожу и слизистые — после обрезки мойте руки.', 'ASPCA', 30
FROM species s WHERE s.latin_name = 'Zamioculcas zamiifolia'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'TOXICITY' AND f.body = 'Содержит оксалаты кальция во всех частях. Токсичен для кошек и собак, сок раздражает кожу и слизистые — после обрезки мойте руки.');

-- ---------- Фикус каучуконосный (Ficus elastica) ----------
INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'ORIGIN', 'Родина', 'Происходит из тропиков Северо-Восточной Индии и Юго-Восточной Азии; в природе достигает 30–40 метров в высоту.', 'Royal Botanic Gardens, Kew', 10
FROM species s WHERE s.latin_name = 'Ficus elastica'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'ORIGIN' AND f.body = 'Происходит из тропиков Северо-Восточной Индии и Юго-Восточной Азии; в природе достигает 30–40 метров в высоту.');

INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'CARE', 'Уход', 'Неприхотлив: яркий рассеянный свет и умеренный полив. Крупные глянцевые листья полезно протирать от пыли. Прищипка верхушки стимулирует ветвление.', NULL, 20
FROM species s WHERE s.latin_name = 'Ficus elastica'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'CARE' AND f.body = 'Неприхотлив: яркий рассеянный свет и умеренный полив. Крупные глянцевые листья полезно протирать от пыли. Прищипка верхушки стимулирует ветвление.');

INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'TOXICITY', 'Токсичность', 'Млечный сок токсичен для кошек и собак и раздражает кожу. Раньше из этого вида добывали натуральный каучук.', 'ASPCA', 30
FROM species s WHERE s.latin_name = 'Ficus elastica'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'TOXICITY' AND f.body = 'Млечный сок токсичен для кошек и собак и раздражает кожу. Раньше из этого вида добывали натуральный каучук.');

-- ---------- Спатифиллум (Spathiphyllum) ----------
INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'ORIGIN', 'Родина', 'Родом из влажных тропических лесов Центральной и Южной Америки, растёт в подлеске у воды — отсюда любовь к влажности и теневыносливость.', 'Royal Botanic Gardens, Kew', 10
FROM species s WHERE s.latin_name = 'Spathiphyllum'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'ORIGIN' AND f.body = 'Родом из влажных тропических лесов Центральной и Южной Америки, растёт в подлеске у воды — отсюда любовь к влажности и теневыносливость.');

INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'CARE', 'Уход', 'Сам сигналит о нехватке воды поникшими листьями и быстро восстанавливается после полива. Любит влажный воздух и рассеянный свет, не любит прямое солнце и жёсткую воду.', NULL, 20
FROM species s WHERE s.latin_name = 'Spathiphyllum'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'CARE' AND f.body = 'Сам сигналит о нехватке воды поникшими листьями и быстро восстанавливается после полива. Любит влажный воздух и рассеянный свет, не любит прямое солнце и жёсткую воду.');

INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'TOXICITY', 'Токсичность', 'Содержит нерастворимые оксалаты кальция. Токсичен для кошек и собак: вызывает жжение во рту, слюнотечение и затруднённое глотание.', 'ASPCA', 30
FROM species s WHERE s.latin_name = 'Spathiphyllum'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'TOXICITY' AND f.body = 'Содержит нерастворимые оксалаты кальция. Токсичен для кошек и собак: вызывает жжение во рту, слюнотечение и затруднённое глотание.');

-- ---------- Толстянка (Crassula ovata) ----------
INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'ORIGIN', 'Родина', 'Суккулент родом из засушливых районов Южной Африки. Запасает воду в толстых листьях, переживая долгие периоды без дождей.', 'Royal Botanic Gardens, Kew', 10
FROM species s WHERE s.latin_name = 'Crassula ovata'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'ORIGIN' AND f.body = 'Суккулент родом из засушливых районов Южной Африки. Запасает воду в толстых листьях, переживая долгие периоды без дождей.');

INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'CARE', 'Уход', 'Нужно много света и редкий полив после полного просыхания грунта. Зимой полив сокращают почти до нуля. Легко размножается листом или черенком.', NULL, 20
FROM species s WHERE s.latin_name = 'Crassula ovata'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'CARE' AND f.body = 'Нужно много света и редкий полив после полного просыхания грунта. Зимой полив сокращают почти до нуля. Легко размножается листом или черенком.');

INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'TOXICITY', 'Токсичность', 'Токсична для кошек и собак: при поедании возможны рвота, вялость и нарушение координации.', 'ASPCA', 30
FROM species s WHERE s.latin_name = 'Crassula ovata'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'TOXICITY' AND f.body = 'Токсична для кошек и собак: при поедании возможны рвота, вялость и нарушение координации.');

INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'CURIOSITY', 'Интересный факт', 'Известна как «денежное дерево»: по фэн-шуй её круглые монетовидные листья символизируют достаток.', NULL, 40
FROM species s WHERE s.latin_name = 'Crassula ovata'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'CURIOSITY' AND f.body = 'Известна как «денежное дерево»: по фэн-шуй её круглые монетовидные листья символизируют достаток.');

-- ---------- Алоэ (Aloe vera) ----------
INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'ORIGIN', 'Родина', 'Суккулент с Аравийского полуострова, широко натурализовавшийся в засушливых тропиках по всему миру.', 'Royal Botanic Gardens, Kew', 10
FROM species s WHERE s.latin_name = 'Aloe vera'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'ORIGIN' AND f.body = 'Суккулент с Аравийского полуострова, широко натурализовавшийся в засушливых тропиках по всему миру.');

INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'CARE', 'Уход', 'Нужен яркий свет и редкий полив после полного просыхания грунта. Хороший дренаж обязателен: от застоя воды корни и розетка загнивают.', NULL, 20
FROM species s WHERE s.latin_name = 'Aloe vera'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'CARE' AND f.body = 'Нужен яркий свет и редкий полив после полного просыхания грунта. Хороший дренаж обязателен: от застоя воды корни и розетка загнивают.');

INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'TOXICITY', 'Токсичность', 'Токсично для кошек и собак: латекс под кожицей листа вызывает рвоту и расстройство пищеварения. Прозрачный гель мякоти традиционно применяют у людей наружно, но цельный лист питомцам давать нельзя.', 'ASPCA', 30
FROM species s WHERE s.latin_name = 'Aloe vera'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'TOXICITY' AND f.body = 'Токсично для кошек и собак: латекс под кожицей листа вызывает рвоту и расстройство пищеварения. Прозрачный гель мякоти традиционно применяют у людей наружно, но цельный лист питомцам давать нельзя.');

-- ---------- Хлорофитум (Chlorophytum comosum) ----------
INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'ORIGIN', 'Родина', 'Происходит из тропической и южной Африки. В природе образует плотные куртины в подлеске.', 'Royal Botanic Gardens, Kew', 10
FROM species s WHERE s.latin_name = 'Chlorophytum comosum'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'ORIGIN' AND f.body = 'Происходит из тропической и южной Африки. В природе образует плотные куртины в подлеске.');

INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'CARE', 'Уход', 'Один из самых неприхотливых видов: терпит и тень, и яркий свет, прощает нерегулярный полив. Активно выпускает «детки» на длинных побегах, которые легко укоренить.', NULL, 20
FROM species s WHERE s.latin_name = 'Chlorophytum comosum'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'CARE' AND f.body = 'Один из самых неприхотливых видов: терпит и тень, и яркий свет, прощает нерегулярный полив. Активно выпускает «детки» на длинных побегах, которые легко укоренить.');

INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'TOXICITY', 'Токсичность', 'Не токсичен для кошек и собак по данным ASPCA. При этом кошек часто привлекает его листва — это безопасно, хотя поедание в больших количествах может вызвать лёгкое расстройство ЖКТ.', 'ASPCA', 30
FROM species s WHERE s.latin_name = 'Chlorophytum comosum'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'TOXICITY' AND f.body = 'Не токсичен для кошек и собак по данным ASPCA. При этом кошек часто привлекает его листва — это безопасно, хотя поедание в больших количествах может вызвать лёгкое расстройство ЖКТ.');

INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'CURIOSITY', 'Интересный факт', 'Считается одним из лучших растений для очистки воздуха в помещении.', NULL, 40
FROM species s WHERE s.latin_name = 'Chlorophytum comosum'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'CURIOSITY' AND f.body = 'Считается одним из лучших растений для очистки воздуха в помещении.');

-- ---------- Антуриум (Anthurium) ----------
INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'ORIGIN', 'Родина', 'Родом из тропических лесов Центральной и Южной Америки, многие виды — эпифиты, растущие на деревьях.', 'Royal Botanic Gardens, Kew', 10
FROM species s WHERE s.latin_name = 'Anthurium'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'ORIGIN' AND f.body = 'Родом из тропических лесов Центральной и Южной Америки, многие виды — эпифиты, растущие на деревьях.');

INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'CARE', 'Уход', 'Любит тепло, влажный воздух и яркий рассеянный свет. Поливают после подсыхания верхнего слоя; от прямого солнца листья получают ожоги.', NULL, 20
FROM species s WHERE s.latin_name = 'Anthurium'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'CARE' AND f.body = 'Любит тепло, влажный воздух и яркий рассеянный свет. Поливают после подсыхания верхнего слоя; от прямого солнца листья получают ожоги.');

INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'TOXICITY', 'Токсичность', 'Содержит нерастворимые оксалаты кальция. Токсичен для кошек и собак: вызывает жжение во рту, слюнотечение и отёк слизистых.', 'ASPCA', 30
FROM species s WHERE s.latin_name = 'Anthurium'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'TOXICITY' AND f.body = 'Содержит нерастворимые оксалаты кальция. Токсичен для кошек и собак: вызывает жжение во рту, слюнотечение и отёк слизистых.');

INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'CURIOSITY', 'Интересный факт', 'Яркое «покрывало» — это не цветок, а видоизменённый лист (прицветник); настоящие мелкие цветки сидят на початке в центре.', NULL, 40
FROM species s WHERE s.latin_name = 'Anthurium'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'CURIOSITY' AND f.body = 'Яркое «покрывало» — это не цветок, а видоизменённый лист (прицветник); настоящие мелкие цветки сидят на початке в центре.');

-- ---------- Драцена (Dracaena) ----------
INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'ORIGIN', 'Родина', 'Род объединяет виды из тропической Африки, Мадагаскара и Юго-Восточной Азии. В природе многие драцены — древовидные растения.', 'Royal Botanic Gardens, Kew', 10
FROM species s WHERE s.latin_name = 'Dracaena'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'ORIGIN' AND f.body = 'Род объединяет виды из тропической Африки, Мадагаскара и Юго-Восточной Азии. В природе многие драцены — древовидные растения.');

INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'CARE', 'Уход', 'Неприхотлива: рассеянный свет и умеренный полив. Чувствительна к фтору и хлору в воде — кончики листьев буреют; лучше поливать отстоянной или фильтрованной водой.', NULL, 20
FROM species s WHERE s.latin_name = 'Dracaena'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'CARE' AND f.body = 'Неприхотлива: рассеянный свет и умеренный полив. Чувствительна к фтору и хлору в воде — кончики листьев буреют; лучше поливать отстоянной или фильтрованной водой.');

INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'TOXICITY', 'Токсичность', 'Содержит сапонины. Токсична для кошек и собак: вызывает рвоту (иногда с кровью), угнетённость и расширенные зрачки у кошек.', 'ASPCA', 30
FROM species s WHERE s.latin_name = 'Dracaena'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'TOXICITY' AND f.body = 'Содержит сапонины. Токсична для кошек и собак: вызывает рвоту (иногда с кровью), угнетённость и расширенные зрачки у кошек.');

-- ---------- Фиалка (Saintpaulia) ----------
INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'ORIGIN', 'Родина', 'Узамбарская фиалка происходит из горных лесов Восточной Африки (Танзания, Кения), где растёт во влажном тенистом микроклимате.', 'Royal Botanic Gardens, Kew', 10
FROM species s WHERE s.latin_name = 'Saintpaulia'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'ORIGIN' AND f.body = 'Узамбарская фиалка происходит из горных лесов Восточной Африки (Танзания, Кения), где растёт во влажном тенистом микроклимате.');

INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'CARE', 'Уход', 'Поливают снизу или в поддон, чтобы вода не попадала на опушённые листья и в розетку. Любит мягкий рассеянный свет и стабильное тепло; при должном уходе цветёт почти круглый год.', NULL, 20
FROM species s WHERE s.latin_name = 'Saintpaulia'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'CARE' AND f.body = 'Поливают снизу или в поддон, чтобы вода не попадала на опушённые листья и в розетку. Любит мягкий рассеянный свет и стабильное тепло; при должном уходе цветёт почти круглый год.');

INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'TOXICITY', 'Токсичность', 'Не токсична для кошек и собак — безопасный выбор для дома с питомцами.', 'ASPCA', 30
FROM species s WHERE s.latin_name = 'Saintpaulia'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'TOXICITY' AND f.body = 'Не токсична для кошек и собак — безопасный выбор для дома с питомцами.');

INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'CURIOSITY', 'Интересный факт', 'Размножается листовым черенком: воткнутый во влажный субстрат лист даёт корни и новые розетки.', NULL, 40
FROM species s WHERE s.latin_name = 'Saintpaulia'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'CURIOSITY' AND f.body = 'Размножается листовым черенком: воткнутый во влажный субстрат лист даёт корни и новые розетки.');

-- ---------- Калатея (Calathea) ----------
INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'ORIGIN', 'Родина', 'Происходит из влажных тропических лесов Южной Америки, преимущественно бассейна Амазонки.', 'Royal Botanic Gardens, Kew', 10
FROM species s WHERE s.latin_name = 'Calathea'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'ORIGIN' AND f.body = 'Происходит из влажных тропических лесов Южной Америки, преимущественно бассейна Амазонки.');

INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'CARE', 'Уход', 'Капризна: требует высокой влажности воздуха, тепла и мягкой воды. На жёсткую воду и сухой воздух реагирует бурыми краями листьев. Прямое солнце противопоказано.', NULL, 20
FROM species s WHERE s.latin_name = 'Calathea'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'CARE' AND f.body = 'Капризна: требует высокой влажности воздуха, тепла и мягкой воды. На жёсткую воду и сухой воздух реагирует бурыми краями листьев. Прямое солнце противопоказано.');

INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'TOXICITY', 'Токсичность', 'Не токсична для кошек и собак — подходит для дома с животными.', 'ASPCA', 30
FROM species s WHERE s.latin_name = 'Calathea'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'TOXICITY' AND f.body = 'Не токсична для кошек и собак — подходит для дома с животными.');

INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'CURIOSITY', 'Интересный факт', 'К вечеру поднимает и сворачивает листья, а утром расправляет — за это растения семейства называют «молитвенными».', NULL, 40
FROM species s WHERE s.latin_name = 'Calathea'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'CURIOSITY' AND f.body = 'К вечеру поднимает и сворачивает листья, а утром расправляет — за это растения семейства называют «молитвенными».');

-- ---------- Диффенбахия (Dieffenbachia) ----------
INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'ORIGIN', 'Родина', 'Родом из тропических лесов Центральной и Южной Америки, растёт в тёплом влажном подлеске.', 'Royal Botanic Gardens, Kew', 10
FROM species s WHERE s.latin_name = 'Dieffenbachia'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'ORIGIN' AND f.body = 'Родом из тропических лесов Центральной и Южной Америки, растёт в тёплом влажном подлеске.');

INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'CARE', 'Уход', 'Любит тепло, влажность и рассеянный свет. Поливают после подсыхания верхнего слоя. При обрезке работайте в перчатках: сок едкий.', NULL, 20
FROM species s WHERE s.latin_name = 'Dieffenbachia'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'CARE' AND f.body = 'Любит тепло, влажность и рассеянный свет. Поливают после подсыхания верхнего слоя. При обрезке работайте в перчатках: сок едкий.');

INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'TOXICITY', 'Токсичность', 'Содержит нерастворимые оксалаты кальция. Токсична для кошек и собак, опасна для детей: сок вызывает сильное жжение, отёк рта и глотки, временную потерю речи (отсюда народное название «немой тростник»).', 'ASPCA', 30
FROM species s WHERE s.latin_name = 'Dieffenbachia'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'TOXICITY' AND f.body = 'Содержит нерастворимые оксалаты кальция. Токсична для кошек и собак, опасна для детей: сок вызывает сильное жжение, отёк рта и глотки, временную потерю речи (отсюда народное название «немой тростник»).');

-- ---------- Плющ (Hedera helix) ----------
INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'ORIGIN', 'Родина', 'Вечнозелёная лиана родом из Европы и Западной Азии, в природе взбирается по стволам и стенам с помощью воздушных корней-присосок.', 'Royal Botanic Gardens, Kew', 10
FROM species s WHERE s.latin_name = 'Hedera helix'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'ORIGIN' AND f.body = 'Вечнозелёная лиана родом из Европы и Западной Азии, в природе взбирается по стволам и стенам с помощью воздушных корней-присосок.');

INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'CARE', 'Уход', 'Теневынослив, любит прохладу и влажный воздух. Хорош для подвесных кашпо. В сухом тёплом воздухе его часто поражает паутинный клещ.', NULL, 20
FROM species s WHERE s.latin_name = 'Hedera helix'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'CARE' AND f.body = 'Теневынослив, любит прохладу и влажный воздух. Хорош для подвесных кашпо. В сухом тёплом воздухе его часто поражает паутинный клещ.');

INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'TOXICITY', 'Токсичность', 'Содержит сапонины. Токсичен для кошек и собак: вызывает слюнотечение, рвоту, боль в животе; сок раздражает кожу у людей.', 'ASPCA', 30
FROM species s WHERE s.latin_name = 'Hedera helix'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'TOXICITY' AND f.body = 'Содержит сапонины. Токсичен для кошек и собак: вызывает слюнотечение, рвоту, боль в животе; сок раздражает кожу у людей.');

-- ---------- Филодендрон (Philodendron) ----------
INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'ORIGIN', 'Родина', 'Большой род из тропиков Центральной и Южной Америки; включает и лианы, и крупнолистные нелазящие виды.', 'Royal Botanic Gardens, Kew', 10
FROM species s WHERE s.latin_name = 'Philodendron'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'ORIGIN' AND f.body = 'Большой род из тропиков Центральной и Южной Америки; включает и лианы, и крупнолистные нелазящие виды.');

INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'CARE', 'Уход', 'Неприхотлив: рассеянный свет и умеренный полив после подсыхания верхнего слоя. Лазящим формам нужна опора. Хорошо переносит лёгкую полутень.', NULL, 20
FROM species s WHERE s.latin_name = 'Philodendron'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'CARE' AND f.body = 'Неприхотлив: рассеянный свет и умеренный полив после подсыхания верхнего слоя. Лазящим формам нужна опора. Хорошо переносит лёгкую полутень.');

INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'TOXICITY', 'Токсичность', 'Содержит нерастворимые оксалаты кальция. Токсичен для кошек и собак: вызывает жжение во рту, слюнотечение и затруднённое глотание.', 'ASPCA', 30
FROM species s WHERE s.latin_name = 'Philodendron'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'TOXICITY' AND f.body = 'Содержит нерастворимые оксалаты кальция. Токсичен для кошек и собак: вызывает жжение во рту, слюнотечение и затруднённое глотание.');

-- ---------- Папоротник нефролепис (Nephrolepis exaltata) ----------
INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'ORIGIN', 'Родина', 'Бостонский папоротник происходит из влажных тропических лесов по всему миру; размножается спорами, а не семенами.', 'Royal Botanic Gardens, Kew', 10
FROM species s WHERE s.latin_name = 'Nephrolepis exaltata'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'ORIGIN' AND f.body = 'Бостонский папоротник происходит из влажных тропических лесов по всему миру; размножается спорами, а не семенами.');

INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'CARE', 'Уход', 'Требует высокой влажности воздуха и постоянно слегка влажного субстрата. От сухого воздуха кончики вай буреют и осыпаются. Любит тень и рассеянный свет.', NULL, 20
FROM species s WHERE s.latin_name = 'Nephrolepis exaltata'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'CARE' AND f.body = 'Требует высокой влажности воздуха и постоянно слегка влажного субстрата. От сухого воздуха кончики вай буреют и осыпаются. Любит тень и рассеянный свет.');

INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'TOXICITY', 'Токсичность', 'Не токсичен для кошек и собак — безопасный выбор для дома с питомцами.', 'ASPCA', 30
FROM species s WHERE s.latin_name = 'Nephrolepis exaltata'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'TOXICITY' AND f.body = 'Не токсичен для кошек и собак — безопасный выбор для дома с питомцами.');

-- ---------- Пеперомия (Peperomia) ----------
INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'ORIGIN', 'Родина', 'Род из тропиков Центральной и Южной Америки; многие виды — небольшие эпифиты с мясистыми листьями.', 'Royal Botanic Gardens, Kew', 10
FROM species s WHERE s.latin_name = 'Peperomia'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'ORIGIN' AND f.body = 'Род из тропиков Центральной и Южной Америки; многие виды — небольшие эпифиты с мясистыми листьями.');

INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'CARE', 'Уход', 'Компактна и неприхотлива: рассеянный свет и умеренный полив. Мясистые листья запасают воду, поэтому перелив опаснее пересушки.', NULL, 20
FROM species s WHERE s.latin_name = 'Peperomia'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'CARE' AND f.body = 'Компактна и неприхотлива: рассеянный свет и умеренный полив. Мясистые листья запасают воду, поэтому перелив опаснее пересушки.');

INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'TOXICITY', 'Токсичность', 'Не токсична для кошек и собак — безопасна для дома с животными.', 'ASPCA', 30
FROM species s WHERE s.latin_name = 'Peperomia'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'TOXICITY' AND f.body = 'Не токсична для кошек и собак — безопасна для дома с животными.');

-- ---------- Хойя (Hoya carnosa) ----------
INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'ORIGIN', 'Родина', 'Восковой плющ родом из Восточной Азии и Австралии; в природе — лиана-эпифит с плотными восковыми листьями.', 'Royal Botanic Gardens, Kew', 10
FROM species s WHERE s.latin_name = 'Hoya carnosa'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'ORIGIN' AND f.body = 'Восковой плющ родом из Восточной Азии и Австралии; в природе — лиана-эпифит с плотными восковыми листьями.');

INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'CARE', 'Уход', 'Нужен яркий рассеянный свет и умеренный полив. Не обрезайте отцветшие цветоносы — на них образуются новые «зонтики» цветков в следующем сезоне.', NULL, 20
FROM species s WHERE s.latin_name = 'Hoya carnosa'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'CARE' AND f.body = 'Нужен яркий рассеянный свет и умеренный полив. Не обрезайте отцветшие цветоносы — на них образуются новые «зонтики» цветков в следующем сезоне.');

INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'TOXICITY', 'Токсичность', 'Не токсична для кошек и собак — безопасна для дома с питомцами.', 'ASPCA', 30
FROM species s WHERE s.latin_name = 'Hoya carnosa'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'TOXICITY' AND f.body = 'Не токсична для кошек и собак — безопасна для дома с питомцами.');

-- ---------- Маранта (Maranta leuconeura) ----------
INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'ORIGIN', 'Родина', 'Происходит из влажных тропических лесов Бразилии, растёт в притенённом подлеске.', 'Royal Botanic Gardens, Kew', 10
FROM species s WHERE s.latin_name = 'Maranta leuconeura'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'ORIGIN' AND f.body = 'Происходит из влажных тропических лесов Бразилии, растёт в притенённом подлеске.');

INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'CARE', 'Уход', 'Любит высокую влажность, тепло и мягкую воду, не выносит прямого солнца. Сухой воздух приводит к бурым краям листьев.', NULL, 20
FROM species s WHERE s.latin_name = 'Maranta leuconeura'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'CARE' AND f.body = 'Любит высокую влажность, тепло и мягкую воду, не выносит прямого солнца. Сухой воздух приводит к бурым краям листьев.');

INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'TOXICITY', 'Токсичность', 'Не токсична для кошек и собак — безопасна для дома с животными.', 'ASPCA', 30
FROM species s WHERE s.latin_name = 'Maranta leuconeura'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'TOXICITY' AND f.body = 'Не токсична для кошек и собак — безопасна для дома с животными.');

INSERT INTO species_facts (species_id, category, title, body, source, display_order)
SELECT s.id, 'CURIOSITY', 'Интересный факт', 'Молитвенное растение: вечером поднимает листья вверх, как сложенные ладони, а утром опускает.', NULL, 40
FROM species s WHERE s.latin_name = 'Maranta leuconeura'
  AND NOT EXISTS (SELECT 1 FROM species_facts f WHERE f.species_id = s.id AND f.category = 'CURIOSITY' AND f.body = 'Молитвенное растение: вечером поднимает листья вверх, как сложенные ладони, а утром опускает.');

-- =====================================================
-- Токсичность видов (species.toxic_to_*). Привязка по latin_name (стабильный ключ).
-- Данные выверены по ASPCA. UPDATE идемпотентен. Виды без достоверных данных НЕ трогаем
-- (остаются NULL — "нет данных").
-- =====================================================

-- Токсичные (оксалаты кальция / сапонины / латекс): токсичны для кошек и собак.
UPDATE species SET toxic_to_cats = TRUE,  toxic_to_dogs = TRUE  WHERE latin_name = 'Monstera deliciosa';
UPDATE species SET toxic_to_cats = TRUE,  toxic_to_dogs = TRUE  WHERE latin_name = 'Sansevieria trifasciata';
UPDATE species SET toxic_to_cats = TRUE,  toxic_to_dogs = TRUE  WHERE latin_name = 'Ficus benjamina';
UPDATE species SET toxic_to_cats = TRUE,  toxic_to_dogs = TRUE  WHERE latin_name = 'Ficus elastica';
UPDATE species SET toxic_to_cats = TRUE,  toxic_to_dogs = TRUE  WHERE latin_name = 'Zamioculcas zamiifolia';
UPDATE species SET toxic_to_cats = TRUE,  toxic_to_dogs = TRUE  WHERE latin_name = 'Spathiphyllum';
UPDATE species SET toxic_to_cats = TRUE,  toxic_to_dogs = TRUE  WHERE latin_name = 'Anthurium';
UPDATE species SET toxic_to_cats = TRUE,  toxic_to_dogs = TRUE  WHERE latin_name = 'Dracaena';
UPDATE species SET toxic_to_cats = TRUE,  toxic_to_dogs = TRUE  WHERE latin_name = 'Dieffenbachia';
UPDATE species SET toxic_to_cats = TRUE,  toxic_to_dogs = TRUE  WHERE latin_name = 'Hedera helix';
UPDATE species SET toxic_to_cats = TRUE,  toxic_to_dogs = TRUE  WHERE latin_name = 'Philodendron';
UPDATE species SET toxic_to_cats = TRUE,  toxic_to_dogs = TRUE  WHERE latin_name = 'Crassula ovata';
UPDATE species SET toxic_to_cats = TRUE,  toxic_to_dogs = TRUE  WHERE latin_name = 'Aloe vera';

-- Безопасные по данным ASPCA: не токсичны для кошек и собак.
UPDATE species SET toxic_to_cats = FALSE, toxic_to_dogs = FALSE WHERE latin_name = 'Phalaenopsis';
UPDATE species SET toxic_to_cats = FALSE, toxic_to_dogs = FALSE WHERE latin_name = 'Saintpaulia';
UPDATE species SET toxic_to_cats = FALSE, toxic_to_dogs = FALSE WHERE latin_name = 'Calathea';
UPDATE species SET toxic_to_cats = FALSE, toxic_to_dogs = FALSE WHERE latin_name = 'Maranta leuconeura';
UPDATE species SET toxic_to_cats = FALSE, toxic_to_dogs = FALSE WHERE latin_name = 'Peperomia';
UPDATE species SET toxic_to_cats = FALSE, toxic_to_dogs = FALSE WHERE latin_name = 'Hoya carnosa';
UPDATE species SET toxic_to_cats = FALSE, toxic_to_dogs = FALSE WHERE latin_name = 'Nephrolepis exaltata';
UPDATE species SET toxic_to_cats = FALSE, toxic_to_dogs = FALSE WHERE latin_name = 'Chlorophytum comosum';

-- toxic_to_humans: проставляем только там, где есть выраженный риск для людей при
-- разжёвывании/контакте (оксалаты, едкий млечный сок). Для остальных оставляем NULL,
-- чтобы не давать ложного сигнала "безопасно для людей" по непроверенным данным.
UPDATE species SET toxic_to_humans = TRUE WHERE latin_name = 'Dieffenbachia';
UPDATE species SET toxic_to_humans = TRUE WHERE latin_name = 'Monstera deliciosa';
UPDATE species SET toxic_to_humans = TRUE WHERE latin_name = 'Spathiphyllum';
UPDATE species SET toxic_to_humans = TRUE WHERE latin_name = 'Anthurium';
UPDATE species SET toxic_to_humans = TRUE WHERE latin_name = 'Philodendron';
UPDATE species SET toxic_to_humans = TRUE WHERE latin_name = 'Zamioculcas zamiifolia';

COMMIT;
