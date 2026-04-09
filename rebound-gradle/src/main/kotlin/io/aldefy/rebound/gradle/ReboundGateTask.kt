package io.aldefy.rebound.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.TaskAction
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date

/**
 * CI budget gate: capture a live snapshot, diff against a committed baseline, fail on regression.
 *
 * Usage:
 *   ./gradlew reboundGate -Pbaseline=.rebound/baseline.json
 *   ./gradlew reboundGate -Pbaseline=.rebound/baseline.json -Pthreshold=10
 *   ./gradlew reboundGate -Pbaseline=.rebound/baseline.json -Pport=19999
 *
 * The task:
 *   1. Connects to the running app (adb forward auto-discovery)
 *   2. Captures a snapshot to .rebound/gate-<timestamp>.json
 *   3. Diffs against the baseline
 *   4. Fails the build if any composable regressed beyond the threshold
 */
abstract class ReboundGateTask : DefaultTask() {

    init {
        group = "rebound"
    }

    @TaskAction
    fun execute() {
        val baselinePath = project.findProperty("baseline")?.toString()
        if (baselinePath.isNullOrBlank()) {
            throw GradleException(
                "Usage: ./gradlew reboundGate -Pbaseline=.rebound/baseline.json\n" +
                "  Create a baseline first: ./gradlew reboundSave -Ptag=baseline"
            )
        }

        val baselineFile = resolveFile(baselinePath)
        if (!baselineFile.exists()) {
            throw GradleException(
                "Baseline not found: $baselinePath\n" +
                "  Create one: ./gradlew reboundSave -Ptag=baseline"
            )
        }

        val threshold = project.findProperty("threshold")?.toString()?.toIntOrNull() ?: 20
        val port = project.findProperty("port")?.toString()?.toIntOrNull() ?: PORT

        // Step 1: Connect
        if (!connect(port)) {
            throw GradleException(
                "Rebound: no connection on port $port — is the app running with Rebound enabled?\n" +
                "  Android: connect device/emulator, launch debug build\n" +
                "  iOS sim: launch app in simulator"
            )
        }

        // Step 2: Capture snapshot
        val json = sendCommand("snapshot", port)
        if (json.isNullOrBlank()) {
            throw GradleException("Rebound: empty snapshot response. Is the app running?")
        }

        val outDir = File(project.projectDir, ".rebound")
        outDir.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
        val snapshotFile = File(outDir, "gate-$ts.json")
        snapshotFile.writeText(json)

        val count = "\"budgetClass\"".toRegex().findAll(json).count()
        println("Captured $count composables to ${snapshotFile.relativeTo(project.projectDir)}")

        // Step 3: Diff
        val baselineSnap = parseSnapshot(baselineFile.readText())
        val currentSnap = parseSnapshot(json)

        if (baselineSnap.isEmpty()) throw GradleException("No composables found in baseline: $baselinePath")
        if (currentSnap.isEmpty()) throw GradleException("No composables found in captured snapshot")

        val allKeys = (baselineSnap.keys + currentSnap.keys).sorted().distinct()

        val improved = mutableListOf<DiffEntry>()
        val regressed = mutableListOf<DiffEntry>()
        val newComposables = mutableListOf<Pair<String, Map<String, Any>>>()
        val removed = mutableListOf<String>()

        for (key in allKeys) {
            val b = baselineSnap[key]
            val a = currentSnap[key]
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

            val entry = DiffEntry(short, budgetClass, budget, bPeak, aPeak, pct,
                bSkip, aSkip, bForced, aForced)

            if (pct < -threshold) improved.add(entry)
            else if (pct > threshold) regressed.add(entry)
        }

        improved.sortBy { it.peakPct }
        regressed.sortByDescending { it.peakPct }

        // Step 4: Report
        val sb = StringBuilder()
        sb.appendLine()
        sb.appendLine("=== Rebound Gate: ${baselineFile.name} -> gate-$ts.json (threshold: $threshold%) ===")
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

        val totalAfter = currentSnap.size
        val nRegressed = regressed.size
        val nImproved = improved.size
        val nUnchanged = totalAfter - nRegressed - nImproved - newComposables.size

        sb.appendLine("Summary: $totalAfter composables ($nImproved improved, $nRegressed regressed, $nUnchanged unchanged, ${newComposables.size} new, ${removed.size} removed)")

        if (nRegressed > 0) {
            sb.appendLine("Result: FAIL")
            println(sb.toString())
            throw GradleException("Rebound: $nRegressed regression(s) exceeded $threshold% threshold — build failed")
        } else {
            sb.appendLine("Result: PASS")
            println(sb.toString())
        }
    }

    private fun resolveFile(path: String): File {
        val f = File(path)
        return if (f.isAbsolute) f else File(project.projectDir, path)
    }

    private fun formatDelta(v: Float): String {
        val s = "%.1f".format(v)
        return if (v >= 0) "+$s" else s
    }

    private fun connect(port: Int): Boolean {
        if (sendCommand("ping", port)?.trim() == "pong") return true
        return setupAdbForward(port)
    }

    private fun setupAdbForward(port: Int): Boolean {
        if (tryAdbForward("rebound", port)) return true
        val unixSockets = adb("shell", "cat /proc/net/unix 2>/dev/null") ?: return false
        val pattern = Regex("@(rebound_\\d+)")
        for (match in pattern.findAll(unixSockets)) {
            if (tryAdbForward(match.groupValues[1], port)) return true
        }
        return false
    }

    private fun tryAdbForward(socketName: String, port: Int): Boolean {
        adb("forward", "tcp:$port", "localabstract:$socketName") ?: return false
        return sendCommand("ping", port)?.trim() == "pong"
    }

    private fun sendCommand(cmd: String, port: Int): String? {
        return try {
            Socket("localhost", port).use { socket ->
                socket.soTimeout = 5000
                val writer = OutputStreamWriter(socket.getOutputStream())
                writer.write("$cmd\n")
                writer.flush()
                Thread.sleep(500)
                socket.shutdownOutput()
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                reader.readText()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun adb(vararg args: String): String? {
        return try {
            val process = ProcessBuilder(listOf(findAdb()) + args.toList())
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            if (process.exitValue() == 0) output else null
        } catch (_: Exception) {
            null
        }
    }

    private fun findAdb(): String {
        val androidHome = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        if (androidHome != null) {
            val adb = File(androidHome, "platform-tools/adb")
            if (adb.exists()) return adb.absolutePath
        }
        val userAdb = File(System.getProperty("user.home"), "Library/Android/sdk/platform-tools/adb")
        if (userAdb.exists()) return userAdb.absolutePath
        return "adb"
    }

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
            fields["peakRate"] = extractInt("peakRate")
            fields["skipRate"] = extractFloat("skipRate")
            fields["forcedCount"] = extractLong("forcedCount")

            result[key] = fields
        }
        return result
    }

    private data class DiffEntry(
        val name: String,
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

    companion object {
        const val PORT = 18462
    }
}
