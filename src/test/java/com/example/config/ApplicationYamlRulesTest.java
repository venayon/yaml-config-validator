package com.example.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("application.yml vs application-{profile}.yml validation")
class ApplicationYamlRulesTest {

    static Stream<String> profileNames() { return YamlFixture.profileNames(); }

    // R1 ------------------------------------------------------------------
    @ParameterizedTest(name = "[{0}] every key exists in application.yml")
    @MethodSource("profileNames")
    @DisplayName("R1: profile keys must be declared in application.yml")
    void r1_profileKeysMustExistInBase(String profile) {
        Map<String, Object> props = YamlFixture.profiles().get(profile);
        Map<String, Object> base  = YamlFixture.base();

        List<Executable> checks = props.keySet().stream()
                .map(k -> (Executable) () -> assertTrue(base.containsKey(k),
                        () -> String.format(
                                "[R1] Property '%s' is defined in application-%s.yml " +
                                        "but NOT in application.yml.", k, profile)))
                .toList();


        assertAll("R1 for profile '" + profile + "'", checks);
    }

    // R2 ------------------------------------------------------------------
    @ParameterizedTest(name = "[{0}] no same-value redeclaration")
    @MethodSource("profileNames")
    @DisplayName("R2: same value must only live in application.yml")
    void r2_sameValueMustNotBeInProfile(String profile) {
        Map<String, Object> props = YamlFixture.profiles().get(profile);
        Map<String, Object> base  = YamlFixture.base();

        List<Executable> checks = props.entrySet().stream()
                .filter(e -> base.containsKey(e.getKey()))
                .map(e -> (Executable) () -> {
                    String key = e.getKey();
                    String pv = YamlFixture.resolve(String.valueOf(e.getValue()));
                    String bv = YamlFixture.resolve(String.valueOf(base.get(key)));
                    assertNotEquals(bv, pv, () -> String.format(
                            "[R2] Property '%s' has the SAME resolved value '%s' " +
                                    "in both application.yml and application-%s.yml.",
                            key, pv, profile));
                })
                .toList();

        assertAll("R2 for profile '" + profile + "'", checks);
    }

    // R5 ------------------------------------------------------------------
    @ParameterizedTest(name = "[{0}] every key is a real override")
    @MethodSource("profileNames")
    @DisplayName("R5: profile files contain only genuine overrides")
    void r5_profileFilesContainOnlyOverrides(String profile) {
        Map<String, Object> props = YamlFixture.profiles().get(profile);
        Map<String, Object> base  = YamlFixture.base();

        List<Executable> checks = new ArrayList<>();
        for (Map.Entry<String, Object> e : props.entrySet()) {
            String key = e.getKey();
            String pv = YamlFixture.resolve(String.valueOf(e.getValue()));
            checks.add(() -> assertTrue(base.containsKey(key),
                    () -> String.format(
                            "[R5] '%s' = '%s' exists in application-%s.yml " +
                                    "but not in application.yml (extra key).",
                            key, pv, profile)));
            if (base.containsKey(key)) {
                String bv = YamlFixture.resolve(String.valueOf(base.get(key)));
                checks.add(() -> assertNotEquals(bv, pv,
                        () -> String.format(
                                "[R5] '%s' = '%s' in application-%s.yml is identical " +
                                        "to application.yml — not a genuine override.",
                                key, pv, profile)));
            }
        }
        assertAll("R5 for profile '" + profile + "'", checks);
    }
}
