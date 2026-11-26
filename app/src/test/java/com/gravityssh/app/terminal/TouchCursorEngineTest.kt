package com.gravityssh.app.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchCursorEngineTest {

    @Test
    fun slowDragProducesDampedGainAndPreciseZone() {
        val engine = TouchCursorEngine(TouchSensitivity.BALANCED)
        val sample = engine.computeGain(60f)
        assertTrue(sample.gain < 0.85f && sample.gain >= 0.55f)
        assertEquals("精准", sample.zoneLabel)

        val drag = engine.onDragDelta(3f, 0f, 0.05f, 8f, 16f)
        assertTrue(drag.velocity.gain < 0.85f)
        assertEquals("精准", drag.velocity.zoneLabel)
    }

    @Test
    fun fastDragAcceleratesAndEmitsAtLeast3xStepsForSameDistance() {
        val sample = TouchCursorEngine(TouchSensitivity.BALANCED).computeGain(900f)
        assertTrue(sample.gain > 3.0f)
        assertEquals("加速", sample.zoneLabel)

        // Exact same 120 dp drag: slow (~60 dp/s) vs fast (~900 dp/s)
        val slowEngine = TouchCursorEngine(TouchSensitivity.BALANCED)
        var slowSteps = 0
        repeat(20) {
            val res = slowEngine.onDragDelta(6f, 0f, 0.10f, 8f, 16f)
            assertEquals("精准", res.velocity.zoneLabel)
            slowSteps += res.steps.filter { it.dir == CursorDir.RIGHT }.sumOf { it.count }
        }

        val fastEngine = TouchCursorEngine(TouchSensitivity.BALANCED)
        var fastSteps = 0
        repeat(10) {
            val res = fastEngine.onDragDelta(12f, 0f, 12f / 900f, 8f, 16f)
            assertEquals("加速", res.velocity.zoneLabel)
            assertTrue(res.velocity.gain > 3.0f)
            fastSteps += res.steps.filter { it.dir == CursorDir.RIGHT }.sumOf { it.count }
        }

        assertTrue(slowSteps > 0)
        assertTrue("fast=$fastSteps slow=$slowSteps", fastSteps >= slowSteps * 3)
    }

    @Test
    fun sensitivityPresetsScaleStepCountMonotonically() {
        fun dragSteps(preset: TouchSensitivity): Int {
            val engine = TouchCursorEngine(preset)
            var count = 0
            repeat(10) {
                count += engine.onDragDelta(12f, 0f, 0.015f, 8f, 16f)
                    .steps.filter { it.dir == CursorDir.RIGHT }.sumOf { it.count }
            }
            return count
        }

        val precise = dragSteps(TouchSensitivity.PRECISE)
        val balanced = dragSteps(TouchSensitivity.BALANCED)
        val fast = dragSteps(TouchSensitivity.FAST)
        assertTrue("precise=$precise balanced=$balanced fast=$fast", precise < balanced && balanced < fast)

        assertEquals(TouchSensitivity.PRECISE, TouchSensitivity.fromId("PRECISE"))
        assertEquals(TouchSensitivity.BALANCED, TouchSensitivity.next(TouchSensitivity.PRECISE))
        assertEquals(TouchCursorMode.CURSOR, TouchCursorMode.next(TouchCursorMode.AUTO))
        assertEquals(TouchCursorMode.AUTO, TouchCursorMode.fromId("unknown"))
    }

    @Test
    fun axisLockSuppressesVerticalWobbleDuringHorizontalDrag() {
        val engine = TouchCursorEngine(TouchSensitivity.BALANCED)
        val single = engine.onDragDelta(40f, 4f, 0.10f, 8f, 16f, axisLock = true)
        assertTrue(single.steps.any { it.dir == CursorDir.RIGHT && it.count > 0 })
        assertTrue(single.steps.none { it.dir == CursorDir.UP || it.dir == CursorDir.DOWN })

        engine.reset()
        var vSteps = 0
        var hSteps = 0
        repeat(6) {
            val res = engine.onDragDelta(10f, 1f, 0.025f, 8f, 16f, axisLock = true)
            vSteps += res.steps.filter { it.dir == CursorDir.UP || it.dir == CursorDir.DOWN }.sumOf { it.count }
            hSteps += res.steps.filter { it.dir == CursorDir.RIGHT }.sumOf { it.count }
        }
        assertTrue(hSteps > 0)
        assertEquals(0, vSteps)
    }

    @Test
    fun flingScheduleHandlesSlowReleaseAndDeceleratingFastFlick() {
        val engine = TouchCursorEngine(TouchSensitivity.BALANCED)
        assertTrue(engine.computeFlingSchedule(200f, 0f, 8f, 16f).isEmpty())
        assertTrue(engine.computeFlingSchedule(0f, -200f, 8f, 16f).isEmpty())

        val pulses = engine.computeFlingSchedule(1000f, 0f, 8f, 16f)
        assertTrue(pulses.isNotEmpty())
        assertTrue(pulses.all { it.step.dir == CursorDir.RIGHT && it.step.count > 0 })
        for (i in 1 until pulses.size) {
            assertTrue(pulses[i].delayMs > pulses[i - 1].delayMs)
        }
        assertTrue(pulses.sumOf { it.step.count } in 2..20)
        assertTrue(pulses.sumOf { it.delayMs } <= 340L)
        assertTrue(engine.computeFlingSchedule(25000f, 0f, 8f, 16f).sumOf { it.step.count } <= 20)
    }

    @Test
    fun tapToCursorComputesExactStepsAndClampsOutOfBounds() {
        val engine = TouchCursorEngine()
        assertEquals(
            listOf(TouchCursorStep(CursorDir.DOWN, 5), TouchCursorStep(CursorDir.LEFT, 3)),
            engine.computeTapToCursorSteps(2, 5, 7, 2, 24, 80)
        )
        assertTrue(engine.computeTapToCursorSteps(2, 5, 2, 5, 24, 80).isEmpty())
        assertEquals(
            listOf(TouchCursorStep(CursorDir.DOWN, 21), TouchCursorStep(CursorDir.LEFT, 5)),
            engine.computeTapToCursorSteps(2, 5, 99, -10, 24, 80)
        )
        assertEquals(
            listOf(TouchCursorStep(CursorDir.UP, 2), TouchCursorStep(CursorDir.RIGHT, 74)),
            engine.computeTapToCursorSteps(2, 5, -5, 200, 24, 80)
        )
        assertTrue(engine.computeTapToCursorSteps(0, 0, 5, 5, 0, 80).isEmpty())
    }
}
