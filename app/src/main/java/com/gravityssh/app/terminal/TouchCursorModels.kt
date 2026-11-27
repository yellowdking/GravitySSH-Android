package com.gravityssh.app.terminal

enum class CursorDir {
    UP,
    DOWN,
    LEFT,
    RIGHT
}

enum class TouchCursorMode(
    val id: String,
    val label: String
) {
    AUTO("auto", "自动"),
    CURSOR("cursor", "光标"),
    SCROLL("scroll", "滚屏");

    companion object {
        fun fromId(id: String): TouchCursorMode {
            val normalized = id.trim().lowercase()
            return entries.firstOrNull { it.id == normalized } ?: AUTO
        }

        fun next(current: TouchCursorMode): TouchCursorMode {
            val all = entries
            return all[(current.ordinal + 1) % all.size]
        }
    }
}

enum class TouchSensitivity(
    val id: String,
    val label: String,
    val baseScale: Float,
    val maxGain: Float
) {
    PRECISE("precise", "精细", 0.72f, 3.0f),
    BALANCED("balanced", "标准", 1.0f, 5.0f),
    FAST("fast", "疾速", 1.35f, 7.0f);

    companion object {
        fun fromId(id: String): TouchSensitivity {
            val normalized = id.trim().lowercase()
            return entries.firstOrNull { it.id == normalized } ?: BALANCED
        }

        fun next(current: TouchSensitivity): TouchSensitivity {
            val all = entries
            return all[(current.ordinal + 1) % all.size]
        }
    }
}

data class TouchCursorStep(
    val dir: CursorDir,
    val count: Int
)

data class VelocitySample(
    val gain: Float,
    val speedDpPerSec: Float,
    val zoneLabel: String
)

data class DragResult(
    val steps: List<TouchCursorStep>,
    val velocity: VelocitySample
)

data class FlingPulse(
    val delayMs: Long,
    val step: TouchCursorStep
)
