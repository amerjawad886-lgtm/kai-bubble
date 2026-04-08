package com.example.reply.agent

object KaiGestureUtils {

    fun resolveCoordinate(
        value: Float?,
        sizePx: Int,
        fallbackFraction: Float = 0.5f
    ): Float {
        val safeSize = sizePx.coerceAtLeast(1)
        val raw = when {
            value == null -> safeSize * fallbackFraction
            value in 0f..1f -> safeSize * value
            else -> value
        }
        return raw.coerceIn(0f, safeSize.toFloat())
    }

    fun resolveDuration(value: Long?, fallback: Long = 350L): Long {
        return (value ?: fallback).coerceIn(80L, 8000L)
    }
}