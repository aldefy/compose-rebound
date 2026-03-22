# CI Parallel Builds Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace two sequential single-job CI workflows with parallel job graphs, cutting CI time by running independent builds simultaneously.

**Architecture:** `build.yml` fans into 6 parallel jobs (runtime/compiler/ide/gradle-plugin/k2/k2-3) gated by a `verify` job. `publish.yml` runs a `preflight` tag check, then 5 parallel staging jobs that upload artifacts, which a `bundle` job merges into the Maven Central zip, then a `release` job creates the GitHub release.

**Tech Stack:** GitHub Actions, Gradle, Kotlin/Native (macOS runner for runtime), GPG signing, `actions/upload-artifact@v4`, `actions/download-artifact@v4`

---

## Files Modified

| File | Change |
|------|--------|
| `.github/workflows/build.yml` | Full rewrite — 1 sequential job → 6 parallel + verify |
| `.github/workflows/publish.yml` | Full rewrite — 1 sequential job → preflight + 5 parallel + bundle + release |
| `rebound-ide/build.gradle.kts` | Line 8: `version = "0.2.0"` → read from `gradle.properties` |

These three files are fully independent. Tasks 1, 2, and 3 can be implemented by parallel subagents with no conflicts.

---

## Task 1: Fix rebound-ide hardcoded version

**Files:**
- Modify: `rebound-ide/build.gradle.kts:8`

The `rebound-ide` module hardcodes `version = "0.2.0"`. The root `gradle.properties` has `rebound_version=0.2.2`. Fix it to read from the project property so the IDE zip is versioned correctly in every release.

- [ ] **Step 1: Read the current file**

Open `rebound-ide/build.gradle.kts` and confirm line 8 reads `version = "0.2.0"`.

- [ ] **Step 2: Replace the hardcoded version**

Change line 8 from:
```kotlin
version = "0.2.0"
```
to:
```kotlin
version = project.property("rebound_version") as String
```

- [ ] **Step 3: Verify the build still works**

Run from the repo root:
```bash
./gradlew :rebound-ide:buildPlugin
```
Expected: `BUILD SUCCESSFUL`. The output zip in `rebound-ide/build/distributions/` should be named `Rebound-0.2.2.zip` (or the current version), not `Rebound-0.2.0.zip`.

```bash
ls rebound-ide/build/distributions/
```
Expected: filename contains `0.2.2`, not `0.2.0`.

- [ ] **Step 4: Commit**

```bash
git add rebound-ide/build.gradle.kts
git commit -m "fix: read rebound-ide version from gradle.properties instead of hardcoding"
```

---

## Task 2: Rewrite build.yml with parallel jobs

**Files:**
- Modify: `.github/workflows/build.yml` (full rewrite)

Replace the single sequential job with 6 parallel jobs and a `verify` gate. The `verify` job is the single required status check for branch protection — avoids needing to list all 6 job names in branch protection rules.

Key decisions:
- `build-runtime` uses `macos-latest` — Kotlin/Native requires Apple toolchain for iOS targets
- All other jobs use `ubuntu-latest` — JVM-only, faster and cheaper
- `GRADLE_OPTS: -Dorg.gradle.daemon=false` on all jobs — prevents Gradle daemon cache races when root and `rebound-gradle` standalone run in the same workspace
- `build-gradle-plugin` uses `working-directory: rebound-gradle` — it's a composite build with its own `gradlew`
- `build-compiler-k2` and `build-compiler-k2-3` use their own `gradlew` via `working-directory`

- [ ] **Step 1: Write the new build.yml**

Replace the entire contents of `.github/workflows/build.yml` with:

