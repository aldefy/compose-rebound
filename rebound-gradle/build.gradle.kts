plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
    id("com.gradle.plugin-publish") version "1.3.1"
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin-api:${libs.versions.kotlin.get()}")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

gradlePlugin {
    website = "https://github.com/aldefy/compose-rebound"
    vcsUrl = "https://github.com/aldefy/compose-rebound"
    plugins {
        create("rebound") {
            id = "io.github.aldefy.rebound"
            displayName = "Rebound — Compose Recomposition Budget Monitor"
            description = "Kotlin compiler plugin that instruments @Composable functions with recomposition budget tracking. Auto-classifies composables, detects violations, reports via IDE/CLI."
            tags = listOf("kotlin", "compose", "jetpack-compose", "android", "performance", "recomposition", "kmp")
            implementationClass = "io.aldefy.rebound.gradle.ReboundGradlePlugin"
        }
    }
}

apply(from = file("../gradle/publish-convention.gradle.kts"))
