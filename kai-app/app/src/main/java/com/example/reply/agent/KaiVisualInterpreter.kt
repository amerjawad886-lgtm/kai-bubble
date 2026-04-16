package com.example.reply.agent

object KaiVisualInterpreter {

    enum class Source {
        EVENT_DRIVEN,
        STRONG_OBSERVATION,
        LIVE_OBSERVATION,
        CANONICAL_FALLBACK,
        EMPTY
    }

    data class VisualTruth(
        val state: KaiScreenState,
        val source: Source,
        val usable: Boolean,
        val strong: Boolean,
        val expectedMatched: Boolean,
        val ageMs: Long,
        val reason: String
    )

    fun resolveTruth(
        expectedPackage: String = "",
        allowLauncherSurface: Boolean = true,
        requireStrong: Boolean = false,
        canonicalState: KaiScreenState? = null,
        preferredFreshWindowMs: Long = 900L,
        strictFreshWindowMs: Long = 350L
    ): VisualTruth {
        val now = System.currentTimeMillis()

        val liveObs = KaiLiveObservationRuntime.bestObservation(
            expectedPackage = expectedPackage,
            requireStrong = false
        )
        val liveState = KaiVisionInterpreter.toScreenState(liveObs)
        val liveAge = if (liveObs.updatedAt > 0L) now - liveObs.updatedAt else Long.MAX_VALUE
        val liveFrame = KaiVisionInterpreter.classify(
            obs = liveObs,
            expectedPackage = expectedPackage,
            allowLauncherSurface = allowLauncherSurface
        )

        val recentEventDriven =
            KaiLiveObservationRuntime.hasRecentEventDriven(strictFreshWindowMs)

        if (liveObs.updatedAt > 0L &&
            liveAge <= strictFreshWindowMs &&
            liveFrame.isUsable &&
            (!requireStrong || liveFrame.isStrong)
        ) {
            return VisualTruth(
                state = liveState,
                source = if (recentEventDriven) Source.EVENT_DRIVEN else Source.LIVE_OBSERVATION,
                usable = true,
                strong = liveFrame.isStrong,
                expectedMatched = liveFrame.expectedPackageMatched,
                ageMs = liveAge,
                reason = if (recentEventDriven) "recent_event_driven_live_truth" else "recent_live_truth"
            )
        }

        val strongObs = KaiLiveObservationRuntime.bestObservation(
            expectedPackage = expectedPackage,
            requireStrong = true
        )
        val strongState = KaiVisionInterpreter.toScreenState(strongObs)
        val strongAge = if (strongObs.updatedAt > 0L) now - strongObs.updatedAt else Long.MAX_VALUE
        val strongFrame = KaiVisionInterpreter.classify(
            obs = strongObs,
            expectedPackage = expectedPackage,
            allowLauncherSurface = allowLauncherSurface
        )

        if (strongObs.updatedAt > 0L &&
            strongAge <= preferredFreshWindowMs &&
            strongFrame.isStrong
        ) {
            return VisualTruth(
                state = strongState,
                source = Source.STRONG_OBSERVATION,
                usable = true,
                strong = true,
                expectedMatched = strongFrame.expectedPackageMatched,
                ageMs = strongAge,
                reason = "fresh_strong_observation_truth"
            )
        }

        if (liveObs.updatedAt > 0L &&
            liveAge <= preferredFreshWindowMs &&
            liveFrame.isUsable &&
            (!requireStrong || liveFrame.isStrong)
        ) {
            return VisualTruth(
                state = liveState,
                source = Source.LIVE_OBSERVATION,
                usable = true,
                strong = liveFrame.isStrong,
                expectedMatched = liveFrame.expectedPackageMatched,
                ageMs = liveAge,
                reason = "fresh_live_observation_truth"
            )
        }

        val canonical = canonicalState
        if (canonical != null && canonical.packageName.isNotBlank()) {
            val canonicalAge = now - canonical.updatedAt
            val canonicalUsable = KaiVisionInterpreter.isUsableState(canonical)
            val canonicalStrong = KaiVisionInterpreter.isStrongState(
                state = canonical,
                expectedPackage = expectedPackage,
                allowLauncherSurface = allowLauncherSurface
            )
            val canonicalMatched =
                KaiVisionInterpreter.packageMatchesExpected(canonical.packageName, expectedPackage)

            if (canonicalUsable && (!requireStrong || canonicalStrong)) {
                return VisualTruth(
                    state = canonical,
                    source = Source.CANONICAL_FALLBACK,
                    usable = true,
                    strong = canonicalStrong,
                    expectedMatched = canonicalMatched,
                    ageMs = canonicalAge,
                    reason = "canonical_fallback_truth"
                )
            }
        }

        return VisualTruth(
            state = liveState,
            source = Source.EMPTY,
            usable = false,
            strong = false,
            expectedMatched = false,
            ageMs = liveAge,
            reason = "no_usable_visual_truth"
        )
    }
}