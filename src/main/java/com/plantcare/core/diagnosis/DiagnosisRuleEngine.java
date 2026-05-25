package com.plantcare.core.diagnosis;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class DiagnosisRuleEngine {

    public DiagnosisResult diagnose(DiagnosisAnswers answers) {
        Set<String> causes = new LinkedHashSet<>();
        Set<String> actions = new LinkedHashSet<>();
        Set<String> checks = new LinkedHashSet<>();

        addBaseDisclaimerActions(actions);

        if (answers == null || answers.symptom() == null) {
            addFallback(causes, actions, checks);
            return result(causes, actions, checks);
        }

        applySymptomRules(answers, causes, actions, checks);
        applyWateringAndSoilRules(answers, causes, actions, checks);
        applyLightRules(answers, causes, actions, checks);
        applyRecentChangesRules(answers, causes, actions, checks);
        applyPestRules(answers, causes, actions, checks);

        if (causes.isEmpty()) {
            addFallback(causes, actions, checks);
        }

        return result(causes, actions, checks);
    }

    private void applySymptomRules(
            DiagnosisAnswers answers,
            Set<String> causes,
            Set<String> actions,
            Set<String> checks
    ) {
        switch (answers.symptom()) {
            case YELLOW_LEAVES -> {
                causes.add("Частые причины желтеющих листьев — перелив, нехватка света или стресс после смены условий.");
                actions.add("Убери пожелтевшие сильно повреждённые листья, если они уже не восстанавливаются.");
                checks.add("Проверь, не стоит ли горшок в воде после полива.");
            }
            case WILTING_LEAVES -> {
                causes.add("Вялость часто связана с пересушкой, переливом или резкой сменой условий.");
                actions.add("Проверь грунт пальцем на глубине 2–3 см перед следующим поливом.");
                checks.add("Посмотри, не перегревается ли растение рядом с батареей или на прямом солнце.");
            }
            case BROWN_DRY_TIPS -> {
                causes.add("Сухие коричневые кончики часто появляются из-за пересушки, сухого воздуха или избытка солей в грунте.");
                actions.add("Срежь только полностью сухие кончики чистыми ножницами, не задевая здоровую ткань.");
                checks.add("Проверь, не стоит ли растение рядом с батареей, кондиционером или сквозняком.");
            }
            case LEAF_SPOTS -> {
                causes.add("Пятна могут появляться из-за переувлажнения, солнечных ожогов, грибковых проблем или механических повреждений.");
                actions.add("Изолируй растение от других, если пятна быстро распространяются.");
                checks.add("Проверь нижнюю сторону листьев и черешки.");
            }
            case PESTS_OR_WEB -> {
                causes.add("Возможны вредители: паутинный клещ, тля, трипсы, щитовка или мучнистый червец.");
                actions.add("Изолируй растение от остальных до осмотра и обработки.");
                checks.add("Осмотри нижнюю сторону листьев, молодые побеги и места крепления черешков.");
            }
            case OTHER -> {
                causes.add("Симптом нетиповой, поэтому точную причину по этому мастеру определить сложно.");
                actions.add("Выбери ближайший симптом из списка или запиши наблюдение в заметки растения.");
                checks.add("Сделай фото растения и отдельно фото проблемного листа для истории.");
            }
        }
    }

    private void applyWateringAndSoilRules(
            DiagnosisAnswers answers,
            Set<String> causes,
            Set<String> actions,
            Set<String> checks
    ) {
        WateringFrequency watering = answers.wateringFrequency();
        SoilState soil = answers.soilState();

        if (soil == SoilState.WET && watering == WateringFrequency.MORE_THAN_USUAL) {
            causes.add("Похоже на риск перелива: грунт влажный, а полив стал чаще обычного.");
            actions.add("Поставь полив на паузу до подсыхания верхнего слоя грунта.");
            actions.add("Убери воду из поддона, если она там есть.");
            checks.add("Проверь дренажные отверстия и плотность грунта.");
            return;
        }

        if (soil == SoilState.WET) {
            causes.add("Возможен избыток влаги или слабое просыхание грунта.");
            actions.add("Не поливай, пока верхние 2–3 см грунта не подсохнут.");
            checks.add("Проверь, не слишком ли большой горшок относительно корней.");
        }

        if (soil == SoilState.DRY && watering == WateringFrequency.LESS_THAN_USUAL) {
            causes.add("Похоже на пересушку: грунт сухой, а полив стал реже.");
            actions.add("Полей растение умеренно и дай лишней воде стечь.");
            actions.add("Вернись к регулярной проверке грунта каждые несколько дней.");
            checks.add("Проверь, не отходит ли сухой грунт от стенок горшка.");
            return;
        }

        if (soil == SoilState.DRY) {
            causes.add("Возможна пересушка или слишком быстрое испарение влаги.");
            actions.add("Проверь влажность грунта глубже, не только сверху.");
            checks.add("Посмотри, не стоит ли растение на жарком солнце или рядом с отоплением.");
        }

        if (soil == SoilState.NORMAL && watering == WateringFrequency.AS_USUAL) {
            checks.add("Если полив и грунт выглядят нормальными, обрати внимание на свет, вредителей и недавние изменения.");
        }

        if (soil == SoilState.NOT_SURE || watering == WateringFrequency.NOT_SURE) {
            checks.add("Проверь грунт пальцем или деревянной палочкой на глубине 2–3 см.");
        }
    }

    private void applyLightRules(
            DiagnosisAnswers answers,
            Set<String> causes,
            Set<String> actions,
            Set<String> checks
    ) {
        LightCondition light = answers.lightCondition();

        if (light == LightCondition.DIRECT_SUN) {
            if (answers.symptom() == DiagnosisSymptom.LEAF_SPOTS
                    || answers.symptom() == DiagnosisSymptom.BROWN_DRY_TIPS
                    || answers.symptom() == DiagnosisSymptom.WILTING_LEAVES) {
                causes.add("Возможен солнечный ожог или перегрев на прямом солнце.");
                actions.add("Переставь растение в яркий рассеянный свет на 7–10 дней и наблюдай.");
                checks.add("Сравни новые пятна: если они появляются только после солнца, вероятен ожог.");
            }
        }

        if (light == LightCondition.LOW_LIGHT) {
            if (answers.symptom() == DiagnosisSymptom.YELLOW_LEAVES
                    || answers.symptom() == DiagnosisSymptom.WILTING_LEAVES) {
                causes.add("Недостаток света может ослаблять растение и провоцировать пожелтение.");
                actions.add("Переставь растение ближе к окну, но без резкого прямого солнца.");
                checks.add("Проверь, не вытягиваются ли новые побеги.");
            }
        }

        if (light == LightCondition.NOT_SURE) {
            checks.add("Оцени свет: растению обычно нужен стабильный яркий рассеянный свет без резких перепадов.");
        }
    }

    private void applyRecentChangesRules(
            DiagnosisAnswers answers,
            Set<String> causes,
            Set<String> actions,
            Set<String> checks
    ) {
        RecentChanges changes = answers.recentChanges();

        if (changes == RecentChanges.MOVED_OR_REPOTTED) {
            causes.add("После перестановки или пересадки возможен адаптационный стресс.");
            actions.add("Не меняй сразу несколько условий одновременно: свет, полив и место лучше корректировать постепенно.");
            checks.add("Наблюдай за новыми листьями: старые повреждения могут не восстановиться.");
        }

        if (changes == RecentChanges.NEW_PLANT) {
            causes.add("Новое растение может реагировать на переезд, смену влажности, света и режима полива.");
            actions.add("Поставь растение отдельно от коллекции на 7–14 дней для наблюдения.");
            checks.add("Осмотри растение на вредителей перед тем, как ставить рядом с другими.");
        }
    }

    private void applyPestRules(
            DiagnosisAnswers answers,
            Set<String> causes,
            Set<String> actions,
            Set<String> checks
    ) {
        PestPresence pests = answers.pestPresence();

        if (pests == PestPresence.YES || answers.symptom() == DiagnosisSymptom.PESTS_OR_WEB) {
            causes.add("Есть риск заражения вредителями.");
            actions.add("Изолируй растение от остальных.");
            actions.add("Аккуратно промой листья тёплой водой, если растению это подходит.");
            actions.add("При сильном заражении используй только безопасные средства по инструкции производителя.");
            checks.add("Повтори осмотр через 3–5 дней: вредители часто возвращаются из яиц/личинок.");
        }

        if (pests == PestPresence.NOT_SURE) {
            checks.add("Осмотри растение при хорошем свете: нижнюю сторону листьев, пазухи, молодые побеги и поверхность грунта.");
        }
    }

    private void addBaseDisclaimerActions(Set<String> actions) {
        actions.add("Не делай резких изменений сразу: сначала проверь грунт, свет и вредителей.");
    }

    private void addFallback(
            Set<String> causes,
            Set<String> actions,
            Set<String> checks
    ) {
        causes.add("По ответам нельзя уверенно выбрать одну причину. Возможны ошибки ухода, стресс или вредители.");
        actions.add("Проверь грунт, освещение и листья с двух сторон.");
        actions.add("Наблюдай 3–7 дней и фиксируй изменения в заметках.");
        checks.add("Если состояние быстро ухудшается, лучше обратиться к специалисту или в профильное сообщество с фото.");
    }

    private DiagnosisResult result(
            Set<String> causes,
            Set<String> actions,
            Set<String> checks
    ) {
        return new DiagnosisResult(
                new ArrayList<>(causes),
                new ArrayList<>(actions),
                new ArrayList<>(checks)
        );
    }
}