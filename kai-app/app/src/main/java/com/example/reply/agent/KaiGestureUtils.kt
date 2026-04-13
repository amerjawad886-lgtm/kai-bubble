package com.example.reply.agent

import kotlin.math.abs

object KaiGestureUtils {

    data class BoundsRect(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val width: Int get() = (right - left).coerceAtLeast(0)
        val height: Int get() = (bottom - top).coerceAtLeast(0)
        val centerX: Float get() = left + width / 2f
        val centerY: Float get() = top + height / 2f
        fun isValid(): Boolean = right > left && bottom > top
    }

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

    fun parseBoundsRect(bounds: String): BoundsRect? {
        val match = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""").find(bounds.trim()) ?: return null
        val left = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        val top = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return null
        val right = match.groupValues.getOrNull(3)?.toIntOrNull() ?: return null
        val bottom = match.groupValues.getOrNull(4)?.toIntOrNull() ?: return null
        val rect = BoundsRect(left, top, right, bottom)
        return rect.takeIf { it.isValid() }
    }

    fun centerFromBounds(bounds: String): Pair<Float, Float>? {
        val rect = parseBoundsRect(bounds) ?: return null
        return rect.centerX to rect.centerY
    }

    fun safeTapCoordinate(
        valuePx: Float,
        sizePx: Int,
        edgePaddingPx: Float = 24f
    ): Float {
        val safeSize = sizePx.coerceAtLeast(1).toFloat()
        val padding = edgePaddingPx.coerceAtLeast(0f).coerceAtMost(safeSize / 2f)
        return valuePx.coerceIn(padding, safeSize - padding)
    }

    fun safeTapFromBounds(
        bounds: String,
        screenWidth: Int,
        screenHeight: Int,
        preferUpperHalf: Boolean = false
    ): Pair<Float, Float>? {
        val rect = parseBoundsRect(bounds) ?: return null
        val x = safeTapCoordinate(rect.centerX, screenWidth)
        val rawY = if (preferUpperHalf) {
            rect.top + rect.height * 0.38f
        } else {
            rect.centerY
        }
        val y = safeTapCoordinate(rawY, screenHeight)
        return x to y
    }

    fun safeTapFromBounds(
        bounds: String,
        preferUpperHalf: Boolean = false
    ): Pair<Float, Float>? {
        val rect = parseBoundsRect(bounds) ?: return null
        return rect.centerX to (if (preferUpperHalf) rect.top + rect.height * 0.38f else rect.centerY)
    }

    fun normalizedDistance(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        width: Int,
        height: Int
    ): Float {
        val w = width.coerceAtLeast(1).toFloat()
        val h = height.coerceAtLeast(1).toFloat()
        val dx = abs(x1 - x2) / w
        val dy = abs(y1 - y2) / h
        return dx + dy
    }

    fun chooseBestTapCandidate(
        candidates: List<Pair<Float, Float>>,
        screenWidth: Int,
        screenHeight: Int
    ): Pair<Float, Float>? {
        if (candidates.isEmpty()) return null
        val centerX = screenWidth.coerceAtLeast(1) / 2f
        val centerY = screenHeight.coerceAtLeast(1) / 2f
        return candidates.minByOrNull { (x, y) ->
            normalizedDistance(x, y, centerX, centerY, screenWidth, screenHeight)
        }?.let { (x, y) ->
            safeTapCoordinate(x, screenWidth) to safeTapCoordinate(y, screenHeight)
        }
    }
}
