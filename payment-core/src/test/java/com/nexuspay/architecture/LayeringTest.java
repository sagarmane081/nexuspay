package com.nexuspay.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1.2 "done when": a domain class has zero Spring web imports.
 * <p>
 * This reads the source files rather than the compiled classpath, deliberately.
 * An import that is present but unused still signals that someone reached for
 * the wrong abstraction, and the failure message can name the exact file and
 * line — which is what makes an architecture test useful rather than annoying.
 * <p>
 * If the rules here grow much beyond this, ArchUnit is the tool to reach for.
 * It is not a dependency yet because one rule does not justify one.
 */
class LayeringTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java/com/nexuspay");

    /**
     * Packages holding business logic. Nothing in here may know that HTTP,
     * servlets or persistence frameworks exist — that is what lets the rules be
     * unit-tested in milliseconds without a Spring context or a database.
     */
    private static final List<String> DOMAIN_PACKAGES = List.of(
            "common/money",
            "common/time",
            "common/id",
            "common/error");

    private static final List<String> FORBIDDEN_IMPORTS = List.of(
            "org.springframework.web",
            "org.springframework.http",
            "org.springframework.stereotype",
            "org.springframework.boot",
            "jakarta.servlet",
            "jakarta.persistence",
            "org.hibernate");

    @Test
    @DisplayName("domain packages import nothing from Spring web, servlets or JPA")
    void domainIsFreeOfFrameworkImports() throws IOException {
        List<String> violations = new ArrayList<>();

        for (String pkg : DOMAIN_PACKAGES) {
            Path dir = SOURCE_ROOT.resolve(pkg);
            assertThat(dir)
                    .as("domain package %s should exist — update this test if it moved", pkg)
                    .isDirectory();

            try (Stream<Path> files = Files.walk(dir)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    List<String> lines = Files.readAllLines(file);
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i).strip();
                        if (!line.startsWith("import ")) {
                            continue;
                        }
                        for (String forbidden : FORBIDDEN_IMPORTS) {
                            if (line.startsWith("import " + forbidden)) {
                                violations.add("%s:%d  %s".formatted(file, i + 1, line));
                            }
                        }
                    }
                }
            }
        }

        assertThat(violations)
                .as("domain classes must not depend on the framework; move the offending code to an api/ or config/ package")
                .isEmpty();
    }

    @Test
    @DisplayName("the test itself is wired to real files, not silently passing on an empty scan")
    void scanActuallyFindsSources() throws IOException {
        long javaFiles = 0;
        for (String pkg : DOMAIN_PACKAGES) {
            try (Stream<Path> files = Files.walk(SOURCE_ROOT.resolve(pkg))) {
                javaFiles += files.filter(p -> p.toString().endsWith(".java")).count();
            }
        }

        // Without this, deleting or moving the domain packages would make the
        // rule above pass vacuously — the most common way an architecture test
        // rots into decoration.
        assertThat(javaFiles)
                .as("expected to scan real domain sources")
                .isGreaterThanOrEqualTo(6);
    }
}
