plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

group = "io.aldefy.rebound"
version = project.property("rebound_version") as String

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        androidStudio("2024.3.1.14")
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("Git4Idea")
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }
}


intellijPlatform {
    pluginConfiguration {
        id = "io.aldefy.rebound.ide"
        name = "Rebound"
        version = project.version.toString()
        description = """
            <h2>Rebound — Compose Recomposition Budget Monitor</h2>
            <p>Catch runaway recompositions before they ship. Rebound assigns each <code>@Composable</code> a recomposition rate budget based on its role (Screen, Container, Interactive, List Item, Animated, Leaf) and alerts when the budget is exceeded.</p>

            <h3>5-Tab Performance Cockpit</h3>
            <ul>
                <li><b>Monitor</b> — Live composable tree with sparkline rate history per node. Scrolling event log shows recomposition events, violations, and state changes in real time.</li>
                <li><b>Hot Spots</b> — Sortable flat table of all tracked composables ranked by severity (OVER → NEAR → OK). Summary card shows violation/warning/OK counts. Click any row to jump to source.</li>
                <li><b>Timeline</b> — Composable × time heatmap. Each cell colored green/yellow/red based on budget status. Scroll back up to 60 minutes. Correlate recomposition spikes with user interactions.</li>
                <li><b>Stability</b> — Parameter stability matrix showing SAME/DIFFERENT/STATIC/UNCERTAIN per parameter. Cascade impact tree visualizes how unstable parameters propagate recompositions through the hierarchy.</li>
                <li><b>History</b> — Saved sessions with VCS-tagged branch name and commit hash. Side-by-side comparison for before/after regression analysis.</li>
            </ul>

            <h3>Editor Integration</h3>
            <ul>
                <li><b>Gutter icons</b> — Red, yellow, or green dots next to <code>@Composable</code> declarations reflecting live budget status.</li>
                <li><b>CodeVision</b> — Inline hints: <code>12/s | budget: 8/s | OVER | skip: 45%</code></li>
                <li><b>Status bar</b> — Persistent widget: <code>Rebound: 45 composables | 3 violations</code></li>
            </ul>

            <p>Works with Kotlin 2.0.x–2.3.x, Android and iOS (Compose Multiplatform). Zero config — just apply the Gradle plugin.</p>
        """.trimIndent()
        vendor {
            name = "aldefy"
            url = "https://github.com/aldefy/compose-rebound"
        }
        ideaVersion {
            sinceBuild = "242"
            untilBuild = provider { null }
        }
    }

    publishing {
        token = providers.environmentVariable("JETBRAINS_MARKETPLACE_TOKEN")
        channels = listOf(
            if (project.version.toString().contains("beta")) "beta" else "default"
        )
    }
}

tasks {
    buildSearchableOptions {
        enabled = false
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        incremental = false
    }
}
