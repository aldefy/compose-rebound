package io.aldefy.rebound.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Compares two Rebound snapshot JSON files and reports regressions/improvements.
 * No device connection needed — works entirely offline.
 *
 * Usage:
 *   ./gradlew reboundDiff -Pbefore=.rebound/before.json -Pafter=.rebound/after.json
 *   ./gradlew reboundDiff -Pbefore=.rebound/before.json -Pafter=.rebound/after.json -Pthreshold=10
 */
abstract class ReboundDiffTask : DefaultTask() {

    init {
        group = "rebound"
    }

    @TaskAction
    fun execute() {
        val before = project.findProperty("before")?.toString() ?: ""
        val after = project.findProperty("after")?.toString() ?: ""
        val threshold = project.findProperty("threshold")?.toString()?.toIntOrNull() ?: 20

        if (before.isEmpty() || after.isEmpty()) {
            logger.error("Usage: ./gradlew reboundDiff -Pbefore=<file> -Pafter=<file> [-Pthreshold=20]")
            return
        }

        val beforeFile = resolveFile(before)
        val afterFile = resolveFile(after)

        if (!beforeFile.exists()) { logger.error("File not found: $before"); return }
        if (!afterFile.exists()) { logger.error("File not found: $after"); return }

        val beforeSnap = parseSnapshot(beforeFile.readText())
        val afterSnap = parseSnapshot(afterFile.readText())

        if (beforeSnap.isEmpty()) { logger.error("No composables found in $before"); return }
        if (afterSnap.isEmpty()) { logger.error("No composables found in $after"); return }

        val allKeys = (beforeSnap.keys + afterSnap.keys).sorted().distinct()

        val improved = mutableListOf<DiffEntry>()
        val regressed = mutableListOf<DiffEntry>()
        val newComposables = mutableListOf<Pair<String, Map<String, Any>>>()
        val removed = mutableListOf<String>()

        for (key in allKeys) {
            val b = beforeSnap[key]
            val a = afterSnap[key]
            val short = if ("." in key) key.substringAfterLast(".") else key

            if (b != null && a == null) { removed.add(short); continue }
            if (a != null && b == null) { newComposables.add(short to a); continue }
            if (b == null || a == null) continue

            val bPeak = (b["peakRate"] as? Number)?.toInt() ?: 0
            val aPeak = (a["peakRate"] as? Number)?.toInt() ?: 0
            val bSkip = (b["skipRate"] as? Number)?.toFloat() ?: 0f
            val aSkip = (a["skipRate"] as? Number)?.toFloat() ?: 0f
            val bForced = (b["forcedCount"] as? Number)?.toLong() ?: 0
            val aForced = (a["forcedCount"] as? Number)?.toLong() ?: 0
            val budgetClass = (a["budgetClass"] as? String) ?: "?"
            val budget = (a["budgetPerSecond"] as? Number)?.toInt() ?: 0

            if (bPeak == 0 && aPeak == 0) continue

            val pct = if (bPeak > 0) ((aPeak - bPeak) * 100) / bPeak else if (aPeak > 0) 100 else 0

            val entry = DiffEntry(short, key, budgetClass, budget, bPeak, aPeak, pct,
                bSkip, aSkip, bForced, aForced)

            if (pct < -threshold) improved.add(entry)
            else if (pct > threshold) regressed.add(entry)
        }

        improved.sortBy { it.peakPct }
        regressed.sortByDescending { it.peakPct }

        val sb = StringBuilder()
        sb.appendLine("=== Rebound Diff: ${beforeFile.name} -> ${afterFile.name} (threshold: $threshold%) ===")
        sb.appendLine()

        if (regressed.isNotEmpty()) {
            sb.appendLine("REGRESSIONS (${regressed.size}):")
            for (e in regressed) {
                val over = if (e.afterPeak > e.budget) " !!OVER!!" else ""
                sb.appendLine("  ${e.name} [${e.budgetClass}]")
                sb.appendLine("    peak: ${e.beforePeak}/s -> ${e.afterPeak}/s (+${e.peakPct}%) budget=${e.budget}/s$over")
                sb.appendLine("    skip: ${"%.1f".format(e.beforeSkip * 100)}% -> ${"%.1f".format(e.afterSkip * 100)}% (${formatDelta((e.afterSkip - e.beforeSkip) * 100)}%)")
                sb.appendLine("    forced: ${e.beforeForced} -> ${e.afterForced}")
            }
            sb.appendLine()
        }

        if (improved.isNotEmpty()) {
            sb.appendLine("IMPROVED (${improved.size}):")
            for (e in improved) {
                sb.appendLine("  ${e.name} [${e.budgetClass}]")
                sb.appendLine("    peak: ${e.beforePeak}/s -> ${e.afterPeak}/s (${e.peakPct}%)")
                sb.appendLine("    skip: ${"%.1f".format(e.beforeSkip * 100)}% -> ${"%.1f".format(e.afterSkip * 100)}% (${formatDelta((e.afterSkip - e.beforeSkip) * 100)}%)")
            }
            sb.appendLine()
        }

        if (newComposables.isNotEmpty()) {
            sb.appendLine("NEW (${newComposables.size}):")
            for ((name, a) in newComposables) {
                sb.appendLine("  $name [${a["budgetClass"] ?: "?"}] peak=${(a["peakRate"] as? Number)?.toInt() ?: 0}/s")
            }
            sb.appendLine()
        }

        if (removed.isNotEmpty()) {
            sb.appendLine("REMOVED (${removed.size}):")
            for (name in removed) sb.appendLine("  $name")
            sb.appendLine()
        }

        val totalAfter = afterSnap.size
        val nRegressed = regressed.size
        val nImproved = improved.size
        val nUnchanged = totalAfter - nRegressed - nImproved - newComposables.size
        sb.appendLine("Summary: $totalAfter composables ($nImproved improved, $nRegressed regressed, $nUnchanged unchanged, ${newComposables.size} new, ${removed.size} removed)")

        if (nRegressed == 0 && nImproved > 0) {
            sb.appendLine("Result: PASS")
        } else if (nRegressed > 0) {
            sb.appendLine("Result: FAIL — regressions detected")
        } else {
            sb.appendLine("Result: PASS — no significant changes")
        }

        println(sb.toString())
    }

