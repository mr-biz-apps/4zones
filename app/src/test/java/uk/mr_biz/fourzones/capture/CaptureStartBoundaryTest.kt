package uk.mr_biz.fourzones.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureStartBoundaryTest {

    // 1. startAction throws, publisher succeeds: one sanitized result, no escape.
    @Test
    fun `runtime exception from the start action becomes a sanitized failed result`() {
        val published = mutableListOf<TargetCaptureResult>()

        CaptureStartBoundary.start(publish = { published += it }) {
            throw SecurityException("raw framework detail that must not surface")
        }

        val failed = published.single() as TargetCaptureResult.Failed
        // Class name only; no message, no stack trace, no internals.
        assertEquals(
            "could not start the capture service (SecurityException)",
            failed.reason,
        )
        assertTrue(!failed.reason.contains("raw framework detail"))
    }

    // 2. startAction throws AND publisher throws: contained, one attempt.
    @Test
    fun `throwing publisher is contained with exactly one publication attempt`() {
        var publishCalls = 0

        CaptureStartBoundary.start(
            publish = {
                publishCalls++
                throw IllegalStateException("publisher broken")
            },
        ) {
            throw SecurityException("start refused")
        }
        // Reaching here proves no exception escaped the boundary.

        assertEquals(1, publishCalls)
    }

    // Concrete-publisher variant of 2: a broken store listener neither
    // escapes nor loses the stored sanitized result.
    @Test
    fun `broken store listener keeps the sanitized failure in lastResult`() {
        TargetCaptureResultStore.setListener { throw IllegalStateException("listener broken") }
        try {
            CaptureStartBoundary.start {
                throw SecurityException("start refused")
            }

            val last = TargetCaptureResultStore.lastResult as TargetCaptureResult.Failed
            assertTrue(last.reason.contains("SecurityException"))
        } finally {
            TargetCaptureResultStore.setListener(null)
        }
    }

    // 3. Publisher throws a fatal Error: it propagates.
    @Test
    fun `fatal error from the publisher propagates`() {
        assertThrows(Error::class.java) {
            CaptureStartBoundary.start(publish = { throw Error("fatal publisher") }) {
                throw SecurityException("start refused")
            }
        }
    }

    // 4. startAction throws a fatal Error: propagates, publisher untouched.
    @Test
    fun `fatal error from the start action propagates without publication`() {
        var publishCalls = 0

        assertThrows(Error::class.java) {
            CaptureStartBoundary.start(publish = { publishCalls++ }) {
                throw Error("fatal start")
            }
        }

        assertEquals(0, publishCalls)
    }

    // 5. Success path unchanged: nothing published.
    @Test
    fun `successful start publishes nothing`() {
        val published = mutableListOf<TargetCaptureResult>()

        CaptureStartBoundary.start(publish = { published += it }) { /* starts fine */ }

        assertTrue(published.isEmpty())
    }

    @Test
    fun `default publish path stores through the result store`() {
        CaptureStartBoundary.start { throw IllegalStateException("boom") }

        val last = TargetCaptureResultStore.lastResult as TargetCaptureResult.Failed
        assertTrue(last.reason.contains("IllegalStateException"))
    }
}
