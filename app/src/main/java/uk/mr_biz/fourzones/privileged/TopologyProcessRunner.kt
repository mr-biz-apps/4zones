package uk.mr_biz.fourzones.privileged

import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs the (fixed) topology dump process with a real timeout and guaranteed
 * cleanup.
 *
 * Guarantees:
 *  - stdout is consumed CONCURRENTLY with the timed wait, streamed
 *    line-by-line through [TopologyDumpFilter] so memory stays bounded and
 *    the raw dump is never held whole;
 *  - stderr is merged into stdout at the single production call site
 *    (redirectErrorStream), so a full stderr pipe cannot deadlock the child;
 *  - reader failures are classified through the synchronized
 *    [ReaderFailureProtocol]: classification is FROZEN at the moment the
 *    failure is observed at the operation boundary (the stream wrapper
 *    directly around each underlying read/close, or the reader's outer
 *    catch for filter failures), and publication may happen arbitrarily
 *    later without cleanup ever being able to reclassify it — so a genuine
 *    overflow or read failure can never be masked by a concurrent timeout;
 *  - EVERY exit path runs one centralized cleanup: destroy → bounded reap →
 *    destroyForcibly → bounded reap → verify dead; close ALL process
 *    streams, RECORDING any close failure as a cleanup failure (closing
 *    stdout is what unblocks a reader stuck in read); interrupt the reader
 *    and join it bounded — a reader that still won't terminate is an
 *    EXPLICIT cleanup failure, never silently abandoned;
 *  - cleanup waits are uninterruptible: caller interruption is recorded,
 *    consumed while cleanup completes, and the interrupt flag is restored
 *    before returning or throwing;
 *  - the failure decision is made from the FINAL post-cleanup protocol
 *    state, with precedence: genuine reader/filter failure (incl.
 *    [TopologyOutputTooLargeException]) > cleanup failure > caller
 *    interruption > timeout > non-zero exit > cleanup-induced reader
 *    failure > missing output;
 *  - success is returned ONLY when the process exited normally with code 0,
 *    the reader completed with no failure of either kind, the filtered
 *    result exists, and every cleanup step succeeded.
 *
 * The launcher parameter is a seam ONLY so tests can exercise timeout and
 * cleanup without invoking real dumpsys. It launches a caller-fixed process;
 * it is not, and must not become, an arbitrary-command abstraction — the
 * single production call site hard-codes the dumpsys invocation.
 */
internal object TopologyProcessRunner {

    const val DEFAULT_TIMEOUT_MILLIS = 15_000L
    private const val DEFAULT_CLEANUP_WAIT_MILLIS = 2_000L
    private const val POLL_SLICE_MILLIS = 25L

    /** A reader failure whose classification was frozen at occurrence time. */
    internal class ClassifiedFailure(val cause: Throwable, val genuine: Boolean)

    /**
     * TEST-ONLY seam: invoked on the reader thread immediately after
     * [ReaderFailureProtocol.failureOccurred] has frozen a classification
     * and immediately before the [ClassifiedReadException] carrying it is
     * allowed to propagate toward the outer reader catch (publication).
     * Never invoked while holding the protocol monitor. Production passes
     * null; the hook exists solely so tests can deterministically hold the
     * reader between classification and publication — it must never grow
     * into instrumentation and is not part of any backend/privilege API.
     */
    internal fun interface ReaderClassificationHook {
        fun afterFailureClassifiedBeforePropagation(classified: ClassifiedFailure)
    }

    /** Carrier that moves a frozen classification up through the reader stack. */
    private class ClassifiedReadException(val classified: ClassifiedFailure) :
        RuntimeException(classified.cause)

    /**
     * Synchronized reader/cleanup coordination protocol.
     *
     * The invariant this exists for: once an independent reader operation
     * has failed, cleanup can NEVER subsequently reclassify that failure as
     * cleanup-induced. It holds by construction, not by timing:
     *
     *  - [failureOccurred] is called at the operation boundary and freezes
     *    the classification INSIDE the returned [ClassifiedFailure], under
     *    the same lock [beginDisturbance] uses — so each failure is ordered
     *    either strictly before or strictly after the disturbance, atomically;
     *  - [publish] merely files an already-classified failure; it reads no
     *    disturbance state, so arbitrary delay between occurrence and
     *    publication (the race Codex identified) cannot change the outcome;
     *  - [TopologyOutputTooLargeException] is genuine unconditionally — the
     *    filter only throws it after retaining real over-limit output, no
     *    matter what cleanup is doing;
     *  - conversely, a failure first observed after [beginDisturbance] is
     *    cleanup-induced: cleanup's destroy/close actions are the only new
     *    failure sources introduced at that point, so a disturbance-era
     *    stream failure must not masquerade as an independent read failure.
     *
     * The monitor is held only for the constant-time classification and
     * bookkeeping calls, never across a blocking read.
     */
    internal class ReaderFailureProtocol {
        private val lock = Any()
        private var disturbanceStarted = false
        private var genuine: Throwable? = null
        private var cleanupInduced: Throwable? = null

