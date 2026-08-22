package uk.mr_biz.fourzones.privileged

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adversarial tests for the process runner via the launcher seam: the fakes
 * only fake a Process object (streams that stay blocked, destroy() that
 * lies, unkillable children, close() that throws) — there is no command
 * abstraction to misuse.
 */
class TopologyProcessRunnerTest {

    /**
     * Controllable java.lang.Process.
     *
     * @param streamIgnoresExit when true, stdout stays BLOCKED after the
     * data even once the process exits; it unblocks only when the stream is
     * explicitly closed.
     * @param destroyWorks whether destroy() actually terminates the child.
     * @param forciblyWorks whether destroyForcibly() terminates the child.
     * @param stdoutCloseFailsFromCall 1-based close() call index at which
     * stdout close starts throwing [stdoutCloseFailure]; the reader's own
     * close (on normal completion) is call 1, cleanup's close comes after.
     * @param stdoutCloseUnblocks when false, a failing stdout close also
     * fails to release a blocked reader.
     * @param readIgnoresInterrupt pathological stream whose blocked read
     * ignores interruption (only an unblocking close releases it).
     */
    private class AdversarialProcess(
        output: String = "",
        private val exitCode: Int = 0,
        exitsOnItsOwn: Boolean = true,
        private val streamIgnoresExit: Boolean = false,
        private val destroyWorks: Boolean = true,
        private val forciblyWorks: Boolean = true,
        private val streamFailure: IOException? = null,
        private val stdinCloseFailure: IOException? = null,
        private val stdoutCloseFailure: IOException? = null,
        private val stderrCloseFailure: IOException? = null,
        private val stdoutCloseFailsFromCall: Int = 1,
        private val stdoutCloseUnblocks: Boolean = true,
        private val readIgnoresInterrupt: Boolean = false,
    ) : Process() {
        val destroyCalled = AtomicBoolean(false)
        val forciblyCalled = AtomicBoolean(false)
        val stdinCloseCalls = AtomicInteger(0)
        val stdoutCloseCalls = AtomicInteger(0)
        val stderrCloseCalls = AtomicInteger(0)
        private var killed = false
        private val exited = CountDownLatch(if (exitsOnItsOwn) 0 else 1)
        private val streamClosed = CountDownLatch(1)
        private val outputBytes = output.toByteArray()

        /** Post-test hygiene for pathological wedged-stream scenarios. */
        fun unwedgeStream() = streamClosed.countDown()

        private val stdout = object : InputStream() {
            private var position = 0

            override fun read(): Int {
                val single = ByteArray(1)
                return if (read(single, 0, 1) == -1) -1 else single[0].toInt()
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (streamClosed.count == 0L) throw IOException("Stream closed")
                if (position < outputBytes.size) {
                    val n = minOf(len, outputBytes.size - position)
                    System.arraycopy(outputBytes, position, b, off, n)
                    position += n
                    return n
                }
                streamFailure?.let { throw it }
                if (streamIgnoresExit) {
                    awaitBlocking(streamClosed)
                    throw IOException("Stream closed")
                }
                awaitBlocking(exited)
                return -1
            }

            private fun awaitBlocking(latch: CountDownLatch) {
                if (readIgnoresInterrupt) {
                    while (latch.count > 0) {
                        try {
                            latch.await()
                        } catch (e: InterruptedException) {
                            // Pathological stream: interrupt is ignored.
                        }
                    }
                } else {
                    latch.await()
                }
            }

            override fun close() {
                val call = stdoutCloseCalls.incrementAndGet()
                val fails = stdoutCloseFailure != null && call >= stdoutCloseFailsFromCall
                if (!fails || stdoutCloseUnblocks) streamClosed.countDown()
                if (fails) throw stdoutCloseFailure!!
            }
        }

        private val stdin = object : OutputStream() {
            override fun write(b: Int) = Unit
            override fun close() {
                stdinCloseCalls.incrementAndGet()
                stdinCloseFailure?.let { throw it }
            }
        }

        private val stderr = object : InputStream() {
            override fun read(): Int = -1
            override fun close() {
                stderrCloseCalls.incrementAndGet()
                stderrCloseFailure?.let { throw it }
            }
        }

        override fun getInputStream(): InputStream = stdout
        override fun getOutputStream(): OutputStream = stdin
        override fun getErrorStream(): InputStream = stderr

        override fun waitFor(): Int {
            exited.await()
            return exitValue()
        }

        override fun exitValue(): Int {
            if (exited.count > 0) throw IllegalThreadStateException("still running")
            return if (killed) 137 else exitCode
        }

        override fun destroy() {
            destroyCalled.set(true)
            if (destroyWorks) kill()
        }

        override fun destroyForcibly(): Process {
            forciblyCalled.set(true)
            if (forciblyWorks) kill()
            return this
        }

        private fun kill() {
            if (exited.count > 0) {
                killed = true
                exited.countDown()
            }
        }
    }

