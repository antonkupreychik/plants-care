package com.plantcare.api.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Конфигурация OpenAPI/Swagger UI для REST API Plants Care.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI plantsCareOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("Plants Care API")
                .version("v1")
                .description("REST API для мобильного клиента Plants Care"));
    }
}
