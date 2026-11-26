package com.gravityssh.app.terminal

import kotlin.math.*

class TouchCursorEngine(var sensitivity: TouchSensitivity = TouchSensitivity.BALANCED) {
    private var accumX = 0f
    private var accumY = 0f
    private var smoothedSpeed = 0f
    private var hasSpeed = false

    fun computeGain(speedDpPerSec: Float): VelocitySample {
        val s = if (speedDpPerSec.isFinite() && speedDpPerSec > 0f) speedDpPerSec else 0f
        return when {
            s < 110f -> {
                val t = (s / 110f).coerceIn(0f, 1f)
                VelocitySample(0.55f + 0.30f * t * t * (3f - 2f * t), s, "精准")
            }
            s <= 360f -> VelocitySample(0.85f + 1.15f * ((s - 110f) / 250f).coerceIn(0f, 1f).pow(1.1f), s, "标准")
            else -> {
                val maxG = sensitivity.maxGain.coerceAtLeast(2.0f)
                VelocitySample(2.0f + (maxG - 2.0f) * ((s - 360f) / 1040f).coerceIn(0f, 1f).pow(0.82f), s, "加速")
            }
        }
    }

    fun onDragDelta(
        dxDp: Float, dyDp: Float, dtSec: Float,
        cellWidthDp: Float, cellHeightDp: Float, axisLock: Boolean = true
    ): DragResult {
        val dx = fin(dxDp); val dy = fin(dyDp)
        val dt = fin(dtSec, 0.016f).coerceIn(0.004f, 0.15f)
        val cellW = fin(cellWidthDp, 8f).coerceAtLeast(4f)
        val cellH = fin(cellHeightDp, 16f).coerceAtLeast(4f)

        val instant = hypot(dx, dy) / dt
        smoothedSpeed = if (!hasSpeed) { hasSpeed = true; instant } else 0.42f * instant + 0.58f * smoothedSpeed
        val sample = computeGain(smoothedSpeed)

        var effDx = dx; var effDy = dy
        if (axisLock) {
            if (abs(dx) > 2.2f * abs(dy)) { effDy = dy * 0.15f; accumY *= 0.60f }
            else if (abs(dy) > 2.2f * abs(dx)) { effDx = dx * 0.15f; accumX *= 0.60f }
        }

        val scale = sample.gain * sensitivity.baseScale
        accumX += (effDx / cellW) * scale
        accumY += (effDy / cellH) * scale

        val rawX = accumX.toInt(); val rawY = accumY.toInt()
        val total = abs(rawX) + abs(rawY)
        val emitX: Int; val emitY: Int
        if (total <= 12) {
            emitX = rawX; emitY = rawY; accumX -= emitX; accumY -= emitY
        } else {
            val r = 12f / total
            var sx = (rawX * r).toInt(); var sy = (rawY * r).toInt()
            if (sx == 0 && sy == 0) {
                if (abs(rawX) >= abs(rawY)) sx = if (rawX > 0) 1 else -1 else sy = if (rawY > 0) 1 else -1
            }
            emitX = sx; emitY = sy
            accumX = (accumX - emitX).coerceIn(-0.99f, 0.99f)
            accumY = (accumY - emitY).coerceIn(-0.99f, 0.99f)
        }
        return DragResult(stepsFor(emitY, emitX), sample)
    }

    fun computeFlingSchedule(
        vxDpPerSec: Float, vyDpPerSec: Float, cellWidthDp: Float, cellHeightDp: Float
    ): List<FlingPulse> {
        val vx = fin(vxDpPerSec); val vy = fin(vyDpPerSec)
        if (hypot(vx, vy) <= 420f) return emptyList()

        val effVx = if (abs(vy) > 2.2f * abs(vx)) 0f else vx
        val effVy = if (abs(vx) > 2.2f * abs(vy)) 0f else vy
        val cpsX = abs(effVx) / fin(cellWidthDp, 8f).coerceAtLeast(4f)
        val cpsY = abs(effVy) / fin(cellHeightDp, 16f).coerceAtLeast(4f)
        val totalSteps = (hypot(cpsX, cpsY) * 0.10f * sensitivity.baseScale).roundToInt().coerceIn(2, 20)
        val n = totalSteps.coerceIn(2, 8)
        val endDelay = (36f + (n - 2) * 5.67f).coerceIn(36f, 70f)
        val dirX = if (effVx >= 0f) CursorDir.RIGHT else CursorDir.LEFT
        val dirY = if (effVy >= 0f) CursorDir.DOWN else CursorDir.UP
        val weightX = cpsX / (cpsX + cpsY).coerceAtLeast(1e-4f)

        var errX = 0f; var rem = totalSteps; var totalMs = 0L; var prevMs = 0L
        val out = ArrayList<FlingPulse>(n)
        for (i in 0 until n) {
            val remP = n - i
            var ms = (18f * (endDelay / 18f).pow(i.toFloat() / (n - 1))).roundToLong()
            if (ms <= prevMs) ms = prevMs + 1L
            if (totalMs + ms > 340L) break
            val extra = (rem - remP).coerceAtLeast(0)
            val cnt = (if (extra > 0) 1 + ((extra + remP - 1) / remP).coerceAtMost(2) else 1).coerceAtMost(rem)
            val dir = when {
                cpsY == 0f -> dirX
                cpsX == 0f -> dirY
                else -> { errX += weightX; if (errX >= 0.5f) { errX -= 1f; dirX } else dirY }
            }
            out.add(FlingPulse(ms, TouchCursorStep(dir, cnt)))
            totalMs += ms; prevMs = ms; rem -= cnt
            if (rem <= 0) break
        }
        return out
    }

    fun computeTapToCursorSteps(
        cursorRow: Int, cursorCol: Int, targetRow: Int, targetCol: Int, screenRows: Int, screenCols: Int
    ): List<TouchCursorStep> = if (screenRows <= 0 || screenCols <= 0) emptyList() else stepsFor(
        targetRow.coerceIn(0, screenRows - 1) - cursorRow.coerceIn(0, screenRows - 1),
        targetCol.coerceIn(0, screenCols - 1) - cursorCol.coerceIn(0, screenCols - 1)
    )

    fun reset() { accumX = 0f; accumY = 0f; smoothedSpeed = 0f; hasSpeed = false }

    private fun stepsFor(dRow: Int, dCol: Int) = buildList(2) {
        if (dRow > 0) add(TouchCursorStep(CursorDir.DOWN, dRow)) else if (dRow < 0) add(TouchCursorStep(CursorDir.UP, -dRow))
        if (dCol > 0) add(TouchCursorStep(CursorDir.RIGHT, dCol)) else if (dCol < 0) add(TouchCursorStep(CursorDir.LEFT, -dCol))
    }

    private fun fin(v: Float, fallback: Float = 0f) = if (v.isFinite()) v else fallback
}
