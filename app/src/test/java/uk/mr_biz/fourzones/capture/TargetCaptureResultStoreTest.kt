package uk.mr_biz.fourzones.capture

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetCaptureResultStoreTest {

    private fun captured() = TargetCaptureResult.Captured(
        targets = emptyMap(),
        diagnostics = CaptureDiagnostics(
            sequenceNumber = 1,
            wallClockMillis = 0,
            monotonicMillis = 0,
            perDisplay = emptyList(),
            focusEvidence = CaptureFocusEvidence(
                hasDisplayScopedFocusEvidence = false,
                observedDisplayIds = emptyList(),
                globalFocusedTaskId = null,
                scopedFocusByDisplay = emptyMap(),
            ),
        ),
    )

    @After
    fun tearDown() {
        // The store is process-global; keep tests independent.
        TargetCaptureResultStore.setListener(null)
        TargetCaptureResultStore.publish(TargetCaptureResult.Failed("test reset"))
    }

    @Test
    fun `publish updates the last result and notifies the listener`() {
        val seen = mutableListOf<TargetCaptureResult>()
        TargetCaptureResultStore.setListener { seen += it }

        val result = TargetCaptureResult.Failed("no focused task")
        TargetCaptureResultStore.publish(result)

        assertEquals(result, TargetCaptureResultStore.lastResult)
        assertEquals(listOf<TargetCaptureResult>(result), seen)
    }

    @Test
    fun `last result survives without a listener`() {
        TargetCaptureResultStore.setListener(null)
        val result = captured()

        TargetCaptureResultStore.publish(result)

        assertEquals(result, TargetCaptureResultStore.lastResult)
    }

    @Test
    fun `throwing listener does not lose the stored result`() {
        // The store writes lastResult BEFORE listener delivery, so a broken
        // diagnostic listener loses only its notification — the structured
        // result remains available, and the service boundary contains the
        // propagated exception so teardown still runs.
        TargetCaptureResultStore.setListener { throw IllegalStateException("listener broken") }
        val result = TargetCaptureResult.Failed("capture failed")

        val thrown = runCatching { TargetCaptureResultStore.publish(result) }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertEquals(result, TargetCaptureResultStore.lastResult)

        // The store stays fully usable afterwards.
        TargetCaptureResultStore.setListener(null)
        val next = captured()
        TargetCaptureResultStore.publish(next)
        assertEquals(next, TargetCaptureResultStore.lastResult)
    }

    @Test
    fun `cleared listener receives nothing further`() {
        val seen = mutableListOf<TargetCaptureResult>()
        TargetCaptureResultStore.setListener { seen += it }
        TargetCaptureResultStore.setListener(null)

        TargetCaptureResultStore.publish(TargetCaptureResult.Failed("late"))

        assertTrue(seen.isEmpty())
    }
}
