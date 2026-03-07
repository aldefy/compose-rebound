package io.aldefy.rebound

/**
 * A point-in-time snapshot of all tracked composable metrics.
 * Can be serialized to JSON for baseline comparison.
 */
data class ReboundSnapshot(
    val composables: Map<String, ComposableSnapshot>
) {
    data class ComposableSnapshot(
        val budgetClass: String,
        val budgetPerSecond: Int,
        val totalCompositions: Long,
        val peakRate: Int,
        val currentRate: Int,
        val totalEnters: Long = 0,
        val skipCount: Long = 0,
        val skipRate: Float = 0f,
        val forcedCount: Long = 0,
        val paramDrivenCount: Long = 0,
        val lastInvalidation: String = ""
    )

    /** Export as JSON string (no external dependencies) */
    fun toJson(): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"composables\": {\n")
        val entries = composables.entries.toList()
        entries.forEachIndexed { i, (key, snap) ->
            sb.append("    \"${escapeJson(key)}\": {\n")
            sb.append("      \"budgetClass\": \"${snap.budgetClass}\",\n")
            sb.append("      \"budgetPerSecond\": ${snap.budgetPerSecond},\n")
            sb.append("      \"totalCompositions\": ${snap.totalCompositions},\n")
            sb.append("      \"peakRate\": ${snap.peakRate},\n")
            sb.append("      \"currentRate\": ${snap.currentRate},\n")
            sb.append("      \"totalEnters\": ${snap.totalEnters},\n")
            sb.append("      \"skipCount\": ${snap.skipCount},\n")
            sb.append("      \"skipRate\": ${snap.skipRate},\n")
            sb.append("      \"forcedCount\": ${snap.forcedCount},\n")
            sb.append("      \"paramDrivenCount\": ${snap.paramDrivenCount},\n")
            sb.append("      \"lastInvalidation\": \"${escapeJson(snap.lastInvalidation)}\"\n")
            sb.append("    }")
            if (i < entries.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("  }\n")
        sb.append("}")
        return sb.toString()
    }

    companion object {
        /** Parse a JSON string back into a ReboundSnapshot */
        fun fromJson(json: String): ReboundSnapshot {
            val composables = mutableMapOf<String, ComposableSnapshot>()
            // Simple parser — find each composable entry between quotes
            val composablesBlock = json.substringAfter("\"composables\"")
                .substringAfter("{").substringBeforeLast("}")

            // Regex to find each key: { ... } block
            val entryRegex = "\"([^\"]+)\"\\s*:\\s*\\{([^}]+)\\}".toRegex()
            for (match in entryRegex.findAll(composablesBlock)) {
                val key = match.groupValues[1]
                val body = match.groupValues[2]

                fun extractString(field: String): String =
                    "\"$field\"\\s*:\\s*\"([^\"]+)\"".toRegex().find(body)?.groupValues?.get(1) ?: ""
                fun extractInt(field: String): Int =
                    "\"$field\"\\s*:\\s*(\\d+)".toRegex().find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                fun extractLong(field: String): Long =
                    "\"$field\"\\s*:\\s*(\\d+)".toRegex().find(body)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

                fun extractFloat(field: String): Float =
                    "\"$field\"\\s*:\\s*([\\d.]+)".toRegex().find(body)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f

                composables[key] = ComposableSnapshot(
                    budgetClass = extractString("budgetClass"),
                    budgetPerSecond = extractInt("budgetPerSecond"),
                    totalCompositions = extractLong("totalCompositions"),
                    peakRate = extractInt("peakRate"),
                    currentRate = extractInt("currentRate"),
                    totalEnters = extractLong("totalEnters"),
                    skipCount = extractLong("skipCount"),
                    skipRate = extractFloat("skipRate"),
                    forcedCount = extractLong("forcedCount"),
                    paramDrivenCount = extractLong("paramDrivenCount"),
                    lastInvalidation = extractString("lastInvalidation")
                )
            }
            return ReboundSnapshot(composables)
        }

        private fun escapeJson(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
