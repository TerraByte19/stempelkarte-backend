package com.example.stemplekarte.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Stempelkarte API")
                        .version("1.0.0")
                        .description("Multi-Tenant Stempelkarten Backend"))
                .addSecurityItem(new SecurityRequirement()
                        .addList("bearerAuth")
                        .addList("staffToken"))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth",
                                new SecurityScheme()
                                        .name("bearerAuth")
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .description("JWT Token fuer Shop-Verwaltung — aus /api/auth/login"))
                        .addSecuritySchemes("staffToken",
                                new SecurityScheme()
                                        .name("X-Staff-Token")
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.HEADER)
                                        .description("Staff-Token fuer Stempel-Scanner — aus /api/shop/staff-token")));
    }
}