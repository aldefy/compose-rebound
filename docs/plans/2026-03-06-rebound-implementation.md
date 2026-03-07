# Rebound Implementation Plan — Hack Weekend MVP

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Prove the IR transformer works end-to-end: compiler plugin injects tracking calls into @Composable functions, runtime counts recompositions, sample app shows it working.

**Architecture:** Three Gradle modules — `rebound-compiler` (IR plugin), `rebound-runtime` (KMP counters), `sample` (Android app with Compose). The Gradle plugin is built as a `buildSrc` convention plugin for now (no separate published plugin yet — that's post-MVP). Kotlin 2.0.21 + Compose Multiplatform 1.7.3 (matching Lumen's proven stack).

**Tech Stack:** Kotlin 2.0.21, Compose Multiplatform 1.7.3, Kotlin IR Plugin API (`IrGenerationExtension`), Gradle `KotlinCompilerPluginSupportPlugin`

**Reference repos:**
- Lumen (`~/Documents/AndroidProjects/Lumen/`) — KMP build patterns, version catalog
- `bnorm/kotlin-ir-plugin-template` — IR plugin scaffold
- `VKCOM/vkompose` — Compose-specific IR transformation patterns

---

### Task 1: Scaffold the Multi-Module Gradle Project

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `gradle/libs.versions.toml`

**Step 1: Create `gradle/libs.versions.toml`**

```toml
[versions]
kotlin = "2.0.21"
compose-multiplatform = "1.7.3"
agp = "8.2.2"
activity-compose = "1.8.2"

[libraries]
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activity-compose" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
compose-multiplatform = { id = "org.jetbrains.compose", version.ref = "compose-multiplatform" }
```

**Step 2: Create `settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "compose-rebound"
include(":rebound-runtime")
include(":rebound-compiler")
include(":rebound-gradle")
include(":sample")
```

**Step 3: Create root `build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose.multiplatform) apply false
}
```

