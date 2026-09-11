package com.nexuspay;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Smoke test: the Spring context must start against a real PostgreSQL
 * (Testcontainers, not H2 — see CLAUDE.md testing expectations).
 */
@SpringBootTest
@Testcontainers
class NexusPayApplicationTests {

    @Configuration
    static class TestcontainersConfig {

        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgresContainer() {
            return new PostgreSQLContainer<>("postgres:16-alpine");
        }
    }

    @Test
    void contextLoads() {
    }
}
