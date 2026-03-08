package io.aldefy.rebound.ide

/**
 * Per-composable ring buffer storing [capacity] rate samples (1 per poll tick).
 * Zero-allocation in steady state — uses IntArray per FQN.
 */
class RateHistoryBuffer(private val capacity: Int = 60) {

    private class Ring(val data: IntArray = IntArray(60), var head: Int = 0, var size: Int = 0)

    private val buffers = mutableMapOf<String, Ring>()

    fun record(fqn: String, rate: Int) {
        val ring = buffers.getOrPut(fqn) { Ring(IntArray(capacity)) }
        ring.data[ring.head] = rate
        ring.head = (ring.head + 1) % capacity
        if (ring.size < capacity) ring.size++
    }

    fun getSamples(fqn: String): List<Int> {
        val ring = buffers[fqn] ?: return emptyList()
        val result = ArrayList<Int>(ring.size)
        val start = (ring.head - ring.size + capacity) % capacity
        for (i in 0 until ring.size) {
            result.add(ring.data[(start + i) % capacity])
        }
        return result
    }

    fun clear() {
        buffers.clear()
    }
}
