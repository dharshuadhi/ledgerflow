package com.ledgerflow.api;

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
    public OpenAPI ledgerFlowApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("LedgerFlow API")
                        .version("v1")
                        .description("""
                                Auditable event-driven payment ledger.

                                Money moves as double-entry postings; every transfer is idempotent
                                (Idempotency-Key header); state changes propagate via a transactional
                                outbox to Kafka. Amounts are minor units; currencies are ISO 4217.
                                """))
                .addSecurityItem(new SecurityRequirement().addList("bearer"))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
