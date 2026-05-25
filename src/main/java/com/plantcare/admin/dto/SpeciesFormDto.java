package com.plantcare.admin.dto;

import com.plantcare.core.domain.enums.CareDifficulty;
import com.plantcare.core.domain.enums.LightPreference;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SpeciesFormDto {

    @NotBlank(message = "Название обязательно")
    @Size(max = 100, message = "Название не должно превышать 100 символов")
    private String name;

    @Size(max = 150, message = "Латинское название не должно превышать 150 символов")
    private String latinName;

    @Min(value = 1, message = "Должно быть от 1 до 365")
    @Max(value = 365, message = "Должно быть от 1 до 365")
    private Integer wateringDays;

    @Min(value = 1, message = "Должно быть от 1 до 365")
    @Max(value = 365, message = "Должно быть от 1 до 365")
    private Integer mistingDays;

    @Min(value = 1, message = "Должно быть от 1 до 365")
    @Max(value = 365, message = "Должно быть от 1 до 365")
    private Integer fertilizingDays;

    private LightPreference lightPreference;

    private CareDifficulty careDifficulty;

    private String description;

    private String searchTags;

    @Min(value = 0, message = "Популярность от 0 до 100")
    @Max(value = 100, message = "Популярность от 0 до 100")
    private Integer popularity = 50;

    /**
     * Токсичность для кошек. Tri-state: {@code true} — токсично,
     * {@code false} — безопасно, {@code null} — нет данных. Все три состояния
     * валидны. Пустое значение из {@code <select>} биндится в {@code null}
     * штатным конвертером Spring (пустая строка → {@code null} для Boolean).
     */
    private Boolean toxicToCats;

    /** Токсичность для собак. Tri-state, см. {@link #toxicToCats}. */
    private Boolean toxicToDogs;

    /** Токсичность для людей/детей. Tri-state, см. {@link #toxicToCats}. */
    private Boolean toxicToHumans;
}