        /** Cleanup calls this immediately before disturbing the process/streams. */
        fun beginDisturbance() {
            synchronized(lock) { disturbanceStarted = true }
        }

        /**
         * Observes a failure at the operation boundary and freezes its
         * classification. Call as close to the failing operation as the
         * code can get; the classification can never change afterwards.
         */
        fun failureOccurred(failure: Throwable): ClassifiedFailure = synchronized(lock) {
            ClassifiedFailure(
                cause = failure,
                genuine = failure is TopologyOutputTooLargeException || !disturbanceStarted,
            )
        }

        /** Files a frozen classification. May run arbitrarily late; never reclassifies. */
        fun publish(classified: ClassifiedFailure) {
            synchronized(lock) {
                if (classified.genuine) {
                    if (genuine == null) genuine = classified.cause
                } else {
                    if (cleanupInduced == null) cleanupInduced = classified.cause
                }
            }
        }

        fun genuineFailure(): Throwable? = synchronized(lock) { genuine }

        fun cleanupInducedFailure(): Throwable? = synchronized(lock) { cleanupInduced }
    }

    /**
     * The operation boundary for stream failures: classification happens in
     * the frame directly around each underlying read/close, before the
     * exception travels up through the reader/decoder machinery — so a
     * descheduled unwinding can no longer let cleanup's disturbance flag
     * flip underneath an already-failed operation.
     */
    private class ClassifyingInputStream(
        private val underlying: InputStream,
        private val protocol: ReaderFailureProtocol,
        private val classificationHook: ReaderClassificationHook?,
    ) : InputStream() {
        override fun read(): Int = boundary { underlying.read() }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            boundary { underlying.read(b, off, len) }

        override fun close() = boundary { underlying.close() }

        private inline fun <T> boundary(op: () -> T): T = try {
            op()
        } catch (t: Throwable) {
            val classified = protocol.failureOccurred(t)
            // Test-only gate between classification (frozen above, outside
            // the protocol monitor by now) and propagation/publication.
            classificationHook?.afterFailureClassifiedBeforePropagation(classified)
            throw ClassifiedReadException(classified)
        }
    }

