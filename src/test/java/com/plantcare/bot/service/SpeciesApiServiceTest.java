package com.plantcare.bot.service;

import com.plantcare.bot.config.SpeciesProperties;
import com.plantcare.bot.domain.Species;
import com.plantcare.bot.repository.SpeciesRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpeciesApiServiceTest {

    @Mock
    private SpeciesRepository speciesRepository;

    @Mock
    private SpeciesProperties speciesProperties;

    @InjectMocks
    private SpeciesService speciesService;

    @Test
    void should_return_page_when_query_blank() {
        // arrange
        Pageable pageable = PageRequest.of(0, 20);
        var monstera = new Species();
        monstera.setName("Монстера");
        Page<Species> expected = new PageImpl<>(List.of(monstera));
        when(speciesRepository.findAll(pageable)).thenReturn(expected);

        // act
        Page<Species> result = speciesService.findPage("", pageable);

        // assert
        assertThat(result).isSameAs(expected);
        verify(speciesRepository).findAll(pageable);
    }

    @Test
    void should_return_page_when_query_is_null() {
        // arrange
        Pageable pageable = PageRequest.of(0, 20);
        Page<Species> expected = new PageImpl<>(List.of());
        when(speciesRepository.findAll(pageable)).thenReturn(expected);

        // act
        Page<Species> result = speciesService.findPage(null, pageable);

        // assert
        verify(speciesRepository).findAll(pageable);
        assertThat(result).isSameAs(expected);
    }

    @Test
    void should_search_when_query_provided() {
        // arrange
        Pageable pageable = PageRequest.of(0, 20);
        var roza = new Species();
        roza.setName("Роза чайная");
        Page<Species> expected = new PageImpl<>(List.of(roza));
        when(speciesRepository.findBySearch(eq("роза"), any(Pageable.class))).thenReturn(expected);

        // act
        Page<Species> result = speciesService.findPage("роза", pageable);

        // assert
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getName()).isEqualTo("Роза чайная");
        verify(speciesRepository).findBySearch(eq("роза"), eq(pageable));
    }

    @Test
    void should_throw_when_species_not_found() {
        // arrange
        when(speciesRepository.findById(999L)).thenReturn(Optional.empty());

        // act + assert
        assertThatThrownBy(() -> speciesService.getById(999L))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("Species not found");
    }

    @Test
    void should_return_species_when_found_by_id() {
        // arrange
        var monstera = new Species();
        monstera.setName("Монстера");
        monstera.setWateringDays(7);
        when(speciesRepository.findById(1L)).thenReturn(Optional.of(monstera));

        // act
        Species result = speciesService.getById(1L);

        // assert
        assertThat(result.getName()).isEqualTo("Монстера");
        assertThat(result.getWateringDays()).isEqualTo(7);
    }
}
