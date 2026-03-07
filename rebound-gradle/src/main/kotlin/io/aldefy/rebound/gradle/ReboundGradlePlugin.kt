package io.aldefy.rebound.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

class ReboundGradlePlugin : Plugin<Project> {
    override fun apply(target: Project) {
        val extension = target.extensions.create("rebound", ReboundExtension::class.java)

        target.afterEvaluate {
            if (!extension.enabled.get()) return@afterEvaluate

            // Detect the project's Kotlin version to select the right compiler artifact
            val kotlinVersion = resolveKotlinVersion(target)
            val compilerArtifactId = selectCompilerArtifact(kotlinVersion)

            // Add compiler plugin JAR to all Kotlin compiler plugin classpath configurations.
            // Try local project dependency first (composite build), fall back to Maven coordinates.
            val compilerDep = try {
                val projectPath = if (compilerArtifactId == "rebound-compiler") {
                    ":rebound-compiler"
                } else {
                    ":rebound-compiler-k2"
                }
                target.dependencies.project(mapOf("path" to projectPath))
            } catch (_: Exception) {
                target.dependencies.create("io.aldefy.rebound:$compilerArtifactId:0.1.0-SNAPSHOT")
            }

            val configs = target.configurations
                .filter { it.name.contains("kotlinCompilerPluginClasspath", ignoreCase = true) }

            val filteredConfigs = if (extension.debugOnly.get()) {
                val debugConfigs = configs.filter { it.name.contains("debug", ignoreCase = true) }
                if (debugConfigs.isEmpty()) configs else debugConfigs // fallback for non-Android (e.g. JVM-only)
            } else {
                configs
            }

            filteredConfigs.forEach { config ->
                target.dependencies.add(config.name, compilerDep)
            }

            // Auto-add runtime dependency (debug-only when debugOnly=true)
            val runtimeDep = target.dependencies.create("io.aldefy.rebound:rebound-runtime:0.1.0-SNAPSHOT")
            val runtimeConfig = if (extension.debugOnly.get()) {
                // Try debug-scoped configurations, fall back to implementation
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

        // Kotlin 2.2+ has relocated IR API classes
        return if (major >= 2 && minor >= 2) {
            "rebound-compiler-kotlin-2.2"
        } else {
            "rebound-compiler"
        }
    }
}