```yaml
name: Build & Test

on:
  push:
    branches: [master, main]
  pull_request:
    branches: [master, main]

env:
  GRADLE_OPTS: >-
    -Dorg.gradle.daemon=false
    -Dkotlin.incremental=false

jobs:
  build-runtime:
    name: Build · runtime (KMP)
    runs-on: macos-latest
    timeout-minutes: 30
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: zulu
          java-version: 17
      - uses: gradle/actions/setup-gradle@v4
      - name: Cache Kotlin/Native compiler
        uses: actions/cache@v4
        with:
          path: ~/.konan
          key: konan-${{ runner.os }}-${{ hashFiles('**/*.gradle.kts', 'gradle/libs.versions.toml') }}
          restore-keys: konan-${{ runner.os }}-
      - name: Build runtime
        run: ./gradlew :rebound-runtime:build

  build-compiler:
    name: Build · compiler
    runs-on: ubuntu-latest
    timeout-minutes: 15
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: zulu
          java-version: 17
      - uses: gradle/actions/setup-gradle@v4
      - name: Build compiler
        run: ./gradlew :rebound-compiler:build

  build-ide:
    name: Build · IDE plugin
    runs-on: ubuntu-latest
    timeout-minutes: 15
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: zulu
          java-version: 17
      - uses: gradle/actions/setup-gradle@v4
      - name: Build IDE plugin
        run: ./gradlew :rebound-ide:buildPlugin
      - name: Upload IDE plugin artifact
        uses: actions/upload-artifact@v4
        with:
          name: rebound-ide-plugin
          path: rebound-ide/build/distributions/*.zip

  build-gradle-plugin:
    name: Build · Gradle plugin
    runs-on: ubuntu-latest
    timeout-minutes: 15
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: zulu
          java-version: 17
      - uses: gradle/actions/setup-gradle@v4
      - name: Build and validate Gradle plugin
        working-directory: rebound-gradle
        run: ./gradlew build validatePlugins

  build-compiler-k2:
    name: Build · compiler-k2 (Kotlin 2.2)
    runs-on: ubuntu-latest
    timeout-minutes: 15
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: zulu
          java-version: 17
      - uses: gradle/actions/setup-gradle@v4
      - name: Build compiler-k2
        working-directory: rebound-compiler-k2
        run: ./gradlew build

  build-compiler-k2-3:
    name: Build · compiler-k2-3 (Kotlin 2.3)
    runs-on: ubuntu-latest
    timeout-minutes: 15
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: zulu
          java-version: 17
      - uses: gradle/actions/setup-gradle@v4
      - name: Build compiler-k2-3
        working-directory: rebound-compiler-k2-3
        run: ./gradlew build

  verify:
    name: CI ✓
    needs:
      - build-runtime
      - build-compiler
      - build-ide
      - build-gradle-plugin
      - build-compiler-k2
      - build-compiler-k2-3
    runs-on: ubuntu-latest
    steps:
      - run: echo "All parallel builds passed"
```

- [ ] **Step 2: Commit**

```bash
git add .github/workflows/build.yml
git commit -m "ci: parallelize build workflow into 6 independent jobs"
```

---

## Task 3: Rewrite publish.yml with parallel staging

**Files:**
- Modify: `.github/workflows/publish.yml` (full rewrite)

Replace the single sequential publish job with: `preflight` → 5 parallel `stage-*` jobs → `bundle` → `release`.

