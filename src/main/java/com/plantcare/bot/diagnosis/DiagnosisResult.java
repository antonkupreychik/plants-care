package com.plantcare.bot.diagnosis;

import java.util.List;

public record DiagnosisResult(
        List<String> possibleCauses,
        List<String> actionsNow,
        List<String> additionalChecks
) {
}