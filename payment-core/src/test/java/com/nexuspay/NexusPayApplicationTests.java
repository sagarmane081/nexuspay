package com.nexuspay;

import com.nexuspay.support.PostgresTestcontainer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Smoke test: the Spring context must start, and Flyway must apply every
 * migration, against a real PostgreSQL (Testcontainers, not H2 — see CLAUDE.md
 * testing expectations).
 */
@SpringBootTest
@Import(PostgresTestcontainer.class)
class NexusPayApplicationTests {

    @Test
    void contextLoads() {
    }
}
