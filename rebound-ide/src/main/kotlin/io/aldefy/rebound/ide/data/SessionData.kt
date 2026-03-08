package io.aldefy.rebound.ide.data

import io.aldefy.rebound.ide.ComposableEntry
import java.time.LocalTime

data class SessionData(
    val snapshots: List<TimestampedSnapshot>,
    val events: List<LogEvent>,
    val composableCount: Int,
    val violationCount: Int,
    val durationMs: Long,
    val branch: String?,
    val commitHash: String?
) {
    fun toJson(): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"composableCount\": $composableCount,\n")
        sb.append("  \"violationCount\": $violationCount,\n")
        sb.append("  \"durationMs\": $durationMs,\n")
        sb.append("  \"branch\": ${branch?.let { "\"${escapeJson(it)}\"" } ?: "null"},\n")
        sb.append("  \"commitHash\": ${commitHash?.let { "\"${escapeJson(it)}\"" } ?: "null"},\n")

        // Snapshots
        sb.append("  \"snapshots\": [\n")
        snapshots.forEachIndexed { i, snapshot ->
            sb.append("    {\n")
            sb.append("      \"timestampMs\": ${snapshot.timestampMs},\n")
            sb.append("      \"entries\": {\n")
            val entryList = snapshot.entries.entries.toList()
            entryList.forEachIndexed { j, (key, entry) ->
                sb.append("        \"${escapeJson(key)}\": ")
                appendComposableEntry(sb, entry)
                if (j < entryList.size - 1) sb.append(",")
                sb.append("\n")
            }
            sb.append("      }\n")
            sb.append("    }")
            if (i < snapshots.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("  ],\n")

        // Events
        sb.append("  \"events\": [\n")
        events.forEachIndexed { i, event ->
            sb.append("    {")
            sb.append("\"timestamp\": \"${event.timestamp}\", ")
            sb.append("\"level\": \"${event.level.name}\", ")
            sb.append("\"message\": \"${escapeJson(event.message)}\"")
            sb.append("}")
            if (i < events.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("  ]\n")

        sb.append("}")
        return sb.toString()
    }

    companion object {
        fun fromJson(json: String): SessionData {
            val root = JsonParser.parseObject(json)

            val composableCount = (root["composableCount"] as Number).toInt()
            val violationCount = (root["violationCount"] as Number).toInt()
            val durationMs = (root["durationMs"] as Number).toLong()
            val branch = root["branch"] as? String
            val commitHash = root["commitHash"] as? String

            @Suppress("UNCHECKED_CAST")
            val snapshotsList = root["snapshots"] as? List<Map<String, Any?>> ?: emptyList()
            val snapshots = snapshotsList.map { snapMap ->
                val timestampMs = (snapMap["timestampMs"] as Number).toLong()
                @Suppress("UNCHECKED_CAST")
                val entriesMap = snapMap["entries"] as? Map<String, Map<String, Any?>> ?: emptyMap()
                val entries = entriesMap.mapValues { (_, v) -> parseComposableEntry(v) }
                TimestampedSnapshot(timestampMs, entries)
            }

            @Suppress("UNCHECKED_CAST")
            val eventsList = root["events"] as? List<Map<String, Any?>> ?: emptyList()
            val events = eventsList.map { evMap ->
                val timestamp = LocalTime.parse(evMap["timestamp"] as String)
                val level = LogEvent.Level.valueOf(evMap["level"] as String)
                val message = evMap["message"] as String
                LogEvent(timestamp, level, message)
            }

            return SessionData(snapshots, events, composableCount, violationCount, durationMs, branch, commitHash)
        }

        private fun parseComposableEntry(map: Map<String, Any?>): ComposableEntry {
            return ComposableEntry(
                name = map["name"] as? String ?: "",
                rate = (map["rate"] as? Number)?.toInt() ?: 0,
                budget = (map["budget"] as? Number)?.toInt() ?: 0,
                budgetClass = map["budgetClass"] as? String ?: "",
                totalCount = (map["totalCount"] as? Number)?.toInt() ?: 0,
                isViolation = map["isViolation"] as? Boolean ?: false,
                isForced = map["isForced"] as? Boolean ?: false,
                changedParams = map["changedParams"] as? String ?: "",
                skipPercent = (map["skipPercent"] as? Number)?.toDouble() ?: -1.0,
                peakRate = (map["peakRate"] as? Number)?.toInt() ?: 0,
                invalidationReason = map["invalidationReason"] as? String ?: "",
                parentFqn = map["parentFqn"] as? String ?: "",
                depth = (map["depth"] as? Number)?.toInt() ?: 0,
                paramStates = map["paramStates"] as? String ?: ""
            )
        }
    }
}

private fun appendComposableEntry(sb: StringBuilder, entry: ComposableEntry) {
    sb.append("{")
    sb.append("\"name\": \"${escapeJson(entry.name)}\", ")
    sb.append("\"rate\": ${entry.rate}, ")
    sb.append("\"budget\": ${entry.budget}, ")
    sb.append("\"budgetClass\": \"${escapeJson(entry.budgetClass)}\", ")
    sb.append("\"totalCount\": ${entry.totalCount}, ")
    sb.append("\"isViolation\": ${entry.isViolation}, ")
    sb.append("\"isForced\": ${entry.isForced}, ")
    sb.append("\"changedParams\": \"${escapeJson(entry.changedParams)}\", ")
    sb.append("\"skipPercent\": ${entry.skipPercent}, ")
    sb.append("\"peakRate\": ${entry.peakRate}, ")
    sb.append("\"invalidationReason\": \"${escapeJson(entry.invalidationReason)}\", ")
    sb.append("\"parentFqn\": \"${escapeJson(entry.parentFqn)}\", ")
    sb.append("\"depth\": ${entry.depth}, ")
    sb.append("\"paramStates\": \"${escapeJson(entry.paramStates)}\"")
    sb.append("}")
}

private fun escapeJson(s: String): String {
    return s.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}

/**
 * Minimal recursive-descent JSON parser. No external dependencies.
 */
private object JsonParser {
    fun parseObject(json: String): Map<String, Any?> {
        val state = ParseState(json.trim())
        return readObject(state)
    }

    private class ParseState(val input: String, var pos: Int = 0) {
        fun peek(): Char = if (pos < input.length) input[pos] else '\u0000'
        fun advance(): Char = input[pos++]
        fun skipWhitespace() {
            while (pos < input.length && input[pos].isWhitespace()) pos++
        }
    }

    private fun readValue(s: ParseState): Any? {
        s.skipWhitespace()
        return when (s.peek()) {
            '{' -> readObject(s)
            '[' -> readArray(s)
            '"' -> readString(s)
            'n' -> readNull(s)
            't', 'f' -> readBoolean(s)
            else -> readNumber(s)
        }
    }

    private fun readObject(s: ParseState): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        s.skipWhitespace()
        expect(s, '{')
        s.skipWhitespace()
        if (s.peek() == '}') { s.advance(); return map }
        while (true) {
            s.skipWhitespace()
            val key = readString(s)
            s.skipWhitespace()
            expect(s, ':')
            val value = readValue(s)
            map[key] = value
            s.skipWhitespace()
            when (s.peek()) {
                ',' -> s.advance()
                '}' -> { s.advance(); return map }
                else -> error("Expected ',' or '}' at pos ${s.pos}")
            }
        }
    }

    private fun readArray(s: ParseState): List<Any?> {
        val list = mutableListOf<Any?>()
        expect(s, '[')
        s.skipWhitespace()
        if (s.peek() == ']') { s.advance(); return list }
        while (true) {
            list.add(readValue(s))
            s.skipWhitespace()
            when (s.peek()) {
                ',' -> s.advance()
                ']' -> { s.advance(); return list }
                else -> error("Expected ',' or ']' at pos ${s.pos}")
            }
        }
    }

    private fun readString(s: ParseState): String {
        s.skipWhitespace()
        expect(s, '"')
        val sb = StringBuilder()
        while (s.peek() != '"') {
            val c = s.advance()
            if (c == '\\') {
                when (val esc = s.advance()) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        val hex = s.input.substring(s.pos, s.pos + 4)
                        s.pos += 4
                        sb.append(hex.toInt(16).toChar())
                    }
                    else -> { sb.append('\\'); sb.append(esc) }
                }
            } else {
                sb.append(c)
            }
        }
        expect(s, '"')
        return sb.toString()
    }

    private fun readNumber(s: ParseState): Number {
        val start = s.pos
        if (s.peek() == '-') s.advance()
        while (s.pos < s.input.length && (s.peek().isDigit() || s.peek() == '.' || s.peek() == 'e' || s.peek() == 'E' || s.peek() == '+' || s.peek() == '-')) {
            // avoid consuming a '-' that isn't part of scientific notation
            if ((s.peek() == '-' || s.peek() == '+') && s.pos > start) {
                val prev = s.input[s.pos - 1]
                if (prev != 'e' && prev != 'E') break
            }
            s.advance()
        }
        val numStr = s.input.substring(start, s.pos)
        return if ('.' in numStr || 'e' in numStr || 'E' in numStr) {
            numStr.toDouble()
        } else {
            val l = numStr.toLong()
            if (l in Int.MIN_VALUE..Int.MAX_VALUE) l.toInt() else l
        }
    }

    private fun readNull(s: ParseState): Any? {
        repeat(4) { s.advance() }
        return null
    }

    private fun readBoolean(s: ParseState): Boolean {
        return if (s.peek() == 't') {
            repeat(4) { s.advance() }
            true
        } else {
            repeat(5) { s.advance() }
            false
        }
    }

    private fun expect(s: ParseState, c: Char) {
        s.skipWhitespace()
        val actual = s.advance()
        if (actual != c) error("Expected '$c' but got '$actual' at pos ${s.pos - 1}")
    }
}
