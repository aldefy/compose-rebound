package io.aldefy.rebound.ide.data

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

data class SessionSummary(
    val file: File,
    val timestamp: LocalDateTime,
    val branch: String?,
    val commitHash: String?
)

object SessionPersistence {

    private val LOG = Logger.getInstance(SessionPersistence::class.java)
    private const val SESSION_DIR = ".rebound/sessions"
    private val FILENAME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmmss")

    // Pattern: 2026-03-08T143022_main_abc1234.json.gz
    private val FILENAME_REGEX = Regex(
        """^(\d{4}-\d{2}-\d{2}T\d{6})(?:_([^_]+))?(?:_([^.]+))?\.json\.gz$"""
    )

    fun save(projectDir: File, sessionData: SessionData) {
        val dir = File(projectDir, SESSION_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }

        val timestamp = LocalDateTime.now()
        val timePart = FILENAME_FORMAT.format(timestamp)
        val branchPart = sessionData.branch?.replace(Regex("[^a-zA-Z0-9._-]"), "-")
        val commitPart = sessionData.commitHash?.take(7)

        val filename = buildString {
            append(timePart)
            if (!branchPart.isNullOrBlank()) append("_$branchPart")
            if (!commitPart.isNullOrBlank()) append("_$commitPart")
            append(".json.gz")
        }

        val file = File(dir, filename)
        try {
            val json = sessionData.toJson()
            FileOutputStream(file).use { fos ->
                GZIPOutputStream(fos).use { gzip ->
                    gzip.write(json.toByteArray(Charsets.UTF_8))
                }
            }
            LOG.info("Saved session to ${file.absolutePath}")
        } catch (e: Exception) {
            LOG.error("Failed to save session to ${file.absolutePath}", e)
        }

        enforceMaxSessions(dir)
    }

    fun loadAll(projectDir: File): List<SessionSummary> {
        val dir = File(projectDir, SESSION_DIR)
        if (!dir.exists() || !dir.isDirectory) return emptyList()

        return dir.listFiles()
            ?.filter { it.name.endsWith(".json.gz") }
            ?.mapNotNull { file -> parseFilename(file) }
            ?.sortedByDescending { it.timestamp }
            ?: emptyList()
    }

    fun load(file: File): SessionData {
        val json = FileInputStream(file).use { fis ->
            GZIPInputStream(fis).use { gzip ->
                gzip.bufferedReader(Charsets.UTF_8).readText()
            }
        }
        return SessionData.fromJson(json)
    }

    private fun parseFilename(file: File): SessionSummary? {
        val match = FILENAME_REGEX.matchEntire(file.name) ?: return null
        val (timePart, branch, commit) = match.destructured
        val timestamp = try {
            LocalDateTime.parse(timePart, FILENAME_FORMAT)
        } catch (e: Exception) {
            return null
        }
        return SessionSummary(
            file = file,
            timestamp = timestamp,
            branch = branch.ifBlank { null },
            commitHash = commit.ifBlank { null }
        )
    }

    private fun enforceMaxSessions(dir: File) {
        val maxSessions = try {
            ReboundSettings.getInstance().state.maxStoredSessions
        } catch (e: Exception) {
            20
        }

        val files = dir.listFiles()
            ?.filter { it.name.endsWith(".json.gz") }
            ?.sortedByDescending { it.name }
            ?: return

        if (files.size > maxSessions) {
            files.drop(maxSessions).forEach { file ->
                try {
                    file.delete()
                    LOG.info("Evicted old session: ${file.name}")
                } catch (e: Exception) {
                    LOG.warn("Failed to delete old session: ${file.name}", e)
                }
            }
        }
    }
}