    private val validOutput = buildString {
        appendLine("Display #0 (activities from top to bottom):")
        appendLine(
            "  * Task{a #55 type=undefined dw=activatable U=0 visible=true " +
                "visibleRequested=true mode=freeform translucent=true sz=0}",
        )
        appendLine("    mDeskRootTaskType=activatable")
    }

    private fun oversizedOutput(): String {
        val hugeRetainedLine = "  * Task{p #1 type=undefined dw=minimized U=0 visible=false " +
            "visibleRequested=false mode=freeform translucent=true sz=0}" + "x".repeat(300)
        return buildString {
            appendLine("Display #0 (activities from top to bottom):")
            repeat(2_000) { appendLine(hugeRetainedLine) }
        }
    }

    private fun assertNoReaderThreadAlive() {
        val alive = Thread.getAllStackTraces().keys
            .filter { it.name == "dexzones-topology-filter" && it.isAlive }
        assertTrue("reader threads still alive: $alive", alive.isEmpty())
    }

    private fun run(process: Process, timeoutMillis: Long = 5_000): String =
        TopologyProcessRunner.run({ process }, timeoutMillis, cleanupWaitMillis = 250)

    // ------------------------------------------------------------ base cases

    @Test
    fun `successful run returns filtered output with no live process or reader`() {
        val process = AdversarialProcess(output = validOutput)

        val result = run(process)

        assertTrue(result.contains("#55"))
        assertTrue(result.contains("mDeskRootTaskType=activatable"))
        assertFalse(process.isAlive)
        assertNoReaderThreadAlive()
    }

    @Test
    fun `filtering happens before the result crosses the seam`() {
        val process = AdversarialProcess(
            output = validOutput + "        Intent { act=android.intent.action.MAIN }\n",
        )

        assertFalse(run(process).contains("Intent"))
    }

    @Test
    fun `non-zero exit status is an explicit failure`() {
        val process = AdversarialProcess(output = validOutput, exitCode = 1)

        val e = assertThrows(IllegalStateException::class.java) { run(process) }

        assertTrue(e.message!!.contains("exited with code 1"))
        assertNoReaderThreadAlive()
    }

    @Test
    fun `launch failure is an explicit failure`() {
        val e = assertThrows(IllegalStateException::class.java) {
            TopologyProcessRunner.run({ error("boom") }, timeoutMillis = 1_000)
        }

        assertTrue(e.message!!.contains("Failed to start"))
    }

    // ------------------------------------------------- blocked-stream cases

    @Test
    fun `stream blocked after process exit is unblocked by close and never yields success`() {
        // The process exits cleanly, but its stream stays blocked and only
        // an explicit close() releases it: success must NOT be reported
        // because reader completion was uncertain until cleanup intervened.
        val process = AdversarialProcess(output = validOutput, streamIgnoresExit = true)

        val e = assertThrows(IllegalStateException::class.java) { run(process) }

        assertTrue(
            "unexpected: ${e.message}",
            e.message!!.contains("read incomplete") || e.message!!.contains("read failed"),
        )
        assertFalse(process.isAlive)
        assertNoReaderThreadAlive()
    }

