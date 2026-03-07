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

apply(from = rootProject.file("gradle/publish-convention.gradle.kts"))

// Wire the java component into a publication for this JVM-only module
configure<PublishingExtension> {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "rebound-compiler"
        }
    }
}
