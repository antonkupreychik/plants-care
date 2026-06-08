-- V49__seed_species_catalog.sql
-- Расширение справочника видов растений: +100 новых видов (каталог 30 → 130).
-- Группы: ароидные, пёстрые декоративнолиственные, пальмы, суккуленты, кактусы,
--   цветущие, орхидеи, папоротники, древовидные, ампельные, бромелиевые, зелень.
--
-- Чистый сид, БЕЗ DDL (схема species уже есть в V1 + V26). Только INSERT.
--
-- Идемпотентность ОБЯЗАТЕЛЬНА: name UNIQUE, поэтому на КАЖДЫЙ вид —
--   INSERT ... SELECT ... WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = '<name>').
--   Плоский INSERT упал бы на повторном прогоне (нарушение UNIQUE по name).
--   Дедуп по name; повторный прогон не плодит дубли и не падает.
--
-- Привязки к id нет — хардкод id запрещён (BIGSERIAL не гарантирован между средами).
--
-- Интервалы ухода — для активного сезона (весна-лето), как в V2. NULL означает,
--   что для вида соответствующий уход не требуется (например, опрыскивание у суккулентов;
--   удобрение у литопсов/мухоловки в выраженном покое).
--
-- Токсичность (toxic_to_cats/dogs/humans): выверена по общеизвестным данным ASPCA
--   (toxic / non-toxic to cats & dogs). Где по виду нет уверенности (в т.ч. по людям) —
--   флаг остаётся NULL ("нет данных"), чтобы не давать ложный сигнал "безопасно".
--
-- Backward-compat note: добавляются ТОЛЬКО новые строки в справочник. Старый код этот
--   справочник только читает (или игнорирует новые записи). Rolling deploy безопасен.

BEGIN;

-- =====================================================
-- A. Ароидные
-- =====================================================

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Эпипремнум (Потос)', 'Epipremnum aureum', 7, 5, 30, 'PARTIAL', 'EASY',
       'Неприхотливая ампельная лиана, прощает нерегулярный полив и полутень.',
       'эпипремнум, потос, золотистый, epipremnum, pothos, лиана', 78,
       TRUE, TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Эпипремнум (Потос)');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Сциндапсус расписной', 'Scindapsus pictus', 7, 5, 30, 'PARTIAL', 'EASY',
       'Лиана с серебристыми пятнами на матовых листьях, любит рассеянный свет.',
       'сциндапсус, расписной, scindapsus, pictus, лиана', 60,
       TRUE, TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Сциндапсус расписной');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Сингониум', 'Syngonium podophyllum', 5, 4, 30, 'PARTIAL', 'EASY',
       'Быстрорастущая лиана со стреловидными листьями, легко черенкуется.',
       'сингониум, syngonium, podophyllum, лиана', 58,
       TRUE, TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Сингониум');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Монстера Адансона', 'Monstera adansonii', 7, 4, 30, 'PARTIAL', 'EASY',
       'Компактная лиана с дырчатыми листьями, любит опору и рассеянный свет.',
       'монстера, адансона, monstera, adansonii, лиана', 62,
       TRUE, TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Монстера Адансона');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Филодендрон краснеющий', 'Philodendron erubescens', 7, 4, 30, 'PARTIAL', 'EASY',
       'Лиана с красноватыми черешками, неприхотлива и теневынослива.',
       'филодендрон, краснеющий, philodendron, erubescens, лиана', 55,
       TRUE, TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Филодендрон краснеющий');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Алоказия амазонская', 'Alocasia × amazonica', 5, 3, 30, 'PARTIAL', 'HARD',
       'Эффектные тёмные листья с белыми жилками, требует тепла и влажности.',
       'алоказия, амазонская, alocasia, amazonica', 50,
       TRUE, TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Алоказия амазонская');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Алоказия зебрина', 'Alocasia zebrina', 6, 3, 30, 'BRIGHT', 'HARD',
       'Ценится за полосатые «зебровые» черешки, капризна к поливу и влажности.',
       'алоказия, зебрина, alocasia, zebrina', 48,
       TRUE, TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Алоказия зебрина');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Каладиум', 'Caladium bicolor', 4, 3, 21, 'PARTIAL', 'MEDIUM',
       'Яркие сердцевидные листья; зимой клубень уходит в покой.',
       'каладиум, caladium, bicolor', 47,
       TRUE, TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Каладиум');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Колоказия (Таро)', 'Colocasia esculenta', 3, 3, 21, 'BRIGHT', 'MEDIUM',
       'Крупное «слоновье ухо», любит обильный полив и тепло.',
       'колоказия, таро, colocasia, esculenta, слоновье ухо', 45,
       TRUE, TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Колоказия (Таро)');