    @Test
    fun `timeout destroys and reaps even when destroy is a no-op`() {
        val process = AdversarialProcess(
            output = validOutput,
            exitsOnItsOwn = false,
            destroyWorks = false, // only destroyForcibly() actually kills it
        )

        val startedAt = System.nanoTime()
        val e = assertThrows(IllegalStateException::class.java) { run(process, timeoutMillis = 200) }
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertTrue(e.message!!.contains("timed out"))
        assertTrue(process.destroyCalled.get())
        assertTrue(process.forciblyCalled.get())
        assertFalse(process.isAlive)
        assertTrue("took ${elapsedMillis}ms", elapsedMillis < 5_000)
        assertNoReaderThreadAlive()
    }

    @Test
    fun `read failure while the process is still running is the reported failure`() {
        val process = AdversarialProcess(
            output = "Display #0 (activities from top to bottom):\n",
            exitsOnItsOwn = false,
            streamFailure = IOException("pipe corrupted"),
        )

        val e = assertThrows(IllegalStateException::class.java) { run(process) }

        assertTrue(e.message!!.contains("pipe corrupted"))
        assertTrue(process.destroyCalled.get() || process.forciblyCalled.get())
        assertFalse(process.isAlive)
        assertNoReaderThreadAlive()
    }

    @Test
    fun `overflow while the process is still running fails closed and kills the child`() {
        val process = AdversarialProcess(output = oversizedOutput(), exitsOnItsOwn = false)

        val e = assertThrows(IllegalStateException::class.java) { run(process, timeoutMillis = 10_000) }

        assertTrue(e.message!!.contains("exceeded"))
        assertFalse(process.isAlive)
        assertNoReaderThreadAlive()
    }

    @Test
    fun `caller interruption still cleans up and restores the interrupt flag`() {
        val process = AdversarialProcess(
            output = validOutput,
            exitsOnItsOwn = false,
            streamIgnoresExit = true,
        )

        Thread.currentThread().interrupt() // already-set flag must not skip cleanup
        val e = assertThrows(IllegalStateException::class.java) { run(process) }
        val flagRestored = Thread.interrupted() // reads AND clears for later tests

        assertTrue(e.message!!.contains("Interrupted"))
        assertTrue(flagRestored)
        // Cleanup completed despite the interruption: process dead, reader done.
        assertFalse(process.isAlive)
        assertNoReaderThreadAlive()
    }

    @Test
    fun `unkillable process is an explicit cleanup failure`() {
        val process = AdversarialProcess(
            output = validOutput,
            exitsOnItsOwn = false,
            destroyWorks = false,
            forciblyWorks = false,
        )

        val e = assertThrows(IllegalStateException::class.java) { run(process, timeoutMillis = 200) }

        assertTrue("was: ${e.message}", e.message!!.contains("cleanup failed"))
        assertTrue(e.message!!.contains("still alive"))
        assertTrue(process.destroyCalled.get())
        assertTrue(process.forciblyCalled.get())
    }

    // ------------------------------------- timeout vs reader failure racing

