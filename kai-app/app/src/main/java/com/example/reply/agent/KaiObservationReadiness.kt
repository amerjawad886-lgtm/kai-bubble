package com.example.reply.agent

object KaiObservationReadiness {

    enum class Tier {
        APP_LAUNCH_SAFE,
        SEMANTIC_ACTION_SAFE
    }

    data class Result(
        val passed: Boolean,
        val reason: String
    )

    fun evaluate(state: KaiScreenState, expectedPackage: String = "", allowLauncherSurface: Boolean = false, tier: Tier = Tier.SEMANTIC_ACTION_SAFE): Result {
        val matched = KaiVisionInterpreter.packageMatchesExpected(state.packageName, expectedPackage)
        return when {
            state.packageName.isBlank() -> Result(false, "missing_package")
            state.isOverlayPolluted() -> Result(false, "overlay_pollution")
            !matched -> Result(false, "wrong_package")
            !allowLauncherSurface && state.isLauncher() -> Result(false, "launcher_surface")
            tier == Tier.SEMANTIC_ACTION_SAFE && !KaiVisionInterpreter.isStrongState(state, expectedPackage, allowLauncherSurface) -> {
                Result(false, if (state.isWeakObservation()) "weak_observation" else "observation_not_ready")
            }
            !KaiVisionInterpreter.isUsableState(state) -> Result(false, "observation_not_usable")
            else -> Result(true, "observation_ready")
        }
    }
}