-- =====================================================
-- B. Пёстрые декоративнолиственные
-- =====================================================

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Фиттония', 'Fittonia albivenis', 3, 2, 30, 'PARTIAL', 'HARD',
       'Миниатюрная с яркими прожилками; не выносит пересушки, любит влажность.',
       'фиттония, fittonia, albivenis', 52,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Фиттония');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Строманта', 'Stromanthe sanguinea', 4, 2, 21, 'PARTIAL', 'HARD',
       'Пёстрые листья с розовой изнанкой, требует высокой влажности и мягкой воды.',
       'строманта, stromanthe, sanguinea', 46,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Строманта');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Ктенанте', 'Ctenanthe', 4, 2, 21, 'PARTIAL', 'HARD',
       'Родственница калатеи с узорчатыми листьями, капризна к влажности.',
       'ктенанте, ctenanthe', 44,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Ктенанте');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Калатея орбифолия', 'Calathea orbifolia', 4, 2, 21, 'PARTIAL', 'HARD',
       'Крупные округлые листья с серебристыми полосами; нужна влажность и мягкая вода.',
       'калатея, орбифолия, calathea, orbifolia', 54,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Калатея орбифолия');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Кротон', 'Codiaeum variegatum', 6, 3, 30, 'BRIGHT', 'MEDIUM',
       'Пёстрые кожистые листья; яркая окраска проявляется только при хорошем свете.',
       'кротон, codiaeum, variegatum', 53,
       TRUE, TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Кротон');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Колеус', 'Plectranthus scutellarioides', 4, NULL, 21, 'BRIGHT', 'EASY',
       'Яркая декоративнолиственная культура; прищипка делает кустик пышнее.',
       'колеус, coleus, plectranthus, scutellarioides', 50,
       TRUE, TRUE, NULL
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Колеус');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Гипоэстес', 'Hypoestes phyllostachya', 4, 3, 30, 'PARTIAL', 'EASY',
       'Компактная с розово-крапчатыми листьями, любит рассеянный свет и влажность.',
       'гипоэстес, hypoestes, phyllostachya', 45,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Гипоэстес');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Ирезине', 'Iresine herbstii', 4, NULL, 30, 'BRIGHT', 'MEDIUM',
       'Насыщенно-бордовые листья; яркость зависит от количества света.',
       'ирезине, iresine, herbstii', 42,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Ирезине');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Аукуба японская', 'Aucuba japonica', 7, NULL, 30, 'PARTIAL', 'EASY',
       'Кожистые листья с золотыми крапинами, теневынослива и неприхотлива.',
       'аукуба, японская, aucuba, japonica', 43,
       NULL, NULL, TRUE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Аукуба японская');

-- =====================================================
-- C. Пальмы
-- =====================================================

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Хамедорея изящная', 'Chamaedorea elegans', 7, 5, 30, 'PARTIAL', 'EASY',
       'Компактная теневыносливая пальма, отлично чистит воздух.',
       'хамедорея, изящная, chamaedorea, elegans, пальма', 72,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Хамедорея изящная');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Ховея (Кентия)', 'Howea forsteriana', 7, 5, 30, 'PARTIAL', 'EASY',
       'Элегантная медленнорастущая пальма, переносит полутень и сухой воздух.',
       'ховея, кентия, howea, forsteriana, kentia, пальма', 60,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Ховея (Кентия)');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Арека', 'Dypsis lutescens', 5, 4, 30, 'BRIGHT', 'MEDIUM',
       'Перистая пальма-«бабочка», любит яркий свет и влажный воздух.',
       'арека, areca, dypsis, lutescens, пальма', 58,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Арека');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Рапис высокий', 'Rhapis excelsa', 7, 5, 30, 'PARTIAL', 'EASY',
       'Веерная бамбуковидная пальма, теневынослива и неприхотлива.',
       'рапис, высокий, rhapis, excelsa, пальма', 50,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Рапис высокий');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Финик Робелена', 'Phoenix roebelenii', 7, 4, 30, 'BRIGHT', 'MEDIUM',
       'Карликовая финиковая пальма с изящными перистыми листьями.',
       'финик, робелена, phoenix, roebelenii, пальма', 48,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Финик Робелена');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Ливистона', 'Livistona chinensis', 7, 5, 30, 'BRIGHT', 'MEDIUM',
       'Веерная пальма с крупными резными листьями, любит яркий свет.',
       'ливистона, livistona, chinensis, пальма', 44,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Ливистона');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Кариота (рыбий хвост)', 'Caryota mitis', 6, 4, 30, 'BRIGHT', 'HARD',
       'Необычные листья формой как рыбий хвост; требует тепла и влажности.',
       'кариота, рыбий хвост, caryota, mitis, пальма', 41,
       TRUE, TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Кариота (рыбий хвост)');

