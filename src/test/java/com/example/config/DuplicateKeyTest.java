package com.example.config;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("R8: duplicate keys within a YAML file")
class DuplicateKeyTest {

    private final List<String> yamlFiles = new ArrayList<>();
    private final Map<String, Set<String>> importsCache = new LinkedHashMap<>();

    @BeforeAll
    void discover() throws IOException {
        yamlFiles.add(YamlFixture.BASE_FILE);
        PathMatchingResourcePatternResolver scanner = new PathMatchingResourcePatternResolver();
        for (Resource r : scanner.getResources(YamlFixture.PROFILE_GLOB)) {
            String fn = r.getFilename();
            if (fn != null && !fn.equals(YamlFixture.BASE_FILE)
                    && (fn.endsWith(".yml") || fn.endsWith(".yaml"))) {
                yamlFiles.add(fn);
            }
        }
    }

    @ParameterizedTest(name = "[{0}] no duplicate keys")
    @MethodSource("yamlFiles")
    @DisplayName("R8: duplicate mapping keys must not exist within a file")
    void r8_noDuplicateKeys(String filename) {
        assertDoesNotThrow(() -> parseStrict(new ClassPathResource(filename)),
                () -> String.format(
                        "[R8] Duplicate mapping key(s) found in '%s'. YAML keeps " +
                        "the last value, silently discarding earlier ones. " +
                        "Remove the duplicate.", filename));
    }

    @ParameterizedTest(name = "[{0}] imported files clean")
    @MethodSource("yamlFiles")
    @DisplayName("R8b: spring.config.import targets are also checked")
    void r8b_importedFilesClean(String filename) {
        for (String imported : extractImports(filename)) {
            ClassPathResource r = new ClassPathResource(imported);
            if (!r.exists()) continue;
            assertDoesNotThrow(() -> parseStrict(r),
                    () -> String.format(
                            "[R8b] Duplicate keys in imported file '%s' (from '%s').",
                            imported, filename));
        }
    }

    Stream<String> yamlFiles() { return yamlFiles.stream(); }

    private void parseStrict(Resource resource) throws IOException {
        LoaderOptions opts = new LoaderOptions();
        opts.setAllowDuplicateKeys(false);
        opts.setMaxAliasesForCollections(100);
        Yaml yaml = new Yaml(new SafeConstructor(opts));
        try (InputStream in = resource.getInputStream()) {
            for (Object ignored : yaml.loadAll(in)) { /* force parse */ }
        }
    }

    @SuppressWarnings("unchecked")
    private Set<String> extractImports(String filename) {
        return importsCache.computeIfAbsent(filename, f -> {
            Set<String> result = new LinkedHashSet<>();
            try {
                YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
                for (PropertySource<?> src : loader.load(f, new ClassPathResource(f))) {
                    if (!(src.getSource() instanceof Map<?, ?> map)) continue;
                    Object s = map.get("spring");
                    if (!(s instanceof Map<?, ?> sm)) continue;
                    Object c = sm.get("config");
                    if (!(c instanceof Map<?, ?> cm)) continue;
                    Object imp = cm.get("import");
                    if (imp == null) continue;
                    if (imp instanceof List<?> list) {
                        for (Object i : list) add(result, String.valueOf(i));
                    } else if (imp instanceof String str) {
                        for (String p : str.split(",")) add(result, p.trim());
                    }
                }
            } catch (Exception ignored) {}
            return result;
        });
    }

    private void add(Set<String> target, String ref) {
        if (ref == null || ref.isBlank()) return;
        String cleaned = ref.startsWith("optional:") ? ref.substring(9) : ref;
        if (cleaned.startsWith("classpath:")) target.add(cleaned.substring(10));
    }
}
