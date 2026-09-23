package com.example.config;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("R6: no dead properties in application.yml")
class Rule6DeadPropertiesTest {

    private static final Set<String> FRAMEWORK_PREFIXES = Set.of(
            "spring.", "server.", "management.", "logging.", "info.",
            "cloud.", "eureka.", "feign.", "ribbon.",
            "hystrix.", "zuul.", "resilience4j.",
            "micrometer.", "springdoc.", "openapi.",
            "datasource.", "jpa.", "hibernate.",
            "kafka.", "rabbitmq.", "redis.",
            "security.", "oauth2.", "jwt."
    );

    private static final Set<String> FRAMEWORK_KEYS = Set.of("debug", "trace");

    private static final String ALLOWLIST = "dead-property-allowlist.txt";

    Map<String, Object> base;
    private Set<String> profileKeys;
    private Set<String> sourceRefs;
    private Set<String> allowlist;

    @BeforeAll
    void load() throws IOException {
        base = YamlFixture.base();
        profileKeys = new HashSet<>();
        YamlFixture.profiles().values().forEach(m -> profileKeys.addAll(m.keySet()));
        sourceRefs = new SourceScanner().scan();
        allowlist = loadAllowlist();
    }

    @Test
    @DisplayName("every key in application.yml is used or overridden")
    void r6_noDeadProperties() {
        List<String> dead = base.keySet().stream()
                .filter(k -> !isFramework(k))
                .filter(k -> !allowlist.contains(k))
                .filter(k -> !profileKeys.contains(k))
                .filter(k -> !isReferenced(k))
                .sorted()
                .toList();

        if (dead.isEmpty()) return;

        StringBuilder sb = new StringBuilder();
        sb.append("[R6] ").append(dead.size())
          .append(dead.size() == 1 ? " dead property" : " dead properties")
          .append(" found in application.yml — not overridden by any profile ")
          .append("and not referenced in source code:\n\n");
        for (String k : dead) {
            sb.append("  • ").append(k).append(" = ").append(base.get(k)).append("\n");
        }
        sb.append("\nFix:\n");
        sb.append("  1. Reference it via @Value, @ConfigurationProperties, ")
          .append("or environment.getProperty(...)\n");
        sb.append("  2. Move it to application-{profile}.yml if it is env-specific\n");
        sb.append("  3. Delete it if unused\n");
        sb.append("  4. Add to src/test/resources/").append(ALLOWLIST)
          .append(" if false positive\n");
        fail(sb.toString());
    }

    private boolean isFramework(String key) {
        if (FRAMEWORK_KEYS.contains(key)) return true;
        return FRAMEWORK_PREFIXES.stream().anyMatch(key::startsWith);
    }

    private boolean isReferenced(String baseKey) {
        String nb = normalize(baseKey);
        for (String ref : sourceRefs) {
            String nr = normalize(ref);
            if (nb.equals(nr)) return true;
            if (nb.startsWith(nr + ".")) return true; // @ConfigurationProperties prefix
        }
        return false;
    }

    private String normalize(String key) {
        String[] parts = key.split("\\.");
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].toLowerCase().replace("-", "").replace("_", "");
        }
        return String.join(".", parts);
    }

    private Set<String> loadAllowlist() {
        Set<String> out = new HashSet<>();
        var r = new org.springframework.core.io.ClassPathResource(ALLOWLIST);
        if (!r.exists()) return out;
        try {
            for (String line : new String(r.getInputStream().readAllBytes()).lines().toList()) {
                String t = line.strip();
                if (!t.isEmpty() && !t.startsWith("#")) out.add(t);
            }
        } catch (IOException e) { throw new UncheckedIOException(e); }
        return out;
    }

    static final class SourceScanner {
        private static final Pattern P_VALUE = Pattern.compile(
                "@Value\\s*\\(\\s*\"\\$\\{([^}:]+)(?::[^}]*)?}\"");
        private static final Pattern P_CP = Pattern.compile(
                "@ConfigurationProperties\\s*\\(\\s*(?:(?:prefix|value)\\s*=\\s*)?\"([^\"]+)\"");
        private static final Pattern P_GET = Pattern.compile(
                "(?:getProperty|getRequiredProperty)\\s*\\(\\s*\"([^\"]+)\"");
        private static final Pattern P_COND = Pattern.compile(
                "@ConditionalOnProperty\\s*\\([^)]*?(?:name|value)\\s*=\\s*\"([^\"]+)\"");

        Set<String> scan() throws IOException {
            Path root = locate();
            if (root == null) return Set.of();
            Set<String> refs = new HashSet<>();
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path f : walk.filter(Files::isRegularFile)
                        .filter(p -> {
                            String s = p.toString();
                            return s.endsWith(".java") || s.endsWith(".kt");
                        }).toList()) {
                    String c = Files.readString(f);
                    extract(refs, P_VALUE, c);
                    extract(refs, P_CP, c);
                    extract(refs, P_GET, c);
                    extract(refs, P_COND, c);
                }
            }
            return refs;
        }

        private void extract(Set<String> refs, Pattern p, String s) {
            Matcher m = p.matcher(s);
            while (m.find()) refs.add(m.group(1).trim());
        }

        private Path locate() {
            Path cur = Paths.get(System.getProperty("user.dir"));
            for (int i = 0; i < 5 && cur != null; i++) {
                Path src = cur.resolve("src/main/java");
                if (Files.isDirectory(src)) return src;
                cur = cur.getParent();
            }
            return null;
        }
    }
}