-- =====================================================
-- D. Суккуленты
-- =====================================================

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Эхеверия', 'Echeveria', 14, NULL, 60, 'DIRECT', 'EASY',
       'Розеточный суккулент; много света и редкий полив после просыхания грунта.',
       'эхеверия, echeveria, суккулент', 62,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Эхеверия');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Хавортия', 'Haworthia fasciata', 14, NULL, 60, 'BRIGHT', 'EASY',
       'Компактный суккулент с белыми полосками, терпит редкий полив.',
       'хавортия, haworthia, fasciata, суккулент', 58,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Хавортия');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Гастерия', 'Gasteria', 14, NULL, 60, 'BRIGHT', 'EASY',
       'Неприхотливый суккулент с языковидными листьями, терпит полутень.',
       'гастерия, gasteria, суккулент', 44,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Гастерия');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Литопс (живые камни)', 'Lithops', 21, NULL, NULL, 'DIRECT', 'MEDIUM',
       '«Живые камни»: крайне редкий полив, зимой почти сухое содержание.',
       'литопс, живые камни, lithops, суккулент', 48,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Литопс (живые камни)');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Седум (очиток)', 'Sedum morganianum', 14, NULL, 60, 'DIRECT', 'EASY',
       'Ампельный суккулент с «хвостами» из мясистых листьев, любит солнце.',
       'седум, очиток, sedum, morganianum, суккулент', 50,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Седум (очиток)');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Каланхоэ Блоссфельда', 'Kalanchoe blossfeldiana', 10, NULL, 30, 'BRIGHT', 'EASY',
       'Цветущий суккулент; обильно цветёт при ярком свете и редком поливе.',
       'каланхоэ, блоссфельда, kalanchoe, blossfeldiana, суккулент', 55,
       TRUE, TRUE, NULL
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Каланхоэ Блоссфельда');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Граптопеталум', 'Graptopetalum', 14, NULL, 60, 'DIRECT', 'EASY',
       'Розеточный суккулент с восковым налётом, легко черенкуется.',
       'граптопеталум, graptopetalum, суккулент', 42,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Граптопеталум');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Молочай трёхгранный', 'Euphorbia trigona', 14, NULL, 30, 'BRIGHT', 'EASY',
       'Колонновидный суккулентный молочай; млечный сок едкий, работайте в перчатках.',
       'молочай, трёхгранный, эуфорбия, euphorbia, trigona', 50,
       TRUE, TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Молочай трёхгранный');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Молочай Миля', 'Euphorbia milii', 10, NULL, 30, 'DIRECT', 'EASY',
       'Колючий «терновый венец» с яркими прицветниками; сок едкий и ядовит.',
       'молочай, миля, терновый венец, euphorbia, milii', 47,
       TRUE, TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Молочай Миля');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Агава американская', 'Agave americana', 21, NULL, 60, 'DIRECT', 'EASY',
       'Крупная розетка жёстких листьев, любит солнце и очень редкий полив.',
       'агава, американская, agave, americana, суккулент', 45,
       TRUE, TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Агава американская');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Нолина (бутылочное дерево)', 'Beaucarnea recurvata', 14, NULL, 60, 'BRIGHT', 'EASY',
       'Запасает воду в утолщённом основании ствола; терпит длительную засуху.',
       'нолина, бутылочное дерево, beaucarnea, recurvata, конский хвост', 52,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Нолина (бутылочное дерево)');