**Step 4: Create `gradle.properties`**

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
kotlin.mpp.androidSourceSetLayoutVersion=2
```

**Step 5: Copy Gradle wrapper from Lumen**

Run: `cp -r ~/Documents/AndroidProjects/Lumen/gradle/wrapper gradle/wrapper && cp ~/Documents/AndroidProjects/Lumen/gradlew . && cp ~/Documents/AndroidProjects/Lumen/gradlew.bat . && chmod +x gradlew`

**Step 6: Initialize git**

Run: `git init && git add -A && git commit -m "chore: scaffold multi-module Gradle project"`

---

### Task 2: Create the rebound-runtime Module (KMP)

This is the module that the compiler plugin's injected code calls into. Start with commonMain + androidMain only for the spike.

**Files:**
- Create: `rebound-runtime/build.gradle.kts`
- Create: `rebound-runtime/src/commonMain/kotlin/io/github/nickalert/rebound/ReboundTracker.kt`
- Create: `rebound-runtime/src/commonMain/kotlin/io/github/nickalert/rebound/BudgetClass.kt`
- Create: `rebound-runtime/src/commonMain/kotlin/io/github/nickalert/rebound/ComposableMetrics.kt`
- Create: `rebound-runtime/src/commonMain/kotlin/io/github/nickalert/rebound/ReboundLogger.kt`
- Create: `rebound-runtime/src/androidMain/kotlin/io/github/nickalert/rebound/AndroidReboundLogger.kt`
- Create: `rebound-runtime/src/jvmMain/kotlin/io/github/nickalert/rebound/JvmReboundLogger.kt`
- Create: `rebound-runtime/src/commonTest/kotlin/io/github/nickalert/rebound/ReboundTrackerTest.kt`

**Step 1: Create `rebound-runtime/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    jvm()

    sourceSets {
        commonMain.dependencies { }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "io.aldefy.rebound.runtime"
    compileSdk = 34
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
```

**Step 2: Create `BudgetClass.kt`**

```kotlin
package io.aldefy.rebound

enum class BudgetClass(val baseBudgetPerSecond: Int) {
    SCREEN(3),
    CONTAINER(10),
    INTERACTIVE(30),
    LIST_ITEM(60),
    ANIMATED(120),
    LEAF(5),
    UNKNOWN(10)
}
```

**Step 3: Create `ReboundLogger.kt` (expect/actual)**

```kotlin
// commonMain
package io.aldefy.rebound

expect object ReboundLogger {
    fun log(tag: String, message: String)
    fun warn(tag: String, message: String)
}
```

```kotlin
// androidMain — AndroidReboundLogger.kt
package io.aldefy.rebound

import android.util.Log

actual object ReboundLogger {
    actual fun log(tag: String, message: String) { Log.d(tag, message) }
    actual fun warn(tag: String, message: String) { Log.w(tag, message) }
}
```

```kotlin
// jvmMain — JvmReboundLogger.kt
package io.aldefy.rebound

actual object ReboundLogger {
    actual fun log(tag: String, message: String) { println("[$tag] $message") }
    actual fun warn(tag: String, message: String) { System.err.println("[$tag] WARN: $message") }
}
```

**Step 4: Create `ComposableMetrics.kt`**

```kotlin
package io.aldefy.rebound

class ComposableMetrics(val budgetClass: BudgetClass) {
    private var compositionCount: Long = 0
    private var windowStartTimeNs: Long = 0
    private var windowCount: Int = 0

    val totalCount: Long get() = compositionCount

    fun recordComposition(currentTimeNs: Long): Int {
        compositionCount++
        val elapsed = currentTimeNs - windowStartTimeNs
        if (elapsed > 1_000_000_000L) { // 1 second window
            windowStartTimeNs = currentTimeNs
            windowCount = 1
        } else {
            windowCount++
        }
        return windowCount
    }

    fun currentRate(): Int = windowCount
}
```

**Step 5: Create `ReboundTracker.kt`**

```kotlin
package io.aldefy.rebound

object ReboundTracker {
    private val metrics = mutableMapOf<String, ComposableMetrics>()
    private const val TAG = "Rebound"

    var enabled: Boolean = true
    var logCompositions: Boolean = false

    fun onComposition(key: String, budgetClassOrdinal: Int, changedMask: Int) {
        if (!enabled) return

        val budgetClass = BudgetClass.entries.getOrElse(budgetClassOrdinal) { BudgetClass.UNKNOWN }
        val m = metrics.getOrPut(key) { ComposableMetrics(budgetClass) }
        val currentRate = m.recordComposition(currentTimeNanos())
        val budget = budgetClass.baseBudgetPerSecond

        if (logCompositions) {
            ReboundLogger.log(TAG, "$key composed (#${m.totalCount}, rate=$currentRate/s, budget=$budget/s)")
        }

        if (currentRate > budget) {
            ReboundLogger.warn(TAG, "BUDGET VIOLATION: $key rate=$currentRate/s exceeds budget=$budget/s (class=$budgetClass)")
        }
    }

    fun reset() {
        metrics.clear()
    }

    fun snapshot(): Map<String, ComposableMetrics> = metrics.toMap()
}

internal expect fun currentTimeNanos(): Long
```

Add `currentTimeNanos` expect/actuals:

```kotlin
// commonMain — add to bottom of ReboundTracker.kt or separate file
// Already declared above as expect

// androidMain — create CurrentTime.android.kt
package io.aldefy.rebound
internal actual fun currentTimeNanos(): Long = System.nanoTime()

// jvmMain — create CurrentTime.jvm.kt
package io.aldefy.rebound
internal actual fun currentTimeNanos(): Long = System.nanoTime()
```

**Step 6: Create `ReboundTrackerTest.kt`**

```kotlin
package io.aldefy.rebound

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReboundTrackerTest {
    @Test
    fun `records composition count`() {
        ReboundTracker.reset()
        ReboundTracker.enabled = true
        ReboundTracker.logCompositions = false

        ReboundTracker.onComposition("TestScreen", BudgetClass.SCREEN.ordinal, 0)
        ReboundTracker.onComposition("TestScreen", BudgetClass.SCREEN.ordinal, 0)

        val snapshot = ReboundTracker.snapshot()
        assertEquals(2L, snapshot["TestScreen"]?.totalCount)
    }

    @Test
    fun `different composables tracked separately`() {
        ReboundTracker.reset()
        ReboundTracker.onComposition("ScreenA", BudgetClass.SCREEN.ordinal, 0)
        ReboundTracker.onComposition("ScreenB", BudgetClass.LEAF.ordinal, 0)

        val snapshot = ReboundTracker.snapshot()
        assertEquals(1L, snapshot["ScreenA"]?.totalCount)
        assertEquals(1L, snapshot["ScreenB"]?.totalCount)
        assertEquals(BudgetClass.SCREEN, snapshot["ScreenA"]?.budgetClass)
        assertEquals(BudgetClass.LEAF, snapshot["ScreenB"]?.budgetClass)
    }

    @Test
    fun `disabled tracker does not record`() {
        ReboundTracker.reset()
        ReboundTracker.enabled = false
        ReboundTracker.onComposition("TestScreen", BudgetClass.SCREEN.ordinal, 0)
        assertTrue(ReboundTracker.snapshot().isEmpty())
        ReboundTracker.enabled = true
    }
}
```

**Step 7: Run tests**

Run: `./gradlew :rebound-runtime:jvmTest`
Expected: 3 tests PASS

**Step 8: Commit**

```bash
git add rebound-runtime/
git commit -m "feat: add rebound-runtime KMP module with tracker and budget classes"
```

---

### Task 3: Create the rebound-compiler Module (IR Plugin)

This is the hardest part — the GO/NO-GO gate. The compiler plugin visits every @Composable function in the IR tree and injects a call to `ReboundTracker.onComposition()`.

**Files:**
- Create: `rebound-compiler/build.gradle.kts`
- Create: `rebound-compiler/src/main/kotlin/io/github/nickalert/rebound/compiler/ReboundCompilerPluginRegistrar.kt`
- Create: `rebound-compiler/src/main/kotlin/io/github/nickalert/rebound/compiler/ReboundCommandLineProcessor.kt`
- Create: `rebound-compiler/src/main/kotlin/io/github/nickalert/rebound/compiler/ReboundIrGenerationExtension.kt`
- Create: `rebound-compiler/src/main/kotlin/io/github/nickalert/rebound/compiler/ReboundIrTransformer.kt`
- Create: `rebound-compiler/src/main/resources/META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar`
- Create: `rebound-compiler/src/main/resources/META-INF/services/org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor`

**Step 1: Create `rebound-compiler/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:${libs.versions.kotlin.get()}")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
```

**Step 2: Create `ReboundCommandLineProcessor.kt`**

```kotlin
package io.aldefy.rebound.compiler

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

val KEY_ENABLED = CompilerConfigurationKey<Boolean>("enabled")

@OptIn(ExperimentalCompilerApi::class)
class ReboundCommandLineProcessor : CommandLineProcessor {
    override val pluginId: String = "io.aldefy.rebound"

    override val pluginOptions: Collection<AbstractCliOption> = listOf(
        CliOption(
            optionName = "enabled",
            valueDescription = "<true|false>",
            description = "Whether the Rebound plugin is enabled",
            required = false
        )
    )

    override fun processOption(
        option: AbstractCliOption,
        value: String,
        configuration: CompilerConfiguration
    ) {
        when (option.optionName) {
            "enabled" -> configuration.put(KEY_ENABLED, value.toBoolean())
        }
    }
}
```

**Step 3: Create `ReboundCompilerPluginRegistrar.kt`**

```kotlin
package io.aldefy.rebound.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration

@OptIn(ExperimentalCompilerApi::class)
class ReboundCompilerPluginRegistrar : CompilerPluginRegistrar() {
    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val enabled = configuration.get(KEY_ENABLED, true)
        if (!enabled) return

        IrGenerationExtension.registerExtension(ReboundIrGenerationExtension())
    }
}
```

**Step 4: Create `ReboundIrGenerationExtension.kt`**

```kotlin
package io.aldefy.rebound.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment

