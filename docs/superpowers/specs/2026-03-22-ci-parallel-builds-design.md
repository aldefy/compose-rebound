# CI Parallel Builds Design

**Date:** 2026-03-22
**Status:** Approved

## Problem

Both `build.yml` and `publish.yml` are single-job sequential workflows. Each step blocks the next despite being fully independent builds. A slow multiplatform runtime build blocks fast compiler builds, IDE plugin builds, and Gradle plugin validation.

## Build Topology

### Root project subprojects (share a single Gradle invocation)
- `:rebound-runtime` — KMP library (Android, JVM, iOS)
- `:rebound-compiler` — Kotlin compiler plugin base
- `:rebound-ide` — IntelliJ/Android Studio plugin

### Standalone composite builds (each has its own `gradlew`)
- `rebound-gradle/` — Gradle plugin + plugin marker artifact
- `rebound-compiler-k2/` — Compiler plugin for Kotlin 2.2.x
- `rebound-compiler-k2-3/` — Compiler plugin for Kotlin 2.3.x

### Key dependency insight
`rebound-compiler-k2` and `rebound-compiler-k2-3` reference
`../rebound-compiler/src/main/resources` as a **source directory** (not a
build output), so they do not need `:rebound-compiler` to compile first.
All 6 build units are fully independent.

## Design: build.yml

### Jobs (all run in parallel)

| Job | Commands | Runner |
|-----|----------|--------|
| `build-runtime` | `./gradlew :rebound-runtime:build` | macos-latest |
| `build-compiler` | `./gradlew :rebound-compiler:build` | ubuntu-latest |
| `build-ide` | `./gradlew :rebound-ide:buildPlugin` | ubuntu-latest |
| `build-gradle-plugin` | `cd rebound-gradle && ./gradlew build validatePlugins` | ubuntu-latest |
| `build-compiler-k2` | `cd rebound-compiler-k2 && ./gradlew build` | ubuntu-latest |
| `build-compiler-k2-3` | `cd rebound-compiler-k2-3 && ./gradlew build` | ubuntu-latest |

Notes:
- `build-runtime` stays on `macos-latest` (needs Kotlin/Native for iOS targets)
- All compiler/JVM-only jobs use `ubuntu-latest` (cheaper, faster for JVM)
- `build-ide` uploads `rebound-ide/build/distributions/*.zip` as artifact
- All jobs must set `GRADLE_OPTS: -Dorg.gradle.daemon=false` — root jobs and `rebound-gradle` standalone job run separate Gradle daemons that can race on shared caches; disabling daemons (already done in `publish.yml`) prevents flakiness

### Verify gate

```yaml
verify:
  needs: [build-runtime, build-compiler, build-ide, build-gradle-plugin, build-compiler-k2, build-compiler-k2-3]
  runs-on: ubuntu-latest
  steps:
    - run: echo "All parallel builds passed"
```

This single job is used as the branch protection required status check instead of individual jobs.

## Design: publish.yml

### Preflight job

Runs first. All stage jobs declare `needs: [preflight]`.

Steps:
1. Verify git tag matches `rebound_version` in `gradle.properties` — fail fast before any signing/publishing work begins
2. Import GPG key (done once here, stage jobs inherit via artifact or repeat inline)

This ensures all five parallel stage jobs only start after the tag is confirmed correct. The alternative (inline check in each stage job) wastes 4x GPG signing time if the tag is wrong.

### Stage jobs (all run in parallel, `needs: [preflight]`)

| Job | Working dir | Publish task | Uploaded artifact name |
|-----|-------------|--------------|------------------------|
| `stage-runtime` | root | `./gradlew :rebound-runtime:publishAllPublicationsToLocalStagingRepository` | `staging-runtime` |
| `stage-compiler` | root | `./gradlew :rebound-compiler:publishAllPublicationsToLocalStagingRepository` | `staging-compiler` |
| `stage-gradle-plugin` | `rebound-gradle/` | `./gradlew publishAllPublicationsToLocalStagingRepository` | `staging-gradle-plugin` |
| `stage-compiler-k2` | `rebound-compiler-k2/` | `./gradlew publishAllPublicationsToLocalStagingRepository` | `staging-compiler-k2` |
| `stage-compiler-k2-3` | `rebound-compiler-k2-3/` | `./gradlew publishAllPublicationsToLocalStagingRepository` | `staging-compiler-k2-3` |

Each stage job uploads its `<module>/build/staging-deploy/` as a GitHub Actions artifact.

### Bundle job

`needs: [stage-runtime, stage-compiler, stage-gradle-plugin, stage-compiler-k2, stage-compiler-k2-3]`

Steps:
1. Download all 5 staging artifacts (each downloaded to its module path)
2. Merge into `bundle/io/` via explicit rsync of these 5 paths:
   - `rebound-runtime/build/staging-deploy/io/`
   - `rebound-compiler/build/staging-deploy/io/`
   - `rebound-gradle/build/staging-deploy/io/`
   - `rebound-compiler-k2/build/staging-deploy/io/`
   - `rebound-compiler-k2-3/build/staging-deploy/io/`
   - Note: `rebound-ide` is intentionally excluded (not published to Maven Central)
3. `zip -r rebound-${TAG}-bundle.zip bundle/io/`
4. Upload bundle zip as artifact

### Release job

`needs: [bundle]`

Steps:
1. Download bundle zip artifact
2. `./gradlew :rebound-ide:buildPlugin` (IDE plugin not on Maven Central, built fresh here)
3. `gh release create` with bundle zip + IDE zip

**Known prerequisite:** `rebound-ide/build.gradle.kts` currently hardcodes `version = "0.2.0"` instead of reading from `gradle.properties`. The IDE zip produced in step 2 will carry the wrong version until this is fixed. Fix is out of scope for CI parallelization but must be done before the release job produces a correctly-versioned IDE artifact.

## Runner Strategy

| Build type | Runner | Reason |
|------------|--------|--------|
| KMP (runtime) | `macos-latest` | Kotlin/Native requires macOS for iOS targets |
| JVM-only | `ubuntu-latest` | Faster startup, cheaper, no Apple toolchain needed |
| IDE plugin | `ubuntu-latest` | IntelliJ platform build is JVM-only |

## Error Handling

- Each parallel job fails independently — a compiler-k2 failure does not cancel runtime build
- `verify` / `bundle` jobs fail if any upstream job failed (GitHub Actions default with `needs`)
- No `continue-on-error` — all jobs must pass

## Tag Verification

Tag verification runs in the dedicated `preflight` job. All five stage jobs declare `needs: [preflight]` and do not repeat this check. This ensures no signing or publishing work begins if the tag is wrong.