-- =====================================================
-- E. Кактусы
-- =====================================================

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Шлюмбергера (Декабрист)', 'Schlumbergera', 7, 3, 30, 'PARTIAL', 'EASY',
       'Лесной кактус, цветёт зимой; не любит пересушки во время бутонизации.',
       'шлюмбергера, декабрист, рождественник, schlumbergera, кактус', 60,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Шлюмбергера (Декабрист)');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Рипсалис', 'Rhipsalis', 7, 4, 30, 'PARTIAL', 'EASY',
       'Ампельный лесной кактус из тонких побегов, любит рассеянный свет.',
       'рипсалис, rhipsalis, кактус', 48,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Рипсалис');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Маммиллярия', 'Mammillaria', 21, NULL, 60, 'DIRECT', 'EASY',
       'Шаровидный кактус, обильно цветёт венчиком мелких цветков; минимум воды.',
       'маммиллярия, mammillaria, кактус', 46,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Маммиллярия');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Эхинокактус (Золотой шар)', 'Echinocactus grusonii', 21, NULL, 60, 'DIRECT', 'EASY',
       'Шаровидный «золотой шар» с жёлтыми колючками, любит солнце и сухость.',
       'эхинокактус, золотой шар, echinocactus, grusonii, кактус', 50,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Эхинокактус (Золотой шар)');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Опунция', 'Opuntia', 21, NULL, 60, 'DIRECT', 'EASY',
       'Кактус из плоских члеников с мелкими колючками-глохидиями; минимум воды.',
       'опунция, opuntia, кактус', 44,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Опунция');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Гимнокалициум', 'Gymnocalycium', 18, NULL, 60, 'BRIGHT', 'EASY',
       'Шаровидный кактус, легко цветёт; редкий полив, светлое место без жгучего солнца.',
       'гимнокалициум, gymnocalycium, кактус', 42,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Гимнокалициум');

-- =====================================================
-- F. Цветущие
-- =====================================================

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Гибискус китайский', 'Hibiscus rosa-sinensis', 4, 3, 14, 'BRIGHT', 'MEDIUM',
       'Китайская роза с крупными цветками, цветёт при ярком свете и обильном поливе.',
       'гибискус, китайская роза, hibiscus, rosa-sinensis', 55,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Гибискус китайский');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Абутилон (комнатный клён)', 'Abutilon', 4, 3, 14, 'BRIGHT', 'MEDIUM',
       'Комнатный клён с колокольчатыми цветками, цветёт почти круглый год.',
       'абутилон, комнатный клён, abutilon', 44,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Абутилон (комнатный клён)');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Пуансеттия', 'Euphorbia pulcherrima', 5, 3, 30, 'BRIGHT', 'MEDIUM',
       'Рождественская звезда с яркими прицветниками; млечный сок раздражает.',
       'пуансеттия, рождественская звезда, euphorbia, pulcherrima', 50,
       TRUE, TRUE, NULL
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Пуансеттия');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Цикламен персидский', 'Cyclamen persicum', 5, NULL, 21, 'PARTIAL', 'MEDIUM',
       'Цветёт зимой; полив снизу, летом клубень уходит в покой.',
       'цикламен, персидский, cyclamen, persicum', 52,
       TRUE, TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Цикламен персидский');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Глоксиния', 'Sinningia speciosa', 4, NULL, 21, 'PARTIAL', 'MEDIUM',
       'Бархатные колокольчатые цветки; полив снизу, на зиму клубень в покой.',
       'глоксиния, синнингия, sinningia, speciosa', 46,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Глоксиния');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Стрептокарпус', 'Streptocarpus', 5, NULL, 21, 'PARTIAL', 'MEDIUM',
       'Обильно цветущий родственник фиалки; полив умеренный, не на листья.',
       'стрептокарпус, streptocarpus', 44,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Стрептокарпус');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Азалия (индийская)', 'Rhododendron simsii', 3, 3, 21, 'PARTIAL', 'HARD',
       'Капризно цветёт; нужна прохлада, мягкая кислая вода и постоянная влажность.',
       'азалия, индийская, рододендрон, rhododendron, simsii', 48,
       TRUE, TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Азалия (индийская)');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Камелия японская', 'Camellia japonica', 5, 3, 30, 'PARTIAL', 'HARD',
       'Цветёт зимой; требует прохлады, кислой почвы и мягкой воды.',
       'камелия, японская, camellia, japonica', 43,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Камелия японская');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Жасмин самбак', 'Jasminum sambac', 4, 3, 21, 'BRIGHT', 'MEDIUM',
       'Ароматная лиана с белыми цветками, любит яркий свет и тепло.',
       'жасмин, самбак, jasminum, sambac', 45,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Жасмин самбак');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Гардения', 'Gardenia jasminoides', 4, 4, 21, 'BRIGHT', 'HARD',
       'Ароматные белые цветки; капризна к кислой почве, влажности и мягкой воде.',
       'гардения, gardenia, jasminoides', 44,
       TRUE, TRUE, NULL
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Гардения');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Бугенвиллия', 'Bougainvillea', 5, NULL, 21, 'DIRECT', 'MEDIUM',
       'Яркие прицветники на солнце; любит много света и просушку между поливами.',
       'бугенвиллия, bougainvillea', 46,
       FALSE, FALSE, NULL
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Бугенвиллия');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Фуксия', 'Fuchsia', 3, 3, 14, 'PARTIAL', 'MEDIUM',
       'Изящные «серёжки» цветков; любит прохладу, влажность и рассеянный свет.',
       'фуксия, fuchsia', 47,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Фуксия');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Кливия', 'Clivia miniata', 7, NULL, 30, 'PARTIAL', 'EASY',
       'Зонтики оранжевых цветков; для цветения нужен зимний прохладный покой.',
       'кливия, clivia, miniata', 50,
       TRUE, TRUE, NULL
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Кливия');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Гиппеаструм', 'Hippeastrum', 7, NULL, 21, 'BRIGHT', 'EASY',
       'Крупные цветки из луковицы; после цветения луковице нужен период покоя.',
       'гиппеаструм, амариллис, hippeastrum', 52,
       TRUE, TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Гиппеаструм');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Эухарис (амазонская лилия)', 'Eucharis grandiflora', 7, 3, 30, 'PARTIAL', 'MEDIUM',
       'Ароматные белые цветки-нарциссы; теневынослива, любит тепло.',
       'эухарис, амазонская лилия, eucharis, grandiflora', 43,
       TRUE, TRUE, NULL
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Эухарис (амазонская лилия)');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Олеандр', 'Nerium oleander', 7, NULL, 30, 'DIRECT', 'EASY',
       'Солнцелюбивый кустарник с ароматными цветками; все части очень ядовиты.',
       'олеандр, nerium, oleander', 42,
       TRUE, TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Олеандр');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Пахистахис', 'Pachystachys lutea', 4, 3, 21, 'BRIGHT', 'MEDIUM',
       'Жёлтые «свечи» соцветий с белыми цветками; любит свет и влажность.',
       'пахистахис, pachystachys, lutea', 41,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Пахистахис');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Афеландра', 'Aphelandra squarrosa', 4, 3, 21, 'PARTIAL', 'HARD',
       'Листья с белыми жилками и колосок-«свеча»; капризна к влажности и теплу.',
       'афеландра, aphelandra, squarrosa', 40,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Афеландра');