    fun run(
        launch: () -> Process,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        cleanupWaitMillis: Long = DEFAULT_CLEANUP_WAIT_MILLIS,
        classificationHook: ReaderClassificationHook? = null,
    ): String {
        val process = try {
            launch()
        } catch (e: Exception) {
            throw IllegalStateException("Failed to start topology dump process: ${e.message}")
        }

        val protocol = ReaderFailureProtocol()
        val filteredOutput = AtomicReference<String>()
        val reader = Thread({
            try {
                ClassifyingInputStream(process.inputStream, protocol, classificationHook)
                    .bufferedReader().useLines { lines ->
                        filteredOutput.set(TopologyDumpFilter.filterLines(lines))
                    }
            } catch (t: Throwable) {
                // Stream failures arrive pre-classified from the boundary;
                // filter failures (notably overflow, which is genuine by
                // rule) are classified here, as early as they are visible.
                val classified = (t as? ClassifiedReadException)?.classified
                    ?: protocol.failureOccurred(t)
                protocol.publish(classified)
            }
        }, "dexzones-topology-filter")
        reader.isDaemon = true
        reader.start()

        var timedOut = false
        var callerInterrupted = false

        // Phase 1: wait for process exit, genuine reader failure, timeout, or
        // caller interruption — whichever comes first. Short slices keep a
        // reader failure (e.g. overflow) from waiting behind a running child.
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (true) {
            if (protocol.genuineFailure() != null) break
            val exited = try {
                process.waitFor(POLL_SLICE_MILLIS, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                callerInterrupted = true
                break
            }
            if (exited) break
            if (System.nanoTime() >= deadline) {
                timedOut = true
                break
            }
        }

        // Phase 1.5: on a clean exit, give the reader a bounded chance to
        // drain buffered output naturally before cleanup disturbs the streams.
        if (protocol.genuineFailure() == null && !timedOut && !callerInterrupted) {
            callerInterrupted = joinUninterruptibly(reader, cleanupWaitMillis) || callerInterrupted
        }

        // Phase 2: centralized cleanup, always, uninterruptibly.
        val cleanup = cleanup(process, reader, cleanupWaitMillis, protocol)
        callerInterrupted = callerInterrupted || cleanup.interrupted

        // Phase 3: decide from the FINAL protocol state. The reader has
        // terminated by now (or its survival is itself a cleanup failure),
        // and classifications were frozen at occurrence — there is no
        // pre-cleanup snapshot whose timing can affect the result.
        try {
            protocol.genuineFailure()?.let { throw it.asFailure("Topology output read failed") }
            cleanup.problem?.let { throw IllegalStateException("Topology cleanup failed: $it") }
            if (callerInterrupted) {
                throw IllegalStateException("Interrupted while reading topology dump")
            }
            if (timedOut) {
                throw IllegalStateException(
                    "Topology dump timed out after $timeoutMillis ms; process destroyed",
                )
            }
            val exitValue = process.exitValue()
            if (exitValue != 0) {
                throw IllegalStateException("Topology dump exited with code $exitValue")
            }
            protocol.cleanupInducedFailure()?.let {
                throw it.asFailure("Topology output read incomplete")
            }
            return filteredOutput.get()
                ?: throw IllegalStateException("Topology dump produced no output")
        } finally {
            if (callerInterrupted) Thread.currentThread().interrupt()
        }
    }

    private fun Throwable.asFailure(prefix: String): IllegalStateException =
        this as? IllegalStateException ?: IllegalStateException("$prefix: $message")

    private class CleanupResult(val problem: String?, val interrupted: Boolean)

    /**
     * The single termination path: kill and reap the process (never assuming
     * destroy() works), close EVERY process stream recording any close
     * failure, then interrupt and join the reader. Every failed step is
     * recorded — the first becomes the primary cleanup failure with the rest
     * summarized — and later steps still run. Runs to completion even if the
     * calling thread is interrupted; interruption is reported back so the
     * caller can restore the flag.
     */
    private fun cleanup(
        process: Process,
        reader: Thread,
        waitMillis: Long,
        protocol: ReaderFailureProtocol,
    ): CleanupResult {
        var interrupted = false
        val problems = mutableListOf<String>()

        // From here on the process and its streams are being disturbed;
        // failures first observed by the reader after this point are
        // classified cleanup-induced (except overflow, which is genuine by
        // rule). Set before termination, not just before the explicit close
        // calls, because destroy() may itself close the pipes on some
        // platforms. Failures already frozen as genuine stay genuine.
        protocol.beginDisturbance()

        if (process.isAlive) {
            process.destroy()
            interrupted = waitForExitUninterruptibly(process, waitMillis) || interrupted
            if (process.isAlive) {
                process.destroyForcibly()
                interrupted = waitForExitUninterruptibly(process, waitMillis) || interrupted
            }
            if (process.isAlive) {
                problems += "process still alive after destroy and destroyForcibly"
            }
        }

        // Close all streams; closing stdout unblocks a reader stuck in read.
        // Production merges stderr into stdout, but close everything anyway.
        // A close failure is a cleanup failure — recorded, never swallowed —
        // and the remaining streams are still closed.
        closeRecordingFailure("stdin (process output stream)", problems) {
            process.outputStream.close()
        }
        closeRecordingFailure("stdout (process input stream)", problems) {
            process.inputStream.close()
        }
        closeRecordingFailure("stderr (process error stream)", problems) {
            process.errorStream.close()
        }

        reader.interrupt()
        interrupted = joinUninterruptibly(reader, waitMillis) || interrupted
        if (reader.isAlive) {
            problems += "output reader did not terminate after interrupt and stream close"
        }

        val problem = when {
            problems.isEmpty() -> null
            problems.size == 1 -> problems.single()
            else -> problems.first() +
                " (+${problems.size - 1} more: ${problems.drop(1).joinToString("; ")})"
        }
        return CleanupResult(problem, interrupted)
    }

    private inline fun closeRecordingFailure(
        name: String,
        problems: MutableList<String>,
        close: () -> Unit,
    ) {
        try {
            close()
        } catch (t: Throwable) {
            problems += "$name close failed: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    /** Bounded process wait that survives interruption. Returns whether interruption occurred. */
    private fun waitForExitUninterruptibly(process: Process, waitMillis: Long): Boolean {
        var interrupted = false
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis)
        while (process.isAlive) {
            val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (remaining <= 0) break
            try {
                process.waitFor(minOf(remaining, POLL_SLICE_MILLIS), TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                // Consume so an already-set flag cannot skip cleanup waits;
                // reported back and restored by the caller.
                interrupted = true
            }
        }
        return interrupted
    }

    /** Bounded thread join that survives interruption. Returns whether interruption occurred. */
    private fun joinUninterruptibly(thread: Thread, waitMillis: Long): Boolean {
        var interrupted = false
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis)
        while (thread.isAlive) {
            val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (remaining <= 0) break
            try {
                thread.join(minOf(remaining, POLL_SLICE_MILLIS))
            } catch (e: InterruptedException) {
                interrupted = true
            }
        }
        return interrupted
    }
}