class ReboundIrGenerationExtension : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        moduleFragment.transform(ReboundIrTransformer(pluginContext), null)
    }
}
```

**Step 5: Create `ReboundIrTransformer.kt`**

This is the core. It visits every function, checks for @Composable annotation, and injects `ReboundTracker.onComposition(key, budgetClassOrdinal, changedMask)` at the top of the function body.

```kotlin
package io.aldefy.rebound.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

class ReboundIrTransformer(
    private val pluginContext: IrPluginContext
) : IrElementTransformerVoid() {

    private val composableAnnotation = FqName("androidx.compose.runtime.Composable")

    // Resolve ReboundTracker.onComposition at transform time
    private val onCompositionFn: IrSimpleFunctionSymbol? by lazy {
        val classId = ClassId(
            FqName("io.aldefy.rebound"),
            Name.identifier("ReboundTracker")
        )
        val trackerClass = pluginContext.referenceClass(classId)
        trackerClass?.owner?.declarations
            ?.filterIsInstance<IrSimpleFunction>()
            ?.firstOrNull { it.name.asString() == "onComposition" }
            ?.symbol
    }

    override fun visitFunction(declaration: IrFunction): IrStatement {
        val function = super.visitFunction(declaration) as IrFunction

        // Only instrument @Composable functions
        if (!function.hasAnnotation(composableAnnotation)) return function

        // Skip abstract, expect, or functions without bodies
        val body = function.body as? IrBlockBody ?: return function

        // Resolve the tracker function
        val trackerFn = onCompositionFn ?: return function

        val builder = DeclarationIrBuilder(pluginContext, function.symbol)
        val fqName = function.kotlinFqName.asString()

        // Find the $changed parameter injected by Compose compiler
        val changedParam = function.valueParameters.firstOrNull {
            it.name.asString() == "\$changed"
        }

        val trackCall = builder.irCall(trackerFn).apply {
            // arg 0: key (String) — fully qualified function name
            putValueArgument(0, builder.irString(fqName))
            // arg 1: budgetClassOrdinal (Int) — UNKNOWN for now, classification comes later
            putValueArgument(1, builder.irInt(6)) // BudgetClass.UNKNOWN.ordinal
            // arg 2: changedMask (Int)
            putValueArgument(
                2,
                if (changedParam != null) builder.irGet(changedParam) else builder.irInt(0)
            )

            // Set the dispatch receiver to the ReboundTracker object instance
            val classId = ClassId(
                FqName("io.aldefy.rebound"),
                Name.identifier("ReboundTracker")
            )
            val trackerClass = pluginContext.referenceClass(classId)
            if (trackerClass != null) {
                dispatchReceiver = builder.irGetObject(trackerClass)
            }
        }

        // Prepend the tracking call to the function body
        body.statements.add(0, trackCall)

        return function
    }
}
```

**Step 6: Create service registration files**

```
// META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
io.aldefy.rebound.compiler.ReboundCompilerPluginRegistrar
```

```
// META-INF/services/org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
io.aldefy.rebound.compiler.ReboundCommandLineProcessor
```

**Step 7: Verify compilation**

Run: `./gradlew :rebound-compiler:build`
Expected: BUILD SUCCESSFUL (compiles against kotlin-compiler-embeddable)

**Step 8: Commit**

```bash
git add rebound-compiler/
git commit -m "feat: add rebound-compiler IR plugin with composable function instrumentation"
```

---

### Task 4: Create the rebound-gradle Module (Gradle Plugin)

Wires the compiler plugin into consuming projects via `KotlinCompilerPluginSupportPlugin`.

**Files:**
- Create: `rebound-gradle/build.gradle.kts`
- Create: `rebound-gradle/src/main/kotlin/io/github/nickalert/rebound/gradle/ReboundGradlePlugin.kt`
- Create: `rebound-gradle/src/main/kotlin/io/github/nickalert/rebound/gradle/ReboundExtension.kt`
- Create: `rebound-gradle/src/main/resources/META-INF/gradle-plugins/io.aldefy.rebound.properties`

**Step 1: Create `rebound-gradle/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin-api:${libs.versions.kotlin.get()}")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

