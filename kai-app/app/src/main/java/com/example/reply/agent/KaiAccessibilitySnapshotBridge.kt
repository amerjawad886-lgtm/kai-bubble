package com.example.reply.agent

/**
 * On-demand accessibility-tree snapshot bridge.
 *
 * لا يوجد dump pipeline مستمر. الوكيل يطلب snapshot فورية على الطلب فقط
 * عندما يحتاج أن يحدّد target للضغط/الكتابة/التمرير، أو يتحقق من
 * semantic fingerprint بعد action.
 *
 * المصدر الحقيقي للعالم (world-state) يبقى KaiLiveVisionRuntime.
 * الـ snapshot هنا auxiliary فقط لأغراض الـ targeting.
 */
object KaiAccessibilitySnapshotBridge {

    interface Provider {
        fun captureSnapshot(expectedPackage: String): KaiScreenState?
    }

    @Volatile
    private var provider: Provider? = null

    fun register(p: Provider) {
        provider = p
    }

    fun unregister(p: Provider) {
        if (provider === p) provider = null
    }

    fun isAvailable(): Boolean = provider != null

    /**
     * Produce a fresh KaiScreenState from the current accessibility node tree.
     *
     * Returns null if the accessibility service isn't connected yet, or if the
     * active window can't be resolved. Callers must treat a null result as
     * "no UI targeting info available right now" — they should not fall back to
     * stale caches; world-state truth lives in KaiLiveVisionRuntime.
     */
    fun captureSnapshot(expectedPackage: String = ""): KaiScreenState? =
        provider?.captureSnapshot(expectedPackage)
}
