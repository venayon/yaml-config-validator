# PROJECT_CONTEXT.md — YAML Config Structural Validator

> **Read this file in full before writing or modifying any code in this
> project.** It is the single source of truth for *why* the code looks the
> way it does, not just what it does. Several design decisions here exist
> specifically to correct bugs an earlier iteration had — re-deriving from
> first principles without reading the "Implementation gotchas" section
> below will likely reintroduce them.

---

## 1. Purpose

A plain JUnit test suite (`YamlConfigStructureTest`) that enforces
structural rules across a Spring Boot application's YAML configuration
files, without starting a Spring context. It exists to catch configuration
drift and copy-paste duplication between `application.yml` and
`application-{profile}.yml` files at build time, before they reach runtime.

This is **not** a library, a runnable service, or a Spring Boot application
itself. It is a self-contained test class plus sample fixtures.

## 2. Stack (fixed — do not silently change)

- Java 21
- Spring Boot 3.5.x (via BOM import, not parent inheritance — see §6)
- JUnit 6 (Jupiter) — overrides Boot's managed 5.x; pinned via
  `junit-jupiter.version` / `junit-platform.version` properties
- Maven, with `dev` / `test` / `prod` profiles (build-time convenience only
  — see §6, these do **not** drive which YAML files the test reads)
