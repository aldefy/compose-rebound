package io.aldefy.rebound

/**
 * Decodes the Compose compiler's `$changed` bitmask.
 *
 * The bitmask uses 3 bits per parameter slot:
 * - Bit 0 (lowest): force-recompose flag (global, not per-param)
 * - Bits per param (starting at bit 1): 3-bit encoding
 *   - 0b000 = Uncertain (runtime must check equality)
 *   - 0b001 = Same (compiler proved unchanged)
 *   - 0b010 = Different (compiler proved changed)
 *   - 0b100 = Static (compile-time constant, never changes)
 */
object ChangedMaskDecoder {

    enum class ParamState(val label: String) {
        UNCERTAIN("uncertain"),
        SAME("same"),
        DIFFERENT("CHANGED"),
        STATIC("static");
    }

    /**
     * Decode the $changed mask for the given parameter names.
     * Returns a list of (paramName, state) pairs.
     *
     * Compose uses 3 bits per parameter, starting at bit 1 (bit 0 is the force flag).
     * Each $changed mask holds up to 10 params (31 usable bits / 3 bits per param).
     * For functions with >10 params, use [decodeFromString] or [decodeMulti].
     */
    fun decode(changedMask: Int, paramNames: String): List<Pair<String, ParamState>> {
        return decodeMulti(listOf(changedMask), paramNames)
    }

    /**
     * Decode multiple $changed masks from a comma-separated string representation.
     * Used for composables with >10 parameters where Compose generates $changed, $changed1, etc.
     *
     * @param changedMasks Comma-separated int values, e.g. "123,456". Empty string uses mask=0.
     * @param paramNames Comma-separated parameter names.
     */
    fun decodeFromString(changedMasks: String, paramNames: String): List<Pair<String, ParamState>> {
        if (changedMasks.isEmpty()) return decodeMulti(listOf(0), paramNames)
        val masks = changedMasks.split(",").map { it.trim().toIntOrNull() ?: 0 }
        return decodeMulti(masks, paramNames)
    }

    /**
     * Decode a list of $changed masks for the given parameter names.
     *
     * Each mask covers up to 10 parameters (3 bits each, starting at bit 1).
     * Parameter at overall index N uses mask at index N/10, with local index N%10.
     */
    fun decodeMulti(changedMasks: List<Int>, paramNames: String): List<Pair<String, ParamState>> {
        if (paramNames.isEmpty()) return emptyList()
        val names = paramNames.split(",")
        return names.mapIndexed { index, name ->
            // Each $changed mask holds 10 params (3 bits each, bit 0 is force flag)
            val maskIndex = index / 10
            val paramIndex = index % 10
            val mask = changedMasks.getOrElse(maskIndex) { 0 }
            val shift = (paramIndex + 1) * 3  // +1 because bit 0 is force flag
            val bits = (mask ushr shift) and 0b111
            val state = when {
                bits and 0b100 != 0 -> ParamState.STATIC
                bits and 0b010 != 0 -> ParamState.DIFFERENT
                bits and 0b001 != 0 -> ParamState.SAME
                else -> ParamState.UNCERTAIN
            }
            name.trim() to state
        }
    }

    /**
     * Returns a compact string showing only changed/uncertain params.
     * Example: "offset=CHANGED, scale=CHANGED"
     */
    fun formatChangedParams(changedMask: Int, paramNames: String): String {
        return formatChangedParamsMulti(listOf(changedMask), paramNames)
    }

    /**
     * Format changed params from a comma-separated masks string (for >10 param composables).
     */
    fun formatChangedParamsFromString(changedMasks: String, paramNames: String): String {
        if (changedMasks.isEmpty()) return formatChangedParamsMulti(listOf(0), paramNames)
        val masks = changedMasks.split(",").map { it.trim().toIntOrNull() ?: 0 }
        return formatChangedParamsMulti(masks, paramNames)
    }

    /**
     * Format changed params from multiple masks.
     */
    fun formatChangedParamsMulti(changedMasks: List<Int>, paramNames: String): String {
        val decoded = decodeMulti(changedMasks, paramNames)
        val interesting = decoded.filter {
            it.second == ParamState.DIFFERENT || it.second == ParamState.UNCERTAIN
        }
        if (interesting.isEmpty()) return "all params stable"
        return interesting.joinToString(", ") { "${it.first}=${it.second.label}" }
    }

    /** Returns true if force-recompose bit is set (bit 0) in any of the masks */
    fun isForced(changedMask: Int): Boolean = changedMask and 0b1 != 0

    /** Returns true if force-recompose bit is set in any mask from a comma-separated string */
    fun isForcedFromString(changedMasks: String): Boolean {
        if (changedMasks.isEmpty()) return false
        return changedMasks.split(",").any { (it.trim().toIntOrNull() ?: 0) and 0b1 != 0 }
    }
}
