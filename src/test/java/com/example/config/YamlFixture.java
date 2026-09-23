package com.example.config;

import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Central YAML loader. Loads application.yml + every application-*.yml once. */
public final class YamlFixture {

    public static final String BASE_FILE = "application.yml";
    public static final String PROFILE_GLOB = "classpath*:application-*.yml";

    private static Map<String, Object> base;
    private static Map<String, Map<String, Object>> profiles;
    private static StandardEnvironment resolver;

    private YamlFixture() {}

    public static synchronized Map<String, Object> base() {
        ensureLoaded();
        return base;
    }

    public static synchronized Map<String, Map<String, Object>> profiles() {
        ensureLoaded();
        return profiles;
    }

    public static synchronized Stream<String> profileNames() {
        ensureLoaded();
        return profiles.keySet().stream();
    }

    public static synchronized StandardEnvironment resolver() {
        ensureLoaded();
        return resolver;
    }

    /** Resolve as String; returns null if raw is null. */
    public static String resolve(String raw) {
        if (raw == null) return null;
        return resolver().resolveRequiredPlaceholders(raw);
    }

    /** Convenience: get base value as String. */
    public static String baseString(String key) {
        Object v = base().get(key);
        return v == null ? null : v.toString();
    }

    /** Convenience: get profile value as String. */
    public static String profileString(String profile, String key) {
        Object v = profiles().get(profile).get(key);
        return v == null ? null : v.toString();
    }

    private static void ensureLoaded() {
        if (base != null) return;

        try {
            base = load(new ClassPathResource(BASE_FILE));

            resolver = new StandardEnvironment();
            resolver.getPropertySources().addLast(new MapPropertySource("baseYaml", base));

            profiles = new LinkedHashMap<>();
            PathMatchingResourcePatternResolver scanner = new PathMatchingResourcePatternResolver();
            for (Resource r : scanner.getResources(PROFILE_GLOB)) {
                String fn = r.getFilename();
                if (fn == null || fn.equals(BASE_FILE)) continue;
                if (!fn.startsWith("application-")) continue;
                if (!(fn.endsWith(".yml") || fn.endsWith(".yaml"))) continue;
                String profile = fn.substring(
                        "application-".length(),
                        fn.lastIndexOf('.'));
                profiles.put(profile, load(r));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load YAML fixtures", e);
        }
    }

    public static Map<String, Object> load(Resource resource) throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        Map<String, Object> out = new LinkedHashMap<>();
        for (PropertySource<?> src : loader.load(resource.getFilename(), resource)) {
            if (src.getSource() instanceof Map<?, ?> map) {
                flatten("", cast(map), out);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) { return (Map<String, Object>) m; }

    private static void flatten(String prefix, Map<String, Object> map, Map<String, Object> out) {
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String key = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            Object v = e.getValue();
            if (v instanceof Map<?, ?> nested) {
                flatten(key, cast(nested), out);
            } else if (v instanceof List<?> list) {
                for (int i = 0; i < list.size(); i++) {
                    Object item = list.get(i);
                    String ik = key + "[" + i + "]";
                    if (item instanceof Map<?, ?> nestedItem) {
                        flatten(ik, cast(nestedItem), out);
                    } else if (item != null) {
                        out.put(ik, item.toString());
                    }
                }
            } else if (v != null) {
                out.put(key, v.toString());
            }
        }
    }
}