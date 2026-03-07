// Shared Maven Central publishing configuration for all Rebound modules
// Usage: apply(from = rootProject.file("gradle/publish-convention.gradle.kts"))

apply(plugin = "maven-publish")
apply(plugin = "signing")

val rebound_version: String by project
val rebound_group: String by project

group = rebound_group
version = rebound_version

configure<PublishingExtension> {
    repositories {
        maven {
            name = "localStaging"
            url = uri(layout.buildDirectory.dir("staging-deploy"))
        }
    }

    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Rebound")
            description.set("Contextual recomposition budget tracking for Compose Multiplatform")
            url.set("https://github.com/nickalert/compose-rebound")

            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }

            developers {
                developer {
                    id.set("aldefy")
                    name.set("Adit Lal")
                    email.set("nickalert@gmail.com")
                }
            }

            scm {
                connection.set("scm:git:git://github.com/nickalert/compose-rebound.git")
                developerConnection.set("scm:git:ssh://github.com/nickalert/compose-rebound.git")
                url.set("https://github.com/nickalert/compose-rebound")
            }
        }
    }
}

val isRelease = !version.toString().endsWith("-SNAPSHOT")

configure<SigningExtension> {
    useGpgCmd()
    sign(the<PublishingExtension>().publications)
    // Only require signing for non-SNAPSHOT releases (CI / Maven Central uploads)
    isRequired = isRelease
}

// Disable signing tasks when not required (local dev / SNAPSHOT builds)
tasks.withType<Sign>().configureEach {
    onlyIf { isRelease }
}