-- =====================================================
-- G. Орхидеи
-- =====================================================

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Дендробиум', 'Dendrobium', 7, NULL, 14, 'BRIGHT', 'MEDIUM',
       'Орхидея с цветками вдоль псевдобульб; нужен яркий свет и просушка субстрата.',
       'дендробиум, dendrobium, орхидея, orchid', 58,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Дендробиум');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Цимбидиум', 'Cymbidium', 7, NULL, 14, 'BRIGHT', 'HARD',
       'Крупные кисти цветков; для цветения нужен перепад дневных и ночных температур.',
       'цимбидиум, cymbidium, орхидея, orchid', 48,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Цимбидиум');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Камбрия', 'Cambria', 7, NULL, 14, 'PARTIAL', 'MEDIUM',
       'Гибридная орхидея с яркими «звёздчатыми» цветками; полив погружением.',
       'камбрия, cambria, орхидея, orchid', 46,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Камбрия');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Ванда', 'Vanda', 3, NULL, 14, 'BRIGHT', 'HARD',
       'Орхидея с открытыми корнями; растёт без субстрата, частый полив и много света.',
       'ванда, vanda, орхидея, orchid', 44,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Ванда');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Пафиопедилум', 'Paphiopedilum', 7, NULL, 21, 'PARTIAL', 'HARD',
       'Орхидея-«венерин башмачок»; теневынослива, любит постоянно влажный субстрат.',
       'пафиопедилум, венерин башмачок, paphiopedilum, орхидея, orchid', 42,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Пафиопедилум');