gradlePlugin {
    plugins {
        create("rebound") {
            id = "io.aldefy.rebound"
            implementationClass = "io.aldefy.rebound.gradle.ReboundGradlePlugin"
        }
    }
}
```

**Step 2: Create `ReboundExtension.kt`**

```kotlin
package io.aldefy.rebound.gradle

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

abstract class ReboundExtension @Inject constructor(objects: ObjectFactory) {
    val enabled: Property<Boolean> = objects.property(Boolean::class.java).convention(true)
}
```

**Step 3: Create `ReboundGradlePlugin.kt`**

```kotlin
package io.aldefy.rebound.gradle

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

class ReboundGradlePlugin : KotlinCompilerPluginSupportPlugin {
    private lateinit var extension: ReboundExtension

    override fun apply(target: Project) {
        extension = target.extensions.create("rebound", ReboundExtension::class.java)

        // Add runtime dependency automatically
        target.afterEvaluate {
            target.dependencies.add("implementation",
                target.dependencies.project(mapOf("path" to ":rebound-runtime"))
            )
        }
    }

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean = true

    override fun getCompilerPluginId(): String = "io.aldefy.rebound"

    override fun getPluginArtifact(): SubpluginArtifact = SubpluginArtifact(
        groupId = "io.aldefy.rebound",
        artifactId = "rebound-compiler",
        version = "0.1.0-SNAPSHOT"
    )

