# Contributing

Rebound is split across a runtime, compiler plugins, a Gradle plugin, an IDE plugin, and docs. The fastest way to work on it is to build the smallest module that covers your change, then run the full root build when the Android SDK is configured.

## Prerequisites

- JDK 17
- Android SDK installed
- `local.properties` at the repo root with `sdk.dir=...`, or `ANDROID_HOME` set
- Node 20+ for the website build

## Fast Checks

Use these when you only changed one area:

```bash
./gradlew :rebound-compiler:build
./gradlew :rebound-gradle:build validatePlugins
cd rebound-compiler-k2 && ./gradlew build
cd rebound-compiler-k2-3 && ./gradlew build
```

If you changed the Android sample or runtime, use:

```bash
./gradlew :rebound-runtime:build
./gradlew :sample:assembleDebug
```

If you changed the IDE plugin:

```bash
./gradlew :rebound-ide:buildPlugin
```

If you changed the docs:

```bash
cd website && npm ci && npm run build
```

## Full Verification

When the Android SDK is configured, run the root build:

```bash
./gradlew build
```

## What To Check First

- Public version strings match `gradle.properties`
- README examples match the website docs
- CLI docs match the snapshot JSON emitted by `ReboundSnapshot`
- Any new feature claim is backed by code, a test, or a linked roadmap item

## Before You Open A PR

- Run the smallest relevant build first
- Run the docs build if you touched markdown or the website
- Include a short note in the PR description if a feature is intentionally partial or still experimental
