# yaml-config-validator

A minimal, runnable Maven project that exercises `YamlConfigStructureTest`
against a deliberately-flawed set of `application*.yml` files, so every rule
(R1–R5) fails at least once when you run it.

## Running it

```bash
mvn test
```

(First run needs internet access to pull Spring Boot / JUnit artifacts from
Maven Central. This sandbox has neither Maven installed nor network access,
so this project has been assembled and reasoned through by hand, not actually
executed here — run it locally to confirm.)

`testFailureIgnore` is set to `true` in `pom.xml` purely so `mvn test` exits
`0` and prints every result in one go for this demo. Set it back to `false`
(or delete the line) to get normal CI-style failing builds.

## The sample files

| File | Deliberate violations |
|---|---|
| `application.yml` | (base — no violations by definition) |
| `application-dev.yml` | R1/R3/R5: `app.newDevOnlyKey` doesn't exist in base. R2/R4/R5: `logging.level.root` duplicates base's `INFO`. Also includes `app.debugFlag: null`, which must be **silently skipped** (not a failure) — the null-skip edge case. |
| `application-test.yml` | R1/R3/R5: `app.testOnlyFlag` doesn't exist in base. R2/R4/R5: `server.port` duplicates base's `8080`. |
| `application-prod.yml` | R1/R3/R5: `app.secretRef` doesn't exist in base. R2/R4/R5: `app.tags[0]` duplicates base's `core` (list-index handling). Also overrides `app.name`, which changes `app.greeting`'s **resolved** value even though its literal YAML text is identical to the base file — demonstrating that R2/R4 compare resolved values, not raw text, and correctly treat this as a real override, not a duplicate. |

## Expected result: 13 failing test executions

`R1`–`R4` are `@ParameterizedTest`s (one execution per discovered profile:
`dev`, `prod`, `test` — 4 rules × 3 profiles = 12 executions), plus `R5` is a
single `@Test`. All 13 fail against this sample data:

```
YamlConfigStructureTest > R1: profile keys must exist in application.yml > [R1] profile=dev  FAILED
    org.opentest4j.AssertionFailedError:
    [R1] key present in profile file is missing from application.yml — key=app.newDevOnlyKey, file=application-dev.yml, value=x
    ==> expected: <true> but was: <false>
    Comment: Profile files may only override values that already exist in the base application.yml; this keeps the full configuration surface discoverable from a single file.

YamlConfigStructureTest > R1: profile keys must exist in application.yml > [R1] profile=prod  FAILED
    [R1] key present in profile file is missing from application.yml — key=app.secretRef, file=application-prod.yml, value=vault://prod/secret

YamlConfigStructureTest > R1: profile keys must exist in application.yml > [R1] profile=test  FAILED
    [R1] key present in profile file is missing from application.yml — key=app.testOnlyFlag, file=application-test.yml, value=true

YamlConfigStructureTest > R2: profile must not duplicate a base value > [R2] profile=dev  FAILED
    [R2] profile value is identical to the base (resolved) value — key=logging.level.root, file=application-dev.yml, value=INFO

YamlConfigStructureTest > R2: profile must not duplicate a base value > [R2] profile=prod  FAILED
    [R2] profile value is identical to the base (resolved) value — key=app.tags[0], file=application-prod.yml, value=core

YamlConfigStructureTest > R2: profile must not duplicate a base value > [R2] profile=test  FAILED
    [R2] profile value is identical to the base (resolved) value — key=server.port, file=application-test.yml, value=8080

YamlConfigStructureTest > R3: profile must not introduce new keys > [R3] profile=dev  FAILED
    [R3] profile file introduces a key absent from application.yml — key=app.newDevOnlyKey, file=application-dev.yml, value=x

YamlConfigStructureTest > R3: profile must not introduce new keys > [R3] profile=prod  FAILED
    [R3] profile file introduces a key absent from application.yml — key=app.secretRef, file=application-prod.yml, value=vault://prod/secret

YamlConfigStructureTest > R3: profile must not introduce new keys > [R3] profile=test  FAILED
    [R3] profile file introduces a key absent from application.yml — key=app.testOnlyFlag, file=application-test.yml, value=true

YamlConfigStructureTest > R4: every override must change the resolved value > [R4] profile=dev  FAILED
    [R4] key is present in both files but does not change the resolved value — key=logging.level.root, file=application-dev.yml, value=INFO

YamlConfigStructureTest > R4: every override must change the resolved value > [R4] profile=prod  FAILED
    [R4] key is present in both files but does not change the resolved value — key=app.tags[0], file=application-prod.yml, value=core

YamlConfigStructureTest > R4: every override must change the resolved value > [R4] profile=test  FAILED
    [R4] key is present in both files but does not change the resolved value — key=server.port, file=application-test.yml, value=8080

YamlConfigStructureTest > R5: profile keys exist if and only if they override base  FAILED
    [R5] key does not belong in a profile file: absent from application.yml — key=app.newDevOnlyKey, file=application-dev.yml, value=x
```

### Why R5 only ever shows one failure per run

R5 is intentionally a **single** `@Test` that walks every profile in one
method body (per the original spec: "R5 is a single @Test that compares all
profiles at once"). Every rule here uses a hard assertion
(`AssertionFailureBuilder...buildAndThrow()`), which throws and aborts the
method on the *first* violation it finds — so R5 will only ever surface one
problem per run (here, `dev`'s `app.newDevOnlyKey`, since profiles are
processed in alphabetical order: `dev` < `prod` < `test`). Fix that one
violation and re-run to see the next one R5 finds.

R1–R4 don't have this limitation *across profiles*, because each profile is
a separate parameterized invocation — a separate JUnit test — so you get one
independent failure report per profile. But within a single profile, if that
profile had two separate keys both missing from the base file, only the
first one (in file order) would show per run of that rule.

If you want every violation surfaced in one pass rather than one-at-a-time,
the fix is to switch from hard assertions to a soft-assertion collector
(e.g. accumulate a `List<String>` of failure messages per method and call
`AssertionFailureBuilder` once at the end with all of them joined) — happy to
rewrite the class that way if that's more useful for your CI output.

## What passes (not shown above, since they don't fail)

- `spring.datasource.url` overrides in all three profiles — different values, no violation.
- `app.timeout: 60` in `dev` — different from base's `30`.
- `app.tags[1]: production` in `prod` — different from base's `stable`.
- `app.name: prod-app` and `app.greeting` in `prod` — `greeting`'s literal YAML text is
  identical to the base file's, but its *resolved* value differs because `app.name`
  is overridden in the same file, so it correctly does **not** trigger R2/R4.
- `app.debugFlag: null` in `dev` — explicit null, silently excluded from every rule.