    override fun applyToCompilation(
        kotlinCompilation: KotlinCompilation<*>
    ): Provider<List<SubpluginOption>> {
        val project = kotlinCompilation.target.project
        return project.provider {
            listOf(
                SubpluginOption("enabled", extension.enabled.get().toString())
            )
        }
    }
}
```

**Step 4: Create plugin properties file**

```
// META-INF/gradle-plugins/io.aldefy.rebound.properties
implementation-class=io.aldefy.rebound.gradle.ReboundGradlePlugin
```

**Step 5: Verify compilation**

Run: `./gradlew :rebound-gradle:build`
Expected: BUILD SUCCESSFUL

**Step 6: Commit**

```bash
git add rebound-gradle/
git commit -m "feat: add rebound-gradle plugin wiring compiler plugin to consuming projects"
```

---

### Task 5: Create the Sample Android App

An Android Compose app that applies the Rebound plugin and has composables that intentionally over-recompose to test detection.

**Files:**
- Create: `sample/build.gradle.kts`
- Create: `sample/src/main/AndroidManifest.xml`
- Create: `sample/src/main/kotlin/io/github/nickalert/rebound/sample/MainActivity.kt`
- Create: `sample/src/main/kotlin/io/github/nickalert/rebound/sample/OverRecomposingScreen.kt`

**Step 1: Create `sample/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    id("io.aldefy.rebound")
}

android {
    namespace = "io.aldefy.rebound.sample"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.aldefy.rebound.sample"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":rebound-runtime"))
    implementation(libs.androidx.activity.compose)
    implementation(compose.material3)
    implementation(compose.foundation)
    implementation(compose.ui)
}

rebound {
    enabled.set(true)
}
```

**Step 2: Create `sample/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:allowBackup="true"
        android:label="Rebound Sample"
        android:theme="@style/Theme.AppCompat.Light.NoActionBar">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

**Step 3: Create `OverRecomposingScreen.kt`**

This has intentional bugs for Rebound to detect:

```kotlin
package io.aldefy.rebound.sample

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// BUG: This screen-level composable reads a rapidly changing state (counter)
// causing excessive recompositions. Rebound should flag this as a SCREEN
// composable exceeding its budget.
@Composable
fun OverRecomposingScreen() {
    var counter by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(16) // ~60fps updates
            counter++
        }
    }

    // This entire screen recomposes on every counter tick
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Rebound Sample", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Counter: $counter") // This is fine — leaf composable
        Spacer(modifier = Modifier.height(16.dp))
        StableList() // This should NOT recompose with counter
    }
}

// This composable should be stable and NOT recompose when counter changes
// If it does, Rebound flags it
@Composable
fun StableList() {
    val items = remember { List(20) { "Item #$it" } }
    LazyColumn {
        items(items) { item ->
            ListItem(headlineContent = { Text(item) })
        }
    }
}
```

**Step 4: Create `MainActivity.kt`**

```kotlin
package io.aldefy.rebound.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import io.aldefy.rebound.ReboundTracker

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ReboundTracker.logCompositions = true
        setContent {
            MaterialTheme {
                OverRecomposingScreen()
            }
        }
    }
}
```

**Step 5: Verify build**

Run: `./gradlew :sample:assembleDebug`
Expected: BUILD SUCCESSFUL with Rebound compiler plugin applied

**Step 6: Run on device/emulator and check logcat**

Run: `./gradlew :sample:installDebug && adb logcat -s Rebound:* | head -50`
Expected: Logcat shows `Rebound: OverRecomposingScreen composed (#1, rate=1/s, budget=10/s)` etc.
Expected: After a few seconds, `Rebound: BUDGET VIOLATION: OverRecomposingScreen rate=60/s exceeds budget=10/s`

**Step 7: Commit**

```bash
git add sample/
git commit -m "feat: add sample Android app with intentional over-recomposition patterns"
```

---

### Task 6: Wire the Gradle Plugin to Use Local Compiler Artifact

The Gradle plugin currently references a Maven coordinate for the compiler artifact. For local development, we need it to use the locally-built JAR instead.

**Files:**
- Modify: `rebound-gradle/src/main/kotlin/io/github/nickalert/rebound/gradle/ReboundGradlePlugin.kt`
- Modify: `settings.gradle.kts`

**Step 1: Update settings to include rebound-gradle as an included build**

The cleanest pattern for a composite build: the Gradle plugin lives in an included build so it can be applied by the sample.

Update `settings.gradle.kts`:

```kotlin
pluginManagement {
    includeBuild("rebound-gradle")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "compose-rebound"
include(":rebound-runtime")
include(":rebound-compiler")
include(":sample")
```

