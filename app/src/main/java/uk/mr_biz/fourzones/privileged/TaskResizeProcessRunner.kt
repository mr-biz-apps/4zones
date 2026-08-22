package uk.mr_biz.fourzones.privileged

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs the fixed task-resize process with the hardened lifecycle used by
 * [TopologyProcessRunner]: stdout is drained concurrently with the timed wait
 * (so a filling pipe cannot deadlock the child), and EVERY exit path runs one
 * centralized, uninterruptible, bounded cleanup — destroy → bounded reap →
 * destroyForcibly → bounded reap → verify dead; close all process streams
 * recording any failure; interrupt and bounded-join the reader. Output is
 * drained only to unblock the child and is never returned, logged, or
 * persisted.
 *
 * Fail closed: a process that survives destroyForcibly, a reader that survives
 * its bounded join, or a stream-close failure all yield STATUS_PROCESS_ERROR.
 * SUCCESS is selected only when the process exited with code 0, the reader
 * completed with no failure, and cleanup was clean. Caller interruption is
 * recorded, cleanup still runs uninterruptibly, and the interrupt flag is
 * restored only AFTER cleanup. Exit code 0 is not treated as proof the
 * geometry applied — postcondition verification happens separately.
 *
 * The launcher parameter is a test seam ONLY; it launches a caller-fixed
 * process and is never a general command interface.
 */
internal object TaskResizeProcessRunner {

    const val DEFAULT_TIMEOUT_MILLIS = 10_000L
    private const val DEFAULT_CLEANUP_WAIT_MILLIS = 2_000L
    private const val POLL_SLICE_MILLIS = 25L
    private const val MAX_DRAIN_CHARS = 64_000

    fun run(
        launch: () -> Process,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        cleanupWaitMillis: Long = DEFAULT_CLEANUP_WAIT_MILLIS,
    ): Int {
        val process = try {
            launch()
        } catch (e: Exception) {
            return TaskResizeCommand.STATUS_PROCESS_ERROR
        }

        val readFailed = AtomicReference<Throwable>()
        val reader = Thread({
            try {
                val stream = process.inputStream.bufferedReader()
                val buffer = CharArray(4096)
                var total = 0
                while (total < MAX_DRAIN_CHARS) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    total += n
                }
            } catch (t: Throwable) {
                readFailed.set(t)
            }
        }, "dexzones-resize-drain")
        reader.isDaemon = true
        reader.start()

        var timedOut = false
        var callerInterrupted = false

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (true) {
            if (readFailed.get() != null) break
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

        // On a clean exit, let the reader drain to EOF before cleanup.
        if (readFailed.get() == null && !timedOut && !callerInterrupted) {
            callerInterrupted = joinUninterruptibly(reader, cleanupWaitMillis) || callerInterrupted
        }

        val cleanup = cleanup(process, reader, cleanupWaitMillis)
        callerInterrupted = callerInterrupted || cleanup.interrupted

        val status = try {
            when {
                cleanup.problem != null -> TaskResizeCommand.STATUS_PROCESS_ERROR
                callerInterrupted -> TaskResizeCommand.STATUS_PROCESS_ERROR
                timedOut -> TaskResizeCommand.STATUS_TIMED_OUT
                readFailed.get() != null -> TaskResizeCommand.STATUS_PROCESS_ERROR
                process.exitValue() == 0 -> TaskResizeCommand.STATUS_SUCCESS
                else -> TaskResizeCommand.STATUS_COMMAND_FAILED
            }
        } catch (e: IllegalThreadStateException) {
            // Process not actually terminated despite no recorded problem —
            // fail closed rather than claim a result.
            TaskResizeCommand.STATUS_PROCESS_ERROR
        }

        if (callerInterrupted) Thread.currentThread().interrupt()
        return status
    }

    private class CleanupResult(val problem: String?, val interrupted: Boolean)

    private fun cleanup(process: Process, reader: Thread, waitMillis: Long): CleanupResult {
        var interrupted = false
        var problem: String? = null

        if (process.isAlive) {
            process.destroy()
            interrupted = waitForExitUninterruptibly(process, waitMillis) || interrupted
            if (process.isAlive) {
                process.destroyForcibly()
                interrupted = waitForExitUninterruptibly(process, waitMillis) || interrupted
            }
            if (process.isAlive) {
                problem = "process still alive after destroy and destroyForcibly"
            }
        }

        // Close all streams (closing stdout unblocks a stuck reader); record
        // the first failure. Every stream is still attempted.
        problem = closeRecording(problem, "stdin") { process.outputStream.close() }
        problem = closeRecording(problem, "stdout") { process.inputStream.close() }
        problem = closeRecording(problem, "stderr") { process.errorStream.close() }

        reader.interrupt()
        interrupted = joinUninterruptibly(reader, waitMillis) || interrupted
        if (reader.isAlive) {
            problem = problem ?: "output reader did not terminate after interrupt and stream close"
        }

        return CleanupResult(problem, interrupted)
    }

    private inline fun closeRecording(existing: String?, name: String, close: () -> Unit): String? {
        return try {
            close()
            existing
        } catch (t: Throwable) {
            existing ?: "$name close failed"
        }
    }

    private fun waitForExitUninterruptibly(process: Process, waitMillis: Long): Boolean {
        var interrupted = false
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis)
        while (process.isAlive) {
            val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (remaining <= 0) break
            try {
                process.waitFor(minOf(remaining, POLL_SLICE_MILLIS), TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                interrupted = true
            }
        }
        return interrupted
    }

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