    /**
     * Deterministic fake for the exact Codex race, sleep-free. Ordering is
     * enforced by latches and thread-join (both event-based):
     *
     *  1. the reader blocks in read() before any failure is available;
     *  2. the runner calls timed waitFor(); the fake releases the failure
     *     and then JOINS the reader thread — so the failure has fully
     *     OCCURRED (and been classified at the operation boundary) before
     *     waitFor reports "still running";
     *  3. the runner is invoked with timeoutMillis=0, so the very next
     *     deadline check after that waitFor deterministically records the
     *     TIMEOUT DECISION;
     *  4. only then does cleanup run — its first observable side effect on
     *     the fake (destroy) is recorded strictly after the reader died.
     *
     * The recorded event order therefore proves:
     *   FAILURE_RELEASED → FAILURE_OCCURRED → READER_TERMINATED →
     *   (timeout decision) → DISTURBANCE_DESTROY
     * and the final result must be the genuine failure, not the timeout.
     */
    private class TimeoutRaceProcess(
        private val failureMode: FailureMode,
        oversized: String = "",
    ) : Process() {
        enum class FailureMode { GENUINE_IO_EXCEPTION, OVERFLOW_DATA }

        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        private val releaseFailure = CountDownLatch(1)
        private val exited = CountDownLatch(1)
        private val firstTimedWait = AtomicBoolean(true)
        private val oversizedBytes = oversized.toByteArray()

        private val stdout = object : InputStream() {
            private var position = 0

            override fun read(): Int {
                val single = ByteArray(1)
                return if (read(single, 0, 1) == -1) -1 else single[0].toInt()
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (failureMode == FailureMode.OVERFLOW_DATA && position > 0) {
                    // Overflow data flows without further gating once released.
                    if (position < oversizedBytes.size) return serve(b, off, len)
                    return -1
                }
                while (releaseFailure.count > 0) {
                    try {
                        releaseFailure.await()
                    } catch (e: InterruptedException) {
                        // The release, once granted, is always deliverable.
                    }
                }
                return when (failureMode) {
                    FailureMode.GENUINE_IO_EXCEPTION -> {
                        events += "FAILURE_OCCURRED"
                        throw IOException("genuine pipe failure")
                    }
                    FailureMode.OVERFLOW_DATA -> {
                        if (position == 0) events += "FAILURE_OCCURRED"
                        if (position < oversizedBytes.size) serve(b, off, len) else -1
                    }
                }
            }

            private fun serve(b: ByteArray, off: Int, len: Int): Int {
                val n = minOf(len, oversizedBytes.size - position)
                System.arraycopy(oversizedBytes, position, b, off, n)
                position += n
                return n
            }
        }

        override fun getInputStream(): InputStream = stdout
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getErrorStream(): InputStream = InputStream.nullInputStream()

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            if (firstTimedWait.getAndSet(false)) {
                events += "FAILURE_RELEASED"
                releaseFailure.countDown()
                // Event-based wait (no sleep): the failure has fully occurred,
                // been classified at the boundary, and been published by the
                // time the runner can reach its timeout decision.
                Thread.getAllStackTraces().keys
                    .filter { it.name == "dexzones-topology-filter" }
                    .forEach { it.join(10_000) }
                events += "READER_TERMINATED"
                return false
            }
            return exited.count == 0L
        }

        override fun waitFor(): Int {
            exited.await()
            return exitValue()
        }

        override fun exitValue(): Int {
            if (exited.count > 0) throw IllegalThreadStateException("still running")
            return 137
        }

        override fun destroy() {
            events += "DISTURBANCE_DESTROY"
            exited.countDown()
        }