-- =====================================================
-- H. Папоротники
-- =====================================================

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Адиантум (венерин волос)', 'Adiantum', 3, 2, 30, 'SHADE', 'HARD',
       'Нежные ажурные вайи; требует высокой влажности и не выносит пересушки.',
       'адиантум, венерин волос, adiantum, папоротник', 48,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Адиантум (венерин волос)');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Асплениум (костенец)', 'Asplenium nidus', 4, 3, 30, 'PARTIAL', 'MEDIUM',
       'Папоротник с цельными воронковидными листьями; любит влажность и тень.',
       'асплениум, костенец, asplenium, nidus, папоротник', 46,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Асплениум (костенец)');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Платицериум (олений рог)', 'Platycerium', 5, 3, 30, 'PARTIAL', 'MEDIUM',
       'Эпифитный папоротник с «рогатыми» вайями; часто растят на блоке.',
       'платицериум, олений рог, platycerium, папоротник', 44,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Платицериум (олений рог)');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Птерис', 'Pteris', 4, 3, 30, 'PARTIAL', 'MEDIUM',
       'Изящный папоротник с перистыми вайями, любит влажность и полутень.',
       'птерис, pteris, папоротник', 41,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Птерис');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Даваллия (заячья лапка)', 'Davallia', 4, 3, 30, 'PARTIAL', 'MEDIUM',
       'Папоротник с пушистыми воздушными корневищами-«лапками»; любит влажность.',
       'даваллия, заячья лапка, davallia, папоротник', 42,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Даваллия (заячья лапка)');

-- =====================================================
-- I. Древовидные
-- =====================================================

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Фикус лировидный', 'Ficus lyrata', 7, 4, 30, 'BRIGHT', 'MEDIUM',
       'Крупные скрипковидные листья; не любит перестановок и сквозняков.',
       'фикус, лировидный, ficus, lyrata', 74,
       TRUE, TRUE, NULL
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Фикус лировидный');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Фикус Гинсенг', 'Ficus microcarpa', 7, 4, 30, 'BRIGHT', 'MEDIUM',
       'Бонсай с утолщёнными воздушными корнями; любит яркий свет.',
       'фикус, гинсенг, бонсай, ficus, microcarpa, ginseng', 56,
       TRUE, TRUE, NULL
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Фикус Гинсенг');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Фикус карликовый', 'Ficus pumila', 5, 4, 30, 'PARTIAL', 'EASY',
       'Мелколистная ампельная/почвопокровная лиана, любит влажность.',
       'фикус, карликовый, ficus, pumila', 48,
       TRUE, TRUE, NULL
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Фикус карликовый');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Полисциас', 'Polyscias', 7, 4, 30, 'BRIGHT', 'HARD',
       'Декоративное деревце с резной листвой; капризно к перестановкам и влажности.',
       'полисциас, polyscias', 42,
       TRUE, TRUE, NULL
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Полисциас');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Радермахера', 'Radermachera sinica', 6, 3, 30, 'BRIGHT', 'EASY',
       'Деревце с глянцевой ажурной листвой, неприхотливо при ярком свете.',
       'радермахера, radermachera, sinica', 44,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Радермахера');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Фатсия японская', 'Fatsia japonica', 5, 3, 30, 'PARTIAL', 'EASY',
       'Крупные пальчатые листья; теневынослива, любит прохладу и влажность.',
       'фатсия, японская, fatsia, japonica', 46,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Фатсия японская');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Кордилина', 'Cordyline fruticosa', 7, 4, 30, 'BRIGHT', 'MEDIUM',
       'Яркие пёстрые листья на стволике; любит свет, влажность и мягкую воду.',
       'кордилина, cordyline, fruticosa', 45,
       TRUE, TRUE, NULL
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Кордилина');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Кофейное дерево', 'Coffea arabica', 5, 3, 30, 'BRIGHT', 'MEDIUM',
       'Глянцевые листья и ароматные цветки; при хорошем уходе даёт плоды-«вишни».',
       'кофе, кофейное дерево, coffea, arabica', 50,
       TRUE, TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Кофейное дерево');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Лавр благородный', 'Laurus nobilis', 7, NULL, 30, 'BRIGHT', 'MEDIUM',
       'Ароматное вечнозелёное деревце; листья используют как пряность.',
       'лавр, благородный, laurus, nobilis', 46,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Лавр благородный');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Мирт обыкновенный', 'Myrtus communis', 5, 3, 30, 'BRIGHT', 'MEDIUM',
       'Ароматный кустарник с белыми цветками; любит свет, прохладную зимовку и влажность.',
       'мирт, обыкновенный, myrtus, communis', 43,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Мирт обыкновенный');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Лимон', 'Citrus limon', 5, 3, 21, 'DIRECT', 'MEDIUM',
       'Цитрус с ароматными цветками и плодами; любит солнце и равномерный полив.',
       'лимон, цитрус, citrus, limon', 55,
       TRUE, TRUE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Лимон');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Мандарин', 'Citrus reticulata', 5, 3, 21, 'DIRECT', 'MEDIUM',
       'Комнатный цитрус с ароматными плодами; любит солнце и тепло.',
       'мандарин, цитрус, citrus, reticulata', 50,
       TRUE, TRUE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Мандарин');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Гранат карликовый', 'Punica granatum ''Nana''', 6, NULL, 21, 'DIRECT', 'MEDIUM',
       'Миниатюрный гранат с яркими цветками и плодами; любит солнце и зимний покой.',
       'гранат, карликовый, punica, granatum, nana', 44,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Гранат карликовый');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Панданус', 'Pandanus', 7, 4, 30, 'BRIGHT', 'MEDIUM',
       'Винтовая пальма с длинными колючими листьями по спирали; любит тепло и свет.',
       'панданус, pandanus, винтовая пальма', 40,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Панданус');