**Step 2: Update `ReboundGradlePlugin.kt` to use project dependency for local dev**

Replace the `getPluginArtifact` to point to the local compiler module. For the MVP, add the compiler JAR to the Kotlin compiler classpath via `kotlinCompilerPluginClasspath` configuration:

```kotlin
override fun apply(target: Project) {
    extension = target.extensions.create("rebound", ReboundExtension::class.java)

    // For local development: add compiler plugin JAR to kotlin compiler classpath
    target.configurations.getByName("kotlinCompilerPluginClasspath").dependencies.add(
        target.dependencies.project(mapOf("path" to ":rebound-compiler"))
    )
}
```

And make `getPluginArtifact` return a dummy (won't be used when the classpath is set manually):

```kotlin
override fun getPluginArtifact(): SubpluginArtifact = SubpluginArtifact(
    groupId = "io.aldefy.rebound",
    artifactId = "rebound-compiler",
    version = "0.1.0-SNAPSHOT" // Not resolved in local dev — classpath set manually
)
```

**Step 3: Update `rebound-gradle/build.gradle.kts` for included build**

The gradle plugin needs its own `settings.gradle.kts` to work as an included build:

Create `rebound-gradle/settings.gradle.kts`:
```kotlin
rootProject.name = "rebound-gradle"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
```

**Step 4: Verify the full build chain**

Run: `./gradlew :sample:assembleDebug`
Expected: BUILD SUCCESSFUL — compiler plugin applied from local build

**Step 5: Commit**

```bash
git add -A
git commit -m "feat: wire composite build so sample uses local compiler plugin"
```

---

### Task 7: End-to-End Validation on Device

This is the GO/NO-GO moment. Run the sample on a device and verify Rebound tracking appears in logcat.

**Step 1: Install and run**

Run: `./gradlew :sample:installDebug`

**Step 2: Check logcat for Rebound output**

Run: `adb logcat -s Rebound:*`

Expected output pattern:
```
D/Rebound: io.aldefy.rebound.sample.OverRecomposingScreen composed (#1, rate=1/s, budget=10/s)
D/Rebound: io.aldefy.rebound.sample.StableList composed (#1, rate=1/s, budget=10/s)
...
W/Rebound: BUDGET VIOLATION: io.aldefy.rebound.sample.OverRecomposingScreen rate=60/s exceeds budget=10/s
```

**Step 3: If logcat shows tracking — IR plugin works! MVP COMPLETE.**

Commit and celebrate:
```bash
git add -A
git commit -m "feat: end-to-end validation — Rebound IR plugin successfully tracks recompositions"
```

**Step 4: If logcat shows nothing — debug**

Check if the compiler plugin was loaded:
Run: `./gradlew :sample:assembleDebug --info 2>&1 | grep -i rebound`

If the plugin wasn't loaded, check:
1. Service registration files are in correct paths
2. Plugin ID matches between CommandLineProcessor and GradlePlugin
3. The `kotlinCompilerPluginClasspath` dependency resolved correctly

If the IR transformer crashes, check:
1. `ReboundTracker` class is resolvable via `pluginContext.referenceClass`
2. The `onComposition` function signature matches what the IR call expects
3. `$changed` parameter might not exist on all composables — the fallback to `irInt(0)` handles this

---

## Post-MVP Stretch Goals (if time permits this weekend)

### Stretch 1: Add `irGetObject` import
The transformer uses `irGetObject` which may need an explicit import from `org.jetbrains.kotlin.ir.builders`. Verify the import resolves.

### Stretch 2: Add a desktop target to sample
Add a JVM Desktop `main()` entry point to validate KMP works beyond Android.

### Stretch 3: Snapshot/Check Gradle tasks
Add `reboundSnapshot` and `reboundCheck` tasks to the Gradle plugin — the CI baseline story.

---

## Known Issues to Address Post-Weekend

1. **Thread safety**: `mutableMapOf` is not thread-safe. Replace with `ConcurrentHashMap` on JVM, platform-appropriate concurrent map on Native.
2. **Plugin ordering**: Need to verify the Rebound plugin runs after the Compose compiler. May need to set explicit ordering in the Gradle plugin registration.
3. **Budget classification**: All composables are currently `UNKNOWN`. IR analysis for SCREEN/LEAF/LIST_ITEM classification is Task 4 in the design.
4. **Package name**: `io.aldefy` is the final package namespace.