    private fun resolveFile(path: String): File {
        val f = File(path)
        return if (f.isAbsolute) f else File(project.projectDir, path)
    }

    private fun formatDelta(v: Float): String {
        val s = "%.1f".format(v)
        return if (v >= 0) "+$s" else s
    }

    /** Minimal JSON parser for Rebound snapshot format — no external dependencies. */
    private fun parseSnapshot(json: String): Map<String, Map<String, Any>> {
        val result = mutableMapOf<String, Map<String, Any>>()
        val composablesBlock = json.substringAfter("\"composables\"")
            .substringAfter("{").substringBeforeLast("}")
        if (composablesBlock.isBlank()) return result

        val entryRegex = "\"([^\"]+)\"\\s*:\\s*\\{([^}]+)\\}".toRegex()
        for (match in entryRegex.findAll(composablesBlock)) {
            val key = match.groupValues[1]
            val body = match.groupValues[2]
            val fields = mutableMapOf<String, Any>()

            fun extractString(field: String): String =
                "\"$field\"\\s*:\\s*\"([^\"]*)\"".toRegex().find(body)?.groupValues?.get(1) ?: ""
            fun extractInt(field: String): Int =
                "\"$field\"\\s*:\\s*(\\d+)".toRegex().find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            fun extractLong(field: String): Long =
                "\"$field\"\\s*:\\s*(\\d+)".toRegex().find(body)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            fun extractFloat(field: String): Float =
                "\"$field\"\\s*:\\s*([\\d.]+)".toRegex().find(body)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f

            fields["budgetClass"] = extractString("budgetClass")
            fields["budgetPerSecond"] = extractInt("budgetPerSecond")
            fields["totalCompositions"] = extractLong("totalCompositions")
            fields["peakRate"] = extractInt("peakRate")
            fields["currentRate"] = extractInt("currentRate")
            fields["totalEnters"] = extractLong("totalEnters")
            fields["skipCount"] = extractLong("skipCount")
            fields["skipRate"] = extractFloat("skipRate")
            fields["forcedCount"] = extractLong("forcedCount")
            fields["paramDrivenCount"] = extractLong("paramDrivenCount")

            result[key] = fields
        }
        return result
    }

    private data class DiffEntry(
        val name: String,
        val fqn: String,
        val budgetClass: String,
        val budget: Int,
        val beforePeak: Int,
        val afterPeak: Int,
        val peakPct: Int,
        val beforeSkip: Float,
        val afterSkip: Float,
        val beforeForced: Long,
        val afterForced: Long,
    )
}
