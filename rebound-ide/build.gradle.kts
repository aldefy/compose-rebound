plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "io.aldefy.rebound"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        androidStudio("2024.2.1.3")
        bundledPlugin("org.jetbrains.kotlin")
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
        description = "Live recomposition budget monitor for Jetpack Compose. Parses Rebound logcat output and displays composable recomposition rates, budgets, and violations in a tool window."
        vendor {
            name = "aldefy"
            url = "https://github.com/nickaldenfy/compose-rebound"
        }
        ideaVersion {
            sinceBuild = "242"
            untilBuild = provider { null }
        }
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
