package com.example.reply.agent

/**
 * Session-local state container for the agent.
 *
 * Extracted from KaiAgentController as part of the live-vision migration.
 * Owns two things and nothing else:
 *
 *  1. The current [KaiAgentSnapshot] (UI-visible agent status). Exposed as a
 *     plain @Volatile var so callers can keep idiomatic `snapshot = snapshot.copy(...)`
 *     style mutations — behavior is unchanged from the old in-controller field.
 *
 *  2. A bounded observation memory (ArrayDeque of recent KaiObservation entries)
 *     with a monitor-protected API. The memory is auxiliary context for the
 *     planner; it is NOT the agent's truth source. World-state truth lives in
 *     KaiLiveVisionRuntime; UI targeting truth lives in KaiAccessibilitySnapshotBridge.
 *
 * This object is deliberately stateless from a runtime-lifecycle perspective:
 * it does not start jobs, does not touch observation runtimes, and does not
 * expose any mutation that depends on live-vision state. Higher-level policy
 * (action-loop begin/end, continuous insight loop, etc.) stays in
 * KaiAgentController until its own extraction batches.
 */
object KaiAgentSessionState {

    const val MAX_MEMORY: Int = 18

    private val memory = ArrayDeque<KaiObservation>()

    @Volatile
    var snapshot: KaiAgentSnapshot = KaiAgentSnapshot()

    fun addObservation(obs: KaiObservation) {
        synchronized(memory) {
            memory.addLast(obs)
            while (memory.size > MAX_MEMORY) memory.removeFirst()
        }
    }

    fun memorySize(): Int = synchronized(memory) { memory.size }

    fun pruneMemory(maxItems: Int) {
        val cap = maxItems.coerceIn(1, MAX_MEMORY)
        synchronized(memory) {
            while (memory.size > cap) memory.removeFirst()
        }
    }

    fun clearMemory() {
        synchronized(memory) { memory.clear() }
    }

    fun recentObservations(count: Int): List<KaiObservation> {
        if (count <= 0) return emptyList()
        return synchronized(memory) { memory.takeLast(count) }
    }
}