-- =====================================================
-- J. Ампельные
-- =====================================================

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Церопегия Вуда (цепочка сердец)', 'Ceropegia woodii', 14, NULL, 30, 'BRIGHT', 'EASY',
       'Ампельный суккулент с сердцевидными листьями на нитях; редкий полив.',
       'церопегия, вуда, цепочка сердец, ceropegia, woodii', 50,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Церопегия Вуда (цепочка сердец)');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Сеткреазия (пурпурное сердце)', 'Tradescantia pallida', 7, NULL, 30, 'DIRECT', 'EASY',
       'Ампельная с фиолетовыми листьями; яркий цвет проявляется на солнце.',
       'сеткреазия, пурпурное сердце, tradescantia, pallida', 46,
       TRUE, TRUE, NULL
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Сеткреазия (пурпурное сердце)');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Зебрина', 'Tradescantia zebrina', 5, 3, 30, 'BRIGHT', 'EASY',
       'Полосатая ампельная традесканция; быстро разрастается и легко черенкуется.',
       'зебрина, традесканция, tradescantia, zebrina', 47,
       TRUE, TRUE, NULL
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Зебрина');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Камнеломка плетеносная', 'Saxifraga stolonifera', 5, 3, 30, 'PARTIAL', 'EASY',
       'Образует «детки» на длинных усах; неприхотлива, любит прохладу.',
       'камнеломка, плетеносная, saxifraga, stolonifera', 41,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Камнеломка плетеносная');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Колумнея', 'Columnea', 4, 3, 21, 'PARTIAL', 'MEDIUM',
       'Ампельная с яркими трубчатыми цветками; любит влажность и рассеянный свет.',
       'колумнея, columnea', 40,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Колумнея');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Эсхинантус', 'Aeschynanthus', 5, 3, 21, 'PARTIAL', 'MEDIUM',
       'Ампельная с яркими цветками-«помадами»; любит влажность и тепло.',
       'эсхинантус, aeschynanthus', 42,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Эсхинантус');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Сенецио Роули (жемчужная нить)', 'Curio rowleyanus', 14, NULL, 30, 'BRIGHT', 'MEDIUM',
       'Ампельный суккулент из «бусин»-листьев; редкий полив, хороший дренаж.',
       'сенецио, роули, жемчужная нить, curio, rowleyanus, senecio', 50,
       TRUE, TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Сенецио Роули (жемчужная нить)');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Пилея (китайские монетки)', 'Pilea peperomioides', 7, NULL, 30, 'PARTIAL', 'EASY',
       'Круглые «монетки»-листья на черешках; неприхотлива, активно даёт деток.',
       'пилея, китайские монетки, pilea, peperomioides', 70,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Пилея (китайские монетки)');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Каллизия', 'Callisia repens', 5, NULL, 30, 'BRIGHT', 'EASY',
       'Мелколистный почвопокровник/ампель; быстро разрастается, легко укореняется.',
       'каллизия, callisia, repens', 41,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Каллизия');

-- =====================================================
-- K. Бромелиевые
-- =====================================================

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Гузмания', 'Guzmania', 7, 4, 30, 'PARTIAL', 'MEDIUM',
       'Яркий прицветный «факел»; воду наливают в розетку, любит влажность.',
       'гузмания, guzmania, бромелия', 50,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Гузмания');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Вриезия', 'Vriesea', 7, 4, 30, 'PARTIAL', 'MEDIUM',
       'Плоский «меч»-соцветие; воду наливают в розетку, любит тепло и влажность.',
       'вриезия, vriesea, бромелия', 46,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Вриезия');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Эхмея', 'Aechmea fasciata', 7, 4, 30, 'BRIGHT', 'MEDIUM',
       'Серебристые листья и розовое соцветие; воду наливают в розетку.',
       'эхмея, aechmea, fasciata, бромелия', 44,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Эхмея');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Неорегелия', 'Neoregelia', 7, 4, 30, 'BRIGHT', 'MEDIUM',
       'Перед цветением центр розетки яркеет; воду наливают в розетку.',
       'неорегелия, neoregelia, бромелия', 42,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Неорегелия');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Криптантус', 'Cryptanthus', 7, 4, 30, 'PARTIAL', 'MEDIUM',
       'Наземная «земляная звезда» с пёстрой розеткой; любит влажность.',
       'криптантус, cryptanthus, земляная звезда, бромелия', 40,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Криптантус');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Тилландсия (атмосферная)', 'Tillandsia', 4, 2, 30, 'BRIGHT', 'MEDIUM',
       'Атмосферная бромелия без грунта; питается через листья, нужны опрыскивания.',
       'тилландсия, атмосферная, tillandsia, бромелия', 45,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Тилландсия (атмосферная)');