- No Spring context, no `@SpringBootTest` — plain JUnit throughout
- No external libraries beyond `spring-boot-starter-test` +
  `org.yaml:snakeyaml` (the latter is transitively present via any real
  Spring Boot starter, but is declared explicitly for a bare test module —
  see §5.3 for why it's needed at all)

If a future change wants to add a library, ask: "does
`spring-boot-starter-test` or `snakeyaml` already provide this?" before
adding a new dependency. The point of this project is to be inspectable
with zero surprise dependencies.

## 3. Inputs

- All YAML lives in `src/test/resources/`.
- Base file: `application.yml`.
- Profile files: `application-{profile}.yml`.
- Profiles are **discovered**, not hardcoded: the test scans
  `classpath*:application-*.yml` at run time via
  `PathMatchingResourcePatternResolver`. Adding a new
  `application-staging.yml` file to `src/test/resources/` is enough to have
  it validated — no code change needed.

## 4. The rule catalog

Each rule is one (or one parameterized) `@Test` method, and every failure
message is tagged with the rule ID so CI logs are self-explanatory without
opening this file.

| ID | Statement | Test shape |
|----|-----------|-----------|
| R1 | Every key in `application-{profile}.yml` must exist in `application.yml`. | `@ParameterizedTest` over profiles |
| R2 | If a key exists in both files with the **same resolved value**, that's a failure — common values belong in `application.yml` only. | `@ParameterizedTest` over profiles |
| R3 | Profile files must not introduce any key absent from `application.yml`. | `@ParameterizedTest` over profiles |
| R4 | Every override must actually change the value (after placeholder resolution). | `@ParameterizedTest` over profiles |
| R5 | **Composite rule** (see below): a key belongs in a profile file **if and only if** its resolved value differs from `application.yml`. | Single `@Test`, all profiles at once |

### On R1≡R3 and R2≡R4

R1/R3 are the same check ("key must exist in base") under two rule IDs, and
R2/R4 are the same check ("resolved values must differ") under two rule
IDs. This is intentional, confirmed by the spec's own worked example, which
tags one violation as `"R1 + R3"`. They are implemented as **separate test
methods** so each rule ID is independently traceable in CI output — do not
merge them into one method, even though the logic is identical.

### R5's real history — read this before touching R5

R5 was originally specified as **"all profile files must define the exact
same key set."** That version shipped and was later found to be *wrong*:
it's incompatible with the actual intent, because different profiles are
not required to override the same keys as each other (e.g. `dev` might
tweak logging while `prod` tweaks a datasource URL, and neither is wrong
for not touching the other's key).

R5 is now: **"a key belongs in `application-{profile}.yml` if and only if
its value differs from `application.yml`."** This is logically the union
of R1/R3 ("no extra keys") and R2/R4 ("no unchanged duplicates"), checked
across every profile in one pass. It is intentionally redundant with
R1–R4 — R5 exists as a single comprehensive cross-profile assertion, per
the requirement that "R5 is a single `@Test` that compares all profiles at
once," not because it catches something R1–R4 miss.

**If a future change to R5 reintroduces "same key set across profiles,"
that is a regression — it directly contradicts this rule's stated intent
and will fail on entirely legitimate, differently-scoped profile files.**

### Message and reason contract

Every failure follows:

```
.message("[R{n}] <what failed> — key=<key>, file=<file>, value=<v>")
.reason("<why this rule exists>")
```

built via `AssertionFailureBuilder...buildAndThrow()`. When a rule finds
multiple violations in one test run, they are joined into a single message
(see §5.4) — each individual line still follows the format above.

## 5. Architecture & the reasoning behind it

The class deliberately keeps two concerns separate: **which keys exist**
(discovery) and **what a key resolves to** (resolution). Conflating them
was the source of the two real bugs described below — keep them separate
in any future change.

### 5.1 Key discovery

`YamlPropertySourceLoader.load(name, resource)` returns
`List<PropertySource<?>>`. Casting each to `EnumerablePropertySource<?>`
and calling `getPropertyNames()` gives the flattened key set — dot-paths
for nested maps, `[i]` suffixes for list elements — for free. No manual
flattening logic should be (re-)written; the loader already does this
exactly the way Spring itself does.

### 5.2 Value resolution — route everything through `Environment`

**Do not** read a value off a `PropertySource` and inspect its Java type
by hand (e.g. `instanceof String`) to decide whether to run placeholder
resolution on it.

`YamlPropertySourceLoader`'s underlying `OriginTrackedMapPropertySource`
wraps every scalar leaf value in an `OriginTrackedValue` for origin
tracking. A YAML string comes back as an `OriginTrackedCharSequence`
(implements `CharSequence`, **not** `String`). Code that checks
`value instanceof String` before resolving `${...}` placeholders will
*silently never match a real YAML value* and placeholders will never
resolve — this was a real bug in an earlier draft of this class and
produced false negatives on R2/R4 (two genuinely-different values could
compare as "equal" based on unresolved literal text).

The fix, and the pattern to keep using: register the actual
`PropertySource<?>` objects (unmodified) into a `StandardEnvironment`, and
call `environment.getProperty(key)`. Spring's own
`PropertySourcesPropertyResolver` unwraps origin-tracking, resolves nested
placeholders, and converts non-string scalars to `String` — all correctly,
because it's the exact same code path real Spring Boot apps rely on at
runtime. This is simpler *and* correct; do not reintroduce manual
unwrapping.

**Priority ordering:** profile source(s) > base source(s) > JVM system
properties > OS environment variables (the latter two are already present
by default in any `new StandardEnvironment()`). Base/profile content is
deliberately given *higher* priority than ambient system state. This is a
deliberate deviation from typical runtime Spring precedence (where system
props often win) — the reasoning is that a structural test's pass/fail
result must be deterministic and must not depend on what happens to be set
in the CI machine's environment. System/env variables still serve as a
fallback for placeholders that reference values not defined in any YAML
file at all.

### 5.3 Explicit-null detection — why a second parser is necessary

Spring's own flattening (`YamlProcessor.buildFlattenedMap`, used inside
`YamlPropertySourceLoader`) silently turns `key: null` into an **empty
string `""`**, not a real `null`. A null-check against the loader's output
(`if (value != null)`) can therefore never trigger — this was the second
real bug in an earlier draft: the required "skip nulls" edge case was
specified but was actually dead code.

The fix: parse each YAML file a second time, directly, with
`org.yaml.snakeyaml.Yaml`, independent of the loader. Walk the resulting
tree (recursing into maps and lists, building the same dot/`[i]` path
convention as Spring's own flattening) and record the paths of leaves that
are genuinely `null`. Multiple `---`-separated documents in one file are
deep-merged first (later document's values, at any depth, override
earlier ones — including "un-nulling" a key an earlier document set to
null) before walking, so multi-document override semantics are handled
correctly rather than naively unioned. Those paths are then subtracted
from the key set the loader produced, before any rule ever sees them.

This is why `org.yaml:snakeyaml` must be an explicit test dependency (see
§2) rather than assumed transitive — it's used directly, not just as an
implementation detail of the loader.

### 5.4 Failure aggregation — report everything found, not just the first

Each rule method collects violations into a `List<String>` during its loop
and calls a shared `failIfAny(violations, reason)` helper once at the end,
rather than calling `AssertionFailureBuilder...buildAndThrow()` inside the
loop on the first match. `failIfAny` is a no-op if the list is empty, and
otherwise throws once with every violation line joined into one message.

This matters because `AssertionFailureBuilder` throws on `buildAndThrow()`
— a hard assertion inside a loop aborts the method at the first problem,
hiding every other violation in that same file until the first one is
fixed and the suite is re-run. For R1–R4 (parameterized per profile) this
was a minor annoyance (three profiles = three separate test invocations,
so at least you'd see one violation per profile). For R5 (a single
`@Test` spanning every profile) it was worse: only the very first
violation found across *all* profiles would ever be visible per run. If a
future change adds any new rule, follow the same pattern — collect, then
call `failIfAny` once — do not throw inside the loop.

### 5.5 `classpath*:` scanning — a known, accepted limitation

Profile discovery uses `classpath*:application-*.yml`, which scans every
JAR on the classpath, not just this module's own test resources. A
dependency that happens to ship its own `application-<name>.yml` (some
Spring Boot starters/autoconfiguration modules do this for defaults) would
also be picked up as a "profile" by this scan. This is accepted as-is
because the original specification explicitly calls for
`classpath*:application-*.yml` scanning — if this ever becomes a real
problem, the fix is switching to a single-classloader `classpath:` scan
scoped to this module's own `target/test-classes`, which is a deliberate
behavior change and should be called out if made.

## 6. Maven layout notes

- No `spring-boot-starter-parent` inheritance — the Spring Boot BOM is
  imported via `<dependencyManagement>` instead, so this module doesn't
  inherit plugin bindings meant for runnable Spring Boot applications
  (e.g. `spring-boot-maven-plugin` repackaging, which would otherwise
  expect a main class this project doesn't have).
- `maven-surefire-plugin` version is pinned explicitly (not left to
  Boot's managed default) because JUnit 6 / JUnit Platform 6 needs a
  Surefire recent enough to support it.
- The `dev` / `test` / `prod` Maven profiles only set
  `spring.profiles.active` for a hypothetical downstream runnable
  artifact. **They do not affect which `application-*.yml` files the test
  reads** — that's runtime classpath scanning, unrelated to which Maven
  profile activated the build. Don't conflate the two when explaining or
  extending this project.

## 7. Edge case matrix (must stay covered by tests/fixtures)

| Edge case | Handling | Where |
|---|---|---|
| Missing profile file | Never discovered by the `classpath*:` scan in the first place; `loadSources`/`computeExplicitNullPaths` also guard on `resource.exists()` as defense in depth | §5.1, §5.3 |
| Empty YAML file | `YamlPropertySourceLoader.load()` returns an empty list for blank content; `Yaml.loadAll()` yields zero documents → empty merged map | §5.1, §5.3 |
| Lists | Flattened to `[i]`-indexed keys by the loader; comparisons are per-index, so a profile appending an extra `[2]` element triggers R1/R3 like any other new key | §5.1 |
| Placeholders | Resolved via layered `StandardEnvironment` (§5.2), not by hand — this also means a placeholder can reference a key overridden in the *same* profile file and resolve correctly against that override, not the base value | §5.2 |
| Explicit `null` values | Skipped entirely (excluded from every rule) via the dedicated SnakeYAML pass | §5.3 |
| Multi-document YAML (`---`) in one file | Deep-merged before null-detection; the primary key/value pipeline uses flat layered `PropertySource`s where a later document's keys simply take priority for identical key names. These two strategies agree for the (expected, common) single-document case; they can diverge only if a later document replaces an entire subtree with a scalar, which is considered out of scope — **avoid multi-document `application-*.yml` files** if this ambiguity matters to you | §5.3 |

## 8. Non-goals / explicitly out of scope

- No Spring context, no property binding to `@ConfigurationProperties`
  classes, no validation against a schema.
- No opinion on *values themselves* (types, ranges, secrets-in-plaintext,
  etc.) — this is purely structural (which keys exist, whether values
  differ), not semantic.
- Not wired into a CI pipeline here — it's a standalone Maven module.
  `testFailureIgnore=true` in the sample `pom.xml` is a **demo-only**
  convenience so `mvn test` prints everything without failing the build;
  flip it to `false` (or delete the line) for real CI gating.

## 9. How to extend: adding a new rule (R6, R7, ...)

Follow this checklist so a new rule matches the existing contract exactly:

1. Decide the rule's scope: per-profile (→ `@ParameterizedTest` +
   `@MethodSource("profileNames")`) or cross-profile (→ single `@Test`,
   see R5).
2. Pull data from the fields already populated in `@BeforeAll`
   (`baseKeys`, `profileKeys`, `baseResolved`, `profileResolved`) — do not
   re-parse YAML or re-build an `Environment` inside a rule method; all of
   that belongs in `@BeforeAll` only.
3. Collect violations into a `List<String>` inside the method's loop; do
   **not** call `AssertionFailureBuilder` inside the loop (see §5.4).
4. Each violation string must follow:
   `"[R{n}] <what failed> — key=<key>, file=<file>, value=<v>"`.
5. Call `failIfAny(violations, "<why this rule exists>")` once, at the end
   of the method.
6. Add a row to the rule catalog table in §4 of this file, and update the
   worked example / fixture project (see §10) if the new rule needs its
   own dedicated violation to demonstrate.
7. If the new rule needs data not already captured by `@BeforeAll`
   (e.g. something about file metadata, timestamps, comments), extend the
   `@BeforeAll` method to compute and cache it once — don't compute it
   per-invocation inside the rule method.

## 10. Companion fixture project

A separate runnable Maven project (`yaml-config-validator/`) exists purely
as a demonstration harness: a `pom.xml`, this test class, and four
`application*.yml` files deliberately seeded with one violation of each
rule (including the list-index and null-skip edge cases), plus a
`README.md` documenting the exact expected `mvn test` output. When adding
a new rule, add a corresponding deliberate violation to those fixture
files and update that `README.md`'s expected-output section to match —
keep the fixture project in sync with whatever rules actually exist in the
test class, since its entire purpose is to be copy-paste runnable proof
of current behavior.

## 11. Decision log

Kept chronological, most recent last. Add an entry here for any future
change that alters a rule's meaning or a core architectural choice (not
for routine bug fixes covered by §5's "gotchas").

1. Initial version: R1–R5 implemented with hard-fail-on-first-violation
   assertions; R5 defined as "all profile files share an identical key
   set."
2. Bug fix: value comparisons were reading raw `PropertySource` values and
   checking `instanceof String` to decide whether to resolve placeholders.
   This silently never matched (see §5.2) and placeholder resolution never
   ran. Fixed by routing all value resolution through
   `Environment.getProperty(key)`.
3. Bug fix: "skip null values" was unreachable dead code because Spring's
   YAML flattening turns `null` into `""` before the loader's output is
   ever inspected (see §5.3). Fixed by adding an independent SnakeYAML
   parse pass dedicated to null-path detection.
4. Rule change: R5 redefined from "all profiles share an identical key
   set" to "a key belongs in a profile file iff its resolved value differs
   from the base file" — the original definition was incompatible with
   profiles legitimately overriding disjoint sets of keys. See §4's R5
   subsection for full reasoning; **do not revert this**.
5. Behavior change: all rules changed from hard-fail-on-first-violation to
   collect-then-report-all (see §5.4), so one test run surfaces every
   violation in scope instead of requiring one fix-and-rerun cycle per
   problem.