package io.aldefy.rebound.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

class ReboundGradlePlugin : Plugin<Project> {
    override fun apply(target: Project) {
        val extension = target.extensions.create("rebound", ReboundExtension::class.java)

        // Register CLI tasks (always available, even if plugin is disabled)
        target.tasks.register("reboundSnapshot", ReboundCliTask::class.java) {
            it.description = "Fetch full JSON metrics from a running Rebound-instrumented app"
            it.command = "snapshot"
        }
        target.tasks.register("reboundSummary", ReboundCliTask::class.java) {
            it.description = "Fetch top violators summary from a running Rebound-instrumented app"
            it.command = "summary"
        }
        target.tasks.register("reboundPing", ReboundCliTask::class.java) {
            it.description = "Health check a running Rebound-instrumented app"
            it.command = "ping"
        }
        target.tasks.register("reboundTelemetry", ReboundCliTask::class.java) {
            it.description = "Fetch anonymized aggregate stats by budget class"
            it.command = "telemetry"
        }

        target.afterEvaluate {
            if (!extension.enabled.get()) return@afterEvaluate

            // Detect the project's Kotlin version to select the right compiler artifact
            val kotlinVersion = resolveKotlinVersion(target)
            val compilerArtifactId = selectCompilerArtifact(kotlinVersion)

            // Add compiler plugin JAR to all Kotlin compiler plugin classpath configurations.
            // Try local project dependency first (composite build), fall back to Maven coordinates.
            val compilerDep = try {
                val projectPath = when (compilerArtifactId) {
                    "rebound-compiler" -> ":rebound-compiler"
                    "rebound-compiler-kotlin-2.3" -> ":rebound-compiler-k2-3"
                    else -> ":rebound-compiler-k2"
                }
                target.dependencies.project(mapOf("path" to projectPath))
            } catch (_: Exception) {
                target.dependencies.create("io.aldefy.rebound:$compilerArtifactId:0.1.0")
            }

            val configs = target.configurations
                .filter { it.name.contains("kotlinCompilerPluginClasspath", ignoreCase = true) }

            val filteredConfigs = if (extension.debugOnly.get()) {
                // Android: only debug configs. iOS/native: always include (no debug/release split
                // at compiler plugin classpath level). JVM: include if no Android debug configs exist.
                val debugConfigs = configs.filter { it.name.contains("debug", ignoreCase = true) }
                val nativeConfigs = configs.filter { config ->
                    val name = config.name.lowercase()
                    name.contains("ios") || name.contains("native") ||
                        name.contains("macos") || name.contains("linux") || name.contains("mingw")
                }
                val combined = (debugConfigs + nativeConfigs).distinct()
                if (combined.isEmpty()) configs else combined
            } else {
                configs
            }

            filteredConfigs.forEach { config ->
                target.dependencies.add(config.name, compilerDep)
            }

            // Auto-add runtime dependency
            // For KMP projects, add to commonMainImplementation; for Android-only, use debugImplementation
            val runtimeDep = target.dependencies.create("io.aldefy.rebound:rebound-runtime:0.1.0")
            val isKmp = target.extensions.findByName("kotlin")?.javaClass?.name
                ?.contains("KotlinMultiplatformExtension") == true
            val runtimeConfig = if (isKmp) {
                // KMP: add to commonMainImplementation so Gradle variant matching resolves per-platform
                "commonMainImplementation"
            } else if (extension.debugOnly.get()) {
                listOf("debugImplementation", "implementation").firstOrNull {
                    target.configurations.findByName(it) != null
                } ?: "implementation"
            } else {
                "implementation"
            }
            target.dependencies.add(runtimeConfig, runtimeDep)

            // Pass plugin options to the compiler
            target.tasks.withType(KotlinCompilationTask::class.java).configureEach {
                it.compilerOptions.freeCompilerArgs.addAll(
                    "-P", "plugin:io.aldefy.rebound:enabled=${extension.enabled.get()}"
                )
            }
        }
    }

    private fun resolveKotlinVersion(project: Project): String {
        // Try to get Kotlin version from the applied Kotlin plugin
        return try {
            val kotlinExtension = project.extensions.findByName("kotlin")
            if (kotlinExtension != null) {
                val method = kotlinExtension.javaClass.getMethod("getCoreLibrariesVersion")
                method.invoke(kotlinExtension)?.toString() ?: "2.1.0"
            } else {
                "2.1.0"
            }
        } catch (_: Exception) {
            "2.1.0"
        }
    }

    private fun selectCompilerArtifact(kotlinVersion: String): String {
        val parts = kotlinVersion.split(".")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 2
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 1

        return when {
            major >= 2 && minor >= 3 -> "rebound-compiler-kotlin-2.3"
            major >= 2 && minor >= 2 -> "rebound-compiler-kotlin-2.2"
            else -> "rebound-compiler"
        }
    }
}