-- =====================================================
-- L. Зелень и прочее
-- =====================================================

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Аспидистра', 'Aspidistra elatior', 10, NULL, 30, 'SHADE', 'EASY',
       'Крайне теневыносливая и неприхотливая, терпит редкий полив.',
       'аспидистра, aspidistra, elatior', 46,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Аспидистра');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Циперус', 'Cyperus alternifolius', 2, 3, 21, 'PARTIAL', 'EASY',
       'Болотное растение-«зонтик»; держат горшок в поддоне с водой.',
       'циперус, cyperus, alternifolius, сыть', 42,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Циперус');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Солейролия (гелксина)', 'Soleirolia soleirolii', 3, 3, 30, 'PARTIAL', 'MEDIUM',
       'Мелколистный почвопокровный «коврик»; любит влажность и не выносит пересушки.',
       'солейролия, гелксина, soleirolia, soleirolii', 41,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Солейролия (гелксина)');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Аспарагус перистый', 'Asparagus setaceus', 5, 3, 30, 'PARTIAL', 'EASY',
       'Ажурная «зелень» из тонких веточек; любит влажность и рассеянный свет.',
       'аспарагус, перистый, asparagus, setaceus', 48,
       TRUE, TRUE, NULL
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Аспарагус перистый');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Розмарин', 'Salvia rosmarinus', 7, NULL, 30, 'DIRECT', 'MEDIUM',
       'Ароматная пряность; любит много солнца и просушку между поливами.',
       'розмарин, rosmarinus, salvia, пряность', 48,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Розмарин');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Базилик', 'Ocimum basilicum', 2, NULL, 21, 'DIRECT', 'EASY',
       'Ароматная пряная зелень; любит солнце и равномерно влажную почву.',
       'базилик, ocimum, basilicum, пряность', 46,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Базилик');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Венерина мухоловка', 'Dionaea muscipula', 3, NULL, NULL, 'BRIGHT', 'HARD',
       'Хищница с ловушками-«челюстями»; только дождевая/дистиллированная вода, без удобрений.',
       'венерина мухоловка, dionaea, muscipula, хищное', 50,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Венерина мухоловка');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Непентес (кувшиночник)', 'Nepenthes', 4, 3, NULL, 'PARTIAL', 'HARD',
       'Хищник с ловчими кувшинами; нужна высокая влажность и мягкая вода.',
       'непентес, кувшиночник, nepenthes, хищное', 44,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Непентес (кувшиночник)');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Гинура', 'Gynura aurantiaca', 5, NULL, 30, 'BRIGHT', 'MEDIUM',
       'Бархатистые листья с фиолетовым опушением; яркость зависит от света.',
       'гинура, gynura, aurantiaca', 40,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Гинура');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Дуранта', 'Duranta erecta', 5, 3, 21, 'BRIGHT', 'MEDIUM',
       'Кустарник с голубыми цветками и оранжевыми ягодами; ягоды и листья ядовиты.',
       'дуранта, duranta, erecta', 40,
       TRUE, TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Дуранта');

INSERT INTO species (name, latin_name, watering_days, misting_days, fertilizing_days,
                     light_preference, care_difficulty, description, search_tags, popularity,
                     toxic_to_cats, toxic_to_dogs, toxic_to_humans)
SELECT 'Муррайя', 'Murraya paniculata', 5, 3, 21, 'BRIGHT', 'MEDIUM',
       'Ароматные белые цветки и красные ягоды; любит свет, тепло и влажность.',
       'муррайя, murraya, paniculata', 42,
       FALSE, FALSE, FALSE
WHERE NOT EXISTS (SELECT 1 FROM species s WHERE s.name = 'Муррайя');

COMMIT;
