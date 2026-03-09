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
        maven {
            name = "sonatype"
            url = uri(
                if (version.toString().endsWith("-SNAPSHOT"))
                    "https://s01.oss.sonatype.org/content/repositories/snapshots/"
                else
                    "https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/"
            )
            credentials {
                username = providers.environmentVariable("SONATYPE_USERNAME").orNull
                    ?: providers.gradleProperty("sonatypeUsername").orNull ?: ""
                password = providers.environmentVariable("SONATYPE_PASSWORD").orNull
                    ?: providers.gradleProperty("sonatypePassword").orNull ?: ""
            }
        }
    }

    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Rebound")
            description.set("Contextual recomposition budget tracking for Compose Multiplatform")
            url.set("https://github.com/aldefy/compose-rebound")

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
                    email.set("aditlal@gmail.com")
                }
            }

            scm {
                connection.set("scm:git:git://github.com/aldefy/compose-rebound.git")
                developerConnection.set("scm:git:ssh://github.com/aldefy/compose-rebound.git")
                url.set("https://github.com/aldefy/compose-rebound")
            }
        }
    }
}

configure<SigningExtension> {
    useGpgCmd()
    sign(the<PublishingExtension>().publications)
}

// Only sign when GPG key is explicitly provided: -Psigning.gnupg.keyName=8EC63504
tasks.withType<Sign>().configureEach {
    onlyIf { project.hasProperty("signing.gnupg.keyName") }
}