Key decisions:
- `preflight` runs the tag verification check first — all stage jobs `needs: [preflight]`, so no signing/publishing begins if the tag is wrong
- Each stage job imports GPG inline (GPG keys can't be shared across jobs via artifacts securely)
- `stage-runtime` uses `macos-latest` for Kotlin/Native; all others use `ubuntu-latest`
- `actions/download-artifact@v4` with `pattern: staging-*` downloads each artifact to a directory named after the artifact (e.g., `staging-runtime/`)
- The staging-deploy directory structure is `<artifact-name>/io/...` after download, so rsync paths are `staging-runtime/io/` etc.
- `release` job uses `ubuntu-latest` — IDE plugin buildPlugin is JVM-only
- `permissions: contents: write` stays at workflow level (already set)

- [ ] **Step 1: Write the new publish.yml**

Replace the entire contents of `.github/workflows/publish.yml` with:

```yaml
name: Release

on:
  push:
    tags:
      - "[0-9]+.[0-9]+.[0-9]+*"   # e.g. 0.2.2, 0.3.0-beta01

concurrency:
  group: release-${{ github.ref }}
  cancel-in-progress: false

env:
  GRADLE_OPTS: >-
    -Dorg.gradle.daemon=false
    -Dkotlin.incremental=false

permissions:
  contents: write

jobs:
  preflight:
    name: Preflight · verify tag
    runs-on: ubuntu-latest
    timeout-minutes: 5
    steps:
      - uses: actions/checkout@v4
      - name: Verify tag matches gradle.properties version
        run: |
          TAG=${GITHUB_REF#refs/tags/}
          GRADLE_VERSION=$(grep '^rebound_version=' gradle.properties | cut -d'=' -f2)
          if [ "$TAG" != "$GRADLE_VERSION" ]; then
            echo "::error::Tag '$TAG' does not match gradle.properties version '$GRADLE_VERSION'"
            exit 1
          fi
          echo "Version check passed: $TAG"

  stage-runtime:
    name: Stage · runtime (KMP)
    needs: [preflight]
    runs-on: macos-latest
    timeout-minutes: 45
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17
      - uses: gradle/actions/setup-gradle@v4
      - name: Cache Kotlin/Native compiler
        uses: actions/cache@v4
        with:
          path: ~/.konan
          key: konan-${{ runner.os }}-${{ hashFiles('**/*.gradle.kts', 'gradle/libs.versions.toml') }}
          restore-keys: konan-${{ runner.os }}-
      - name: Import GPG key
        uses: crazy-max/ghaction-import-gpg@v6
        with:
          gpg_private_key: ${{ secrets.GPG_PRIVATE_KEY }}
          passphrase: ${{ secrets.GPG_PASSPHRASE }}
      - name: Publish runtime to local staging
        run: |
          ./gradlew :rebound-runtime:publishAllPublicationsToLocalStagingRepository \
            -Psigning.gnupg.keyName=${{ secrets.GPG_KEY_ID }} \
            -Psigning.gnupg.passphrase=${{ secrets.GPG_PASSPHRASE }}
      - name: Upload staging artifact
        uses: actions/upload-artifact@v4
        with:
          name: staging-runtime
          path: rebound-runtime/build/staging-deploy/

  stage-compiler:
    name: Stage · compiler
    needs: [preflight]
    runs-on: ubuntu-latest
    timeout-minutes: 15
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17
      - uses: gradle/actions/setup-gradle@v4
      - name: Import GPG key
        uses: crazy-max/ghaction-import-gpg@v6
        with:
          gpg_private_key: ${{ secrets.GPG_PRIVATE_KEY }}
          passphrase: ${{ secrets.GPG_PASSPHRASE }}
      - name: Publish compiler to local staging
        run: |
          ./gradlew :rebound-compiler:publishAllPublicationsToLocalStagingRepository \
            -Psigning.gnupg.keyName=${{ secrets.GPG_KEY_ID }} \
            -Psigning.gnupg.passphrase=${{ secrets.GPG_PASSPHRASE }}
      - name: Upload staging artifact
        uses: actions/upload-artifact@v4
        with:
          name: staging-compiler
          path: rebound-compiler/build/staging-deploy/

  stage-gradle-plugin:
    name: Stage · Gradle plugin
    needs: [preflight]
    runs-on: ubuntu-latest
    timeout-minutes: 15
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17
      - uses: gradle/actions/setup-gradle@v4
      - name: Import GPG key
        uses: crazy-max/ghaction-import-gpg@v6
        with:
          gpg_private_key: ${{ secrets.GPG_PRIVATE_KEY }}
          passphrase: ${{ secrets.GPG_PASSPHRASE }}
      - name: Publish Gradle plugin to local staging
        working-directory: rebound-gradle
        run: |
          ./gradlew publishAllPublicationsToLocalStagingRepository \
            -Psigning.gnupg.keyName=${{ secrets.GPG_KEY_ID }} \
            -Psigning.gnupg.passphrase=${{ secrets.GPG_PASSPHRASE }}
      - name: Upload staging artifact
        uses: actions/upload-artifact@v4
        with:
          name: staging-gradle-plugin
          path: rebound-gradle/build/staging-deploy/

  stage-compiler-k2:
    name: Stage · compiler-k2 (Kotlin 2.2)
    needs: [preflight]
    runs-on: ubuntu-latest
    timeout-minutes: 15
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17
      - uses: gradle/actions/setup-gradle@v4
      - name: Import GPG key
        uses: crazy-max/ghaction-import-gpg@v6
        with:
          gpg_private_key: ${{ secrets.GPG_PRIVATE_KEY }}
          passphrase: ${{ secrets.GPG_PASSPHRASE }}
      - name: Publish compiler-k2 to local staging
        working-directory: rebound-compiler-k2
        run: |
          ./gradlew publishAllPublicationsToLocalStagingRepository \
            -Psigning.gnupg.keyName=${{ secrets.GPG_KEY_ID }} \
            -Psigning.gnupg.passphrase=${{ secrets.GPG_PASSPHRASE }}
      - name: Upload staging artifact
        uses: actions/upload-artifact@v4
        with:
          name: staging-compiler-k2
          path: rebound-compiler-k2/build/staging-deploy/

  stage-compiler-k2-3:
    name: Stage · compiler-k2-3 (Kotlin 2.3)
    needs: [preflight]
    runs-on: ubuntu-latest
    timeout-minutes: 15
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17
      - uses: gradle/actions/setup-gradle@v4
      - name: Import GPG key
        uses: crazy-max/ghaction-import-gpg@v6
        with:
          gpg_private_key: ${{ secrets.GPG_PRIVATE_KEY }}
          passphrase: ${{ secrets.GPG_PASSPHRASE }}
      - name: Publish compiler-k2-3 to local staging
        working-directory: rebound-compiler-k2-3
        run: |
          ./gradlew publishAllPublicationsToLocalStagingRepository \
            -Psigning.gnupg.keyName=${{ secrets.GPG_KEY_ID }} \
            -Psigning.gnupg.passphrase=${{ secrets.GPG_PASSPHRASE }}
      - name: Upload staging artifact
        uses: actions/upload-artifact@v4
        with:
          name: staging-compiler-k2-3
          path: rebound-compiler-k2-3/build/staging-deploy/

  bundle:
    name: Bundle · merge staging → zip
    needs:
      - stage-runtime
      - stage-compiler
      - stage-gradle-plugin
      - stage-compiler-k2
      - stage-compiler-k2-3
    runs-on: ubuntu-latest
    timeout-minutes: 10
    steps:
      - name: Download all staging artifacts
        uses: actions/download-artifact@v4
        with:
          pattern: staging-*
      - name: Merge and zip
        run: |
          TAG=${GITHUB_REF#refs/tags/}
          mkdir -p bundle/io
          # Each artifact downloads to staging-<name>/io/...
          rsync -a staging-runtime/io/        bundle/io/
          rsync -a staging-compiler/io/       bundle/io/
          rsync -a staging-gradle-plugin/io/  bundle/io/
          rsync -a staging-compiler-k2/io/    bundle/io/
          rsync -a staging-compiler-k2-3/io/  bundle/io/
          cd bundle
          zip -r "$GITHUB_WORKSPACE/rebound-${TAG}-bundle.zip" io/
      - name: Upload bundle
        uses: actions/upload-artifact@v4
        with:
          name: release-bundle
          path: rebound-*-bundle.zip

  release:
    name: Release · create GitHub release
    needs: [bundle]
    runs-on: ubuntu-latest
    timeout-minutes: 20
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17
      - uses: gradle/actions/setup-gradle@v4
      - name: Download bundle zip
        uses: actions/download-artifact@v4
        with:
          name: release-bundle
      - name: Build IDE plugin
        run: ./gradlew :rebound-ide:buildPlugin
      - name: Create GitHub release
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          TAG=${GITHUB_REF#refs/tags/}
          IDE_ZIP=$(find rebound-ide/build/distributions -name "*.zip" | head -1)
          gh release create "$TAG" \
            "rebound-${TAG}-bundle.zip" \
            "$IDE_ZIP" \
            --title "Rebound $TAG" \
            --generate-notes \
            --latest
```

- [ ] **Step 2: Commit**

```bash
git add .github/workflows/publish.yml
git commit -m "ci: parallelize publish workflow — preflight + 5 parallel stage jobs + bundle + release"
```

---

## Final Step: Push and verify

- [ ] **Push all commits**

```bash
git push origin master
```

- [ ] **Verify build.yml runs correctly**

Go to `github.com/aldefy/compose-rebound/actions` and confirm the next push to master shows 7 jobs in the Build & Test workflow (6 parallel + verify), all passing.

- [ ] **Done**

The publish workflow will be exercised on the next version tag push.
