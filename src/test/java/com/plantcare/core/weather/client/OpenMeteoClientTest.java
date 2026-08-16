package com.plantcare.core.weather.client;

import com.plantcare.core.weather.dto.OpenMeteoResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestToUriTemplate;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit-тесты для {@link OpenMeteoClient}. HTTP-слой подменяем {@link MockRestServiceServer},
 * подключённым к реальному {@link RestClient} — это внешнее API (Open-Meteo), мокать
 * разрешено по правилам CLAUDE.md.
 */
@DisplayName("Unit-тесты для OpenMeteoClient")
class OpenMeteoClientTest {

    private static final String BASE_URL = "http://open-meteo.test";

    private MockRestServiceServer server;
    private OpenMeteoClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        client = new OpenMeteoClient(restClient);
    }

    @Test
    @DisplayName("Успешный ответ с валидным телом возвращает заполненный Optional")
    void shouldReturnResponseWhenApiRespondsWithValidBody() {
        double lat = 53.9006;
        double lon = 27.5590;

        server.expect(requestToUriTemplate(BASE_URL + "/forecast?latitude={lat}&longitude={lon}"
                        + "&hourly={hourly}&timezone={tz}",
                        lat, lon, "relative_humidity_2m", "auto"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(queryParam("latitude", String.valueOf(lat)))
                .andExpect(queryParam("longitude", String.valueOf(lon)))
                .andRespond(withSuccess("""
                        {
                          "timezone": "Europe/Minsk",
                          "hourly": {
                            "time": ["2026-08-16T00:00", "2026-08-16T01:00"],
                            "relative_humidity_2m": [55, 60]
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        Optional<OpenMeteoResponse> result = client.fetchHourlyHumidity(lat, lon);

        assertThat(result).isPresent();
        assertThat(result.get().timezone()).isEqualTo("Europe/Minsk");
        assertThat(result.get().hourly().time()).containsExactly("2026-08-16T00:00", "2026-08-16T01:00");
        assertThat(result.get().hourly().relativeHumidity2m()).containsExactly(55, 60);

        server.verify();
    }

    @Test
    @DisplayName("Non-200 ответ (500) приводит к Optional.empty(), исключение не прокидывается")
    void shouldReturnEmptyWhenApiRespondsWithServerError() {
        server.expect(requestToUriTemplate(BASE_URL + "/forecast?latitude={lat}&longitude={lon}"
                        + "&hourly={hourly}&timezone={tz}",
                        10.0, 20.0, "relative_humidity_2m", "auto"))
                .andRespond(withServerError());

        Optional<OpenMeteoResponse> result = client.fetchHourlyHumidity(10.0, 20.0);

        assertThat(result).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("Таймаут/сетевой сбой (IOException) приводит к Optional.empty()")
    void shouldReturnEmptyWhenRequestTimesOut() {
        server.expect(requestToUriTemplate(BASE_URL + "/forecast?latitude={lat}&longitude={lon}"
                        + "&hourly={hourly}&timezone={tz}",
                        11.0, 22.0, "relative_humidity_2m", "auto"))
                .andRespond(request -> {
                    throw new IOException("simulated read timeout");
                });

        Optional<OpenMeteoResponse> result = client.fetchHourlyHumidity(11.0, 22.0);

        assertThat(result).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("Отсутствие hourly-блока в теле ответа приводит к Optional.empty()")
    void shouldReturnEmptyWhenHourlyBlockMissing() {
        server.expect(requestToUriTemplate(BASE_URL + "/forecast?latitude={lat}&longitude={lon}"
                        + "&hourly={hourly}&timezone={tz}",
                        33.0, 44.0, "relative_humidity_2m", "auto"))
                .andRespond(withSuccess("""
                        {
                          "timezone": "Europe/Minsk"
                        }
                        """, MediaType.APPLICATION_JSON));

        Optional<OpenMeteoResponse> result = client.fetchHourlyHumidity(33.0, 44.0);

        assertThat(result).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("Отсутствие relative_humidity_2m в hourly-блоке приводит к Optional.empty()")
    void shouldReturnEmptyWhenHumidityFieldMissing() {
        server.expect(requestToUriTemplate(BASE_URL + "/forecast?latitude={lat}&longitude={lon}"
                        + "&hourly={hourly}&timezone={tz}",
                        1.0, 2.0, "relative_humidity_2m", "auto"))
                .andRespond(withSuccess("""
                        {
                          "timezone": "Europe/Minsk",
                          "hourly": {
                            "time": ["2026-08-16T00:00"]
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        Optional<OpenMeteoResponse> result = client.fetchHourlyHumidity(1.0, 2.0);

        assertThat(result).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("Явный null в поле time (при наличии humidity) приводит к Optional.empty()")
    void shouldReturnEmptyWhenTimeFieldIsExplicitlyNull() {
        server.expect(requestToUriTemplate(BASE_URL + "/forecast?latitude={lat}&longitude={lon}"
                        + "&hourly={hourly}&timezone={tz}",
                        7.0, 8.0, "relative_humidity_2m", "auto"))
                .andRespond(withSuccess("""
                        {
                          "timezone": "Europe/Minsk",
                          "hourly": {
                            "time": null,
                            "relative_humidity_2m": [42]
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        Optional<OpenMeteoResponse> result = client.fetchHourlyHumidity(7.0, 8.0);

        assertThat(result).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("Пустое тело ответа (null) приводит к Optional.empty()")
    void shouldReturnEmptyWhenBodyIsEmpty() {
        server.expect(requestToUriTemplate(BASE_URL + "/forecast?latitude={lat}&longitude={lon}"
                        + "&hourly={hourly}&timezone={tz}",
                        5.0, 6.0, "relative_humidity_2m", "auto"))
                .andRespond(withSuccess("", null));

        Optional<OpenMeteoResponse> result = client.fetchHourlyHumidity(5.0, 6.0);

        assertThat(result).isEmpty();
    }
}