        override fun destroyForcibly(): Process {
            destroy()
            return this
        }
    }

    @Test
    fun `genuine io exception after the timeout decision is never masked by timeout`() {
        val process = TimeoutRaceProcess(TimeoutRaceProcess.FailureMode.GENUINE_IO_EXCEPTION)

        // timeoutMillis=0: the deadline check right after the fake's first
        // waitFor deterministically records the timeout decision — which by
        // then is strictly after the genuine failure occurred.
        val e = assertThrows(IllegalStateException::class.java) {
            TopologyProcessRunner.run({ process }, timeoutMillis = 0, cleanupWaitMillis = 2_000)
        }

        assertTrue("was: ${e.message}", e.message!!.contains("genuine pipe failure"))
        assertFalse(e.message!!.contains("timed out"))
        // The protocol ordering the fake observed: failure released and
        // occurred, reader terminated (classification + publication done),
        // and only afterwards did cleanup disturbance begin.
        assertEquals(
            listOf("FAILURE_RELEASED", "FAILURE_OCCURRED", "READER_TERMINATED", "DISTURBANCE_DESTROY"),
            process.events.toList(),
        )
        assertFalse(process.isAlive)
        assertNoReaderThreadAlive()
    }

    @Test
    fun `genuine overflow after the timeout decision is never masked by timeout`() {
        val process = TimeoutRaceProcess(
            TimeoutRaceProcess.FailureMode.OVERFLOW_DATA,
            oversized = oversizedOutput(),
        )

        val e = assertThrows(IllegalStateException::class.java) {
            TopologyProcessRunner.run({ process }, timeoutMillis = 0, cleanupWaitMillis = 2_000)
        }

        assertTrue("was: ${e.message}", e.message!!.contains("exceeded"))
        assertFalse(e.message!!.contains("timed out"))
        assertEquals(
            listOf("FAILURE_RELEASED", "FAILURE_OCCURRED", "READER_TERMINATED", "DISTURBANCE_DESTROY"),
            process.events.toList(),
        )
        assertFalse(process.isAlive)
        assertNoReaderThreadAlive()
    }

    /**
     * Fake for the exact former defect, end-to-end: the reader must be HELD
     * between classification (failureOccurred) and publication (outer
     * catch), across cleanup's beginDisturbance(). All ordering is enforced
     * by latches — no sleeps, no scheduler luck, no early reader join.
     */
    private class HeldPublicationRaceProcess : Process() {
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val releaseFailure = CountDownLatch(1)
        val classificationFrozen = CountDownLatch(1)
        val disturbanceBegun = CountDownLatch(1)
        private val exited = CountDownLatch(1)
        private val firstTimedWait = AtomicBoolean(true)

        private val stdout = object : InputStream() {
            override fun read(): Int {
                awaitUninterruptibly(releaseFailure)
                events += "FAILURE_OCCURRED"
                throw IOException("genuine pipe failure")
            }
        }

        override fun getInputStream(): InputStream = stdout
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getErrorStream(): InputStream = InputStream.nullInputStream()

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            if (firstTimedWait.getAndSet(false)) {
                events += "FAILURE_RELEASED"
                releaseFailure.countDown()
                // Event-based: return only once the classification has been
                // frozen AND the hook is holding publication — the runner's
                // timeout decision lands strictly inside the held window.
                awaitUninterruptibly(classificationFrozen)
                events += "TIMED_WAIT_RETURNING"
                return false
            }
            return exited.count == 0L
        }

        override fun waitFor(): Int {
            exited.await()
            return exitValue()
        }

        override fun exitValue(): Int {
            if (exited.count > 0) throw IllegalThreadStateException("still running")
            return 137
        }

        override fun destroy() {
            events += "DISTURBANCE_DESTROY"
            disturbanceBegun.countDown()
            exited.countDown()
        }

        override fun destroyForcibly(): Process {
            destroy()
            return this
        }
    }

    private companion object {
        fun awaitUninterruptibly(latch: CountDownLatch) {
            while (latch.count > 0) {
                try {
                    latch.await()
                } catch (e: InterruptedException) {
                    // Ordering latches must not be broken by interrupts.
                }
            }
        }
    }

    @Test
    fun `held publication across cleanup disturbance still reports the genuine failure`() {
        // The exact former race, end-to-end through ClassifyingInputStream,
        // ReaderFailureProtocol, the outer reader catch, cleanup, and final
        // selection:
        //   A/B reader enters the read and it throws a genuine IOException
        //   C/D failureOccurred() freezes the classification as GENUINE
        //   E   the hook BLOCKS propagation (publication has not happened)
        //   F   the runner records its timeout decision (timeoutMillis=0)
        //   G/H cleanup calls beginDisturbance(); observed via destroy()
        //   I/J the hook is released; the exception propagates
        //   K   the outer catch publishes the already-frozen failure
        //   L/M cleanup completes; the genuine IOException beats the timeout
        // Under the previous classify-at-publication implementation, the
        // disturbance flag would already be true at step K, the failure
        // would flip to cleanup-induced, and the timeout would win.
        val process = HeldPublicationRaceProcess()
        val frozen = java.util.concurrent.atomic.AtomicReference<TopologyProcessRunner.ClassifiedFailure>()
        val hook = TopologyProcessRunner.ReaderClassificationHook { classified ->
            frozen.set(classified)
            process.events += "CLASSIFICATION_FROZEN(genuine=${classified.genuine})"
            process.classificationFrozen.countDown()
            // Hold publication until cleanup disturbance has provably begun.
            awaitUninterruptibly(process.disturbanceBegun)
            process.events += "PUBLICATION_RELEASED"
        }

        val e = assertThrows(IllegalStateException::class.java) {
            TopologyProcessRunner.run(
                { process },
                timeoutMillis = 0,
                cleanupWaitMillis = 2_000,
                classificationHook = hook,
            )
        }

        // M: the genuine failure won, despite publication after disturbance.
        assertTrue("was: ${e.message}", e.message!!.contains("genuine pipe failure"))
        assertFalse(e.message!!.contains("timed out"))
        // D: the classification was frozen genuine before the hold began.
        assertTrue(frozen.get()!!.genuine)
        assertTrue(frozen.get()!!.cause is IOException)
        // The explicit happens-before chain:
        //   failureOccurred -> (timeout decision) -> beginDisturbance -> publish
        assertEquals(
            listOf(
                "FAILURE_RELEASED",
                "FAILURE_OCCURRED",
                "CLASSIFICATION_FROZEN(genuine=true)",
                "TIMED_WAIT_RETURNING",
                "DISTURBANCE_DESTROY",
                "PUBLICATION_RELEASED",
            ),
            process.events.toList(),
        )
        assertFalse(process.isAlive)
        assertNoReaderThreadAlive()
    }

    // -------------------------------------- the synchronization protocol

    @Test
    fun `protocol never reclassifies a pre-disturbance failure as cleanup-induced`() {
        // The exact race: the failure OCCURS (classification frozen) before
        // disturbance, but its PUBLICATION is delayed until after cleanup
        // has begun. Under classify-at-publication semantics (the previous
        // implementation) this failure would flip to cleanup-induced and
        // timeout would win; the protocol must keep it genuine.
        val protocol = TopologyProcessRunner.ReaderFailureProtocol()

        val classified = protocol.failureOccurred(IOException("genuine pipe failure"))
        protocol.beginDisturbance() // cleanup starts...
        protocol.publish(classified) // ...and only then does publication land

        assertEquals("genuine pipe failure", protocol.genuineFailure()?.message)
        assertTrue(protocol.cleanupInducedFailure() == null)
    }

    @Test
    fun `protocol classifies post-disturbance failures as cleanup-induced`() {
        val protocol = TopologyProcessRunner.ReaderFailureProtocol()

        protocol.beginDisturbance()
        protocol.publish(protocol.failureOccurred(IOException("stream closed by cleanup")))

        assertTrue(protocol.genuineFailure() == null)
        assertEquals("stream closed by cleanup", protocol.cleanupInducedFailure()?.message)
    }

    @Test
    fun `protocol keeps overflow genuine even when it occurs after disturbance`() {
        val protocol = TopologyProcessRunner.ReaderFailureProtocol()

        protocol.beginDisturbance()
        protocol.publish(
            protocol.failureOccurred(TopologyOutputTooLargeException(400_000)),
        )

        assertTrue(protocol.genuineFailure() is TopologyOutputTooLargeException)
        assertTrue(protocol.cleanupInducedFailure() == null)
    }

    @Test
    fun `protocol keeps the first failure of each class`() {
        val protocol = TopologyProcessRunner.ReaderFailureProtocol()

        val first = protocol.failureOccurred(IOException("first genuine"))
        val second = protocol.failureOccurred(IOException("second genuine"))
        protocol.publish(first)
        protocol.publish(second)

        assertEquals("first genuine", protocol.genuineFailure()?.message)
    }

    @Test
    fun `reader failure wins the race against timeout when both are pending`() {
        // Overflow data is available immediately while the child never
        // exits: the genuine overflow must be the reported failure, not the
        // pending timeout. (The exact timeout-decision boundary is exercised
        // deterministically by the latch-controlled race test above.)
        val process = AdversarialProcess(output = oversizedOutput(), exitsOnItsOwn = false)

        val e = assertThrows(IllegalStateException::class.java) { run(process, timeoutMillis = 2_000) }

        assertTrue("was: ${e.message}", e.message!!.contains("exceeded"))
        assertFalse(e.message!!.contains("timed out"))
        assertFalse(process.isAlive)
    }

    // ------------------------------------------- stream-close failure cases

    @Test
    fun `stdout close failure after successful execution is an explicit cleanup failure`() {
        // Call 1 is the reader's own close (succeeds); call 2 is cleanup's.
        val process = AdversarialProcess(
            output = validOutput,
            stdoutCloseFailure = IOException("stdout close boom"),
            stdoutCloseFailsFromCall = 2,
        )

        val e = assertThrows(IllegalStateException::class.java) { run(process) }

        assertTrue("was: ${e.message}", e.message!!.contains("cleanup failed"))
        assertTrue(e.message!!.contains("stdout"))
        assertNoReaderThreadAlive()
    }

    @Test
    fun `stdin close failure is recorded and remaining cleanup still runs`() {
        val process = AdversarialProcess(
            output = validOutput,
            stdinCloseFailure = IOException("stdin close boom"),
        )

        val e = assertThrows(IllegalStateException::class.java) { run(process) }

        assertTrue("was: ${e.message}", e.message!!.contains("cleanup failed"))
        assertTrue(e.message!!.contains("stdin"))
        // The other streams were still closed after the failure.
        assertTrue(process.stdoutCloseCalls.get() >= 1)
        assertEquals(1, process.stderrCloseCalls.get())
        assertNoReaderThreadAlive()
    }

    @Test
    fun `stderr close failure is recorded and remaining cleanup still runs`() {
        val process = AdversarialProcess(
            output = validOutput,
            stderrCloseFailure = IOException("stderr close boom"),
        )

        val e = assertThrows(IllegalStateException::class.java) { run(process) }

        assertTrue("was: ${e.message}", e.message!!.contains("cleanup failed"))
        assertTrue(e.message!!.contains("stderr"))
        assertTrue(process.stdinCloseCalls.get() >= 1)
        assertTrue(process.stdoutCloseCalls.get() >= 1)
        assertNoReaderThreadAlive()
    }

    @Test
    fun `stdout close failure with a still-blocked reader is explicit and bounded`() {
        // Pathological: stdout close throws AND does not unblock the reader,
        // and the blocked read ignores interruption. Contract: bounded
        // cleanup, surviving reader detected, explicit cleanup failure with
        // both problems reported — never success, never a wedged runner.
        val process = AdversarialProcess(
            output = validOutput,
            streamIgnoresExit = true,
            readIgnoresInterrupt = true,
            stdoutCloseFailure = IOException("stdout stuck"),
            stdoutCloseUnblocks = false,
        )

        val startedAt = System.nanoTime()
        val e = assertThrows(IllegalStateException::class.java) { run(process) }
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertTrue("was: ${e.message}", e.message!!.contains("cleanup failed"))
        assertTrue(e.message!!.contains("stdout"))
        assertTrue(e.message!!.contains("reader did not terminate"))
        assertTrue("took ${elapsedMillis}ms", elapsedMillis < 5_000)

        // Hygiene: release the wedged daemon reader so it cannot leak into
        // other tests' thread assertions.
        process.unwedgeStream()
        Thread.getAllStackTraces().keys
            .filter { it.name == "dexzones-topology-filter" }
            .forEach { it.join(1_000) }
    }

    @Test
    fun `genuine overflow outranks a simultaneous stream-close failure`() {
        val process = AdversarialProcess(
            output = oversizedOutput(),
            exitsOnItsOwn = false,
            stdinCloseFailure = IOException("stdin close boom"),
        )

        val e = assertThrows(IllegalStateException::class.java) { run(process, timeoutMillis = 10_000) }

        assertTrue("was: ${e.message}", e.message!!.contains("exceeded"))
        assertFalse(e.message!!.contains("cleanup failed"))
        assertFalse(process.isAlive)
    }

    @Test
    fun `cleanup failure outranks timeout`() {
        val process = AdversarialProcess(
            output = validOutput,
            exitsOnItsOwn = false,
            stdinCloseFailure = IOException("stdin close boom"),
        )

        val e = assertThrows(IllegalStateException::class.java) { run(process, timeoutMillis = 200) }

        assertTrue("was: ${e.message}", e.message!!.contains("cleanup failed"))
        assertTrue(e.message!!.contains("stdin"))
        assertFalse(e.message!!.contains("timed out"))
        assertFalse(process.isAlive)
        assertNoReaderThreadAlive()
    }
}
