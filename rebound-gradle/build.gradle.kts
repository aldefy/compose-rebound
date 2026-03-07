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

apply(from = file("../gradle/publish-convention.gradle.kts"))
