package io.aldefy.rebound.ide

/**
 * Parses ReboundTracker JSON snapshots into ComposableEntry list for the table.
 * Handles both full snapshot format (toJson) and compact summary format.
 */
object SnapshotParser {

    private val ENTRY_REGEX = """"([^"]+)"\s*:\s*\{([^}]+)\}""".toRegex()

    fun parse(json: String): List<ComposableEntry> {
        if (json.isBlank()) return emptyList()

        // Detect format: full snapshot has "composables", summary has array
        return if (json.contains("\"composables\"")) {
            parseFullSnapshot(json)
        } else {
            parseSummary(json)
        }
    }

    private fun parseFullSnapshot(json: String): List<ComposableEntry> {
        val block = json.substringAfter("\"composables\"")
            .substringAfter("{").substringBeforeLast("}")

        return ENTRY_REGEX.findAll(block).map { match ->
            val fqn = match.groupValues[1]
            val body = match.groupValues[2]

            val rate = extractInt(body, "currentRate")
            val budget = extractInt(body, "budgetPerSecond")
            val budgetClass = extractString(body, "budgetClass")
            val totalCount = extractLong(body, "totalCompositions").toInt()
            val skipRate = extractFloat(body, "skipRate")
            val forcedCount = extractLong(body, "forcedCount")
            val paramDrivenCount = extractLong(body, "paramDrivenCount")
            val peak = extractInt(body, "peakRate")
            val lastInvalidation = extractString(body, "lastInvalidation")

            val skipPct = (skipRate * 1000).toInt() / 10.0

            ComposableEntry(
                name = fqn,
                rate = rate,
                budget = budget,
                budgetClass = budgetClass,
                totalCount = totalCount,
                isViolation = rate > budget,
                isForced = forcedCount > paramDrivenCount && forcedCount > 0,
                changedParams = if (paramDrivenCount > 0) "param-driven: $paramDrivenCount" else "",
                skipPercent = skipPct,
                peakRate = peak,
                invalidationReason = lastInvalidation
            )
        }.toList()
    }

    private fun parseSummary(json: String): List<ComposableEntry> {
        // Summary format: {"composables":[{...},...]}
        val arrayBlock = json.substringAfter("[").substringBeforeLast("]")
        val entryRegex = """\{([^}]+)\}""".toRegex()

        return entryRegex.findAll(arrayBlock).map { match ->
            val body = match.groupValues[1]

            ComposableEntry(
                name = extractString(body, "fqn").ifEmpty { extractString(body, "name") },
                rate = extractInt(body, "rate"),
                budget = extractInt(body, "budget"),
                budgetClass = extractString(body, "class"),
                totalCount = extractInt(body, "total"),
                isViolation = body.contains("\"over\":true"),
                skipPercent = extractFloat(body, "skip").toDouble(),
                peakRate = extractInt(body, "peak")
            )
        }.toList()
    }

    private fun extractString(body: String, field: String): String =
        """"$field"\s*:\s*"([^"]+)"""".toRegex().find(body)?.groupValues?.get(1) ?: ""

    private fun extractInt(body: String, field: String): Int =
        """"$field"\s*:\s*(\d+)""".toRegex().find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun extractLong(body: String, field: String): Long =
        """"$field"\s*:\s*(\d+)""".toRegex().find(body)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

    private fun extractFloat(body: String, field: String): Float =
        """"$field"\s*:\s*([\d.]+)""".toRegex().find(body)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
}
