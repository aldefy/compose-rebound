plugins {
    kotlin("jvm") version "2.2.20"
}

val reboundVersion = "0.1.0"
val reboundGroup = "io.github.aldefy.rebound"

group = reboundGroup
version = reboundVersion

dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.20")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Copy SPI service files from rebound-compiler
sourceSets.main {
    resources.srcDir(rootProject.file("../rebound-compiler/src/main/resources"))
}

apply(from = rootProject.file("../gradle/publish-convention.gradle.kts"))

configure<PublishingExtension> {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = reboundGroup
            artifactId = "rebound-compiler-kotlin-2.2"
            version = reboundVersion
        }
    }
}
