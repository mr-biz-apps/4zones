package uk.mr_biz.fourzones.privileged

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adversarial runner tests via the launcher seam — never invokes adb/cmd/
 * Shizuku. Covers blocked streams, ignored destroy, unkillable children,
 * stream-close failures, and caller interruption.
 */
class TaskResizeProcessRunnerTest {

    private class AdversarialProcess(
        output: String = "",
        private val exitCode: Int = 0,
        exitsOnItsOwn: Boolean = true,
        private val streamIgnoresExit: Boolean = false,
        private val destroyWorks: Boolean = true,
        private val forciblyWorks: Boolean = true,
        private val stdoutCloseFails: Boolean = false,
        private val stdoutCloseUnblocks: Boolean = true,
        private val readIgnoresInterrupt: Boolean = false,
    ) : Process() {
        val destroyCalled = AtomicBoolean(false)
        val forciblyCalled = AtomicBoolean(false)
        private var killed = false
        private val exited = CountDownLatch(if (exitsOnItsOwn) 0 else 1)
        private val streamClosed = CountDownLatch(1)
        private val bytes = output.toByteArray()

        fun unwedge() = streamClosed.countDown()

        private val stdout = object : InputStream() {
            private var pos = 0
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) == -1) -1 else one[0].toInt()
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (streamClosed.count == 0L) return -1 // closed => clean EOF
                if (pos < bytes.size) {
                    val n = minOf(len, bytes.size - pos)
                    System.arraycopy(bytes, pos, b, off, n); pos += n; return n
                }
                if (streamIgnoresExit) {
                    // Blocked until close; a normal close unblocks to a clean
                    // EOF. When close is configured to fail and not unblock,
                    // the (interrupt-ignoring) reader stays blocked, which the
                    // runner must detect as a bounded cleanup failure.
                    awaitBlocking(streamClosed)
                    return -1
                }
                awaitBlocking(exited)
                return -1
            }
            private fun awaitBlocking(latch: CountDownLatch) {
                if (readIgnoresInterrupt) {
                    while (latch.count > 0) try { latch.await() } catch (e: InterruptedException) { /* ignore */ }
                } else {
                    latch.await()
                }
            }
            override fun close() {
                if (!stdoutCloseFails || stdoutCloseUnblocks) streamClosed.countDown()
                if (stdoutCloseFails) throw IOException("stdout close boom")
            }
        }

        override fun getInputStream(): InputStream = stdout
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getErrorStream(): InputStream = InputStream.nullInputStream()
        override fun waitFor(): Int { exited.await(); return exitValue() }
        override fun exitValue(): Int {
            if (exited.count > 0) throw IllegalThreadStateException("running")
            return if (killed) 137 else exitCode
        }
        override fun destroy() { destroyCalled.set(true); if (destroyWorks) kill() }
        override fun destroyForcibly(): Process { forciblyCalled.set(true); if (forciblyWorks) kill(); return this }
        private fun kill() { if (exited.count > 0) { killed = true; exited.countDown() } }
    }

    private fun run(process: Process, timeoutMillis: Long = 5_000): Int =
        TaskResizeProcessRunner.run({ process }, timeoutMillis, cleanupWaitMillis = 250)

    @Test
    fun `exit zero maps to success with no live process`() {
        val process = AdversarialProcess(output = "ok", exitCode = 0)
        assertEquals(TaskResizeCommand.STATUS_SUCCESS, run(process))
        assertFalse(process.isAlive)
    }

    @Test
    fun `nonzero exit maps to command failed`() {
        assertEquals(TaskResizeCommand.STATUS_COMMAND_FAILED, run(AdversarialProcess(exitCode = 1)))
    }

    @Test
    fun `timeout with a no-op destroy still reaps via destroyForcibly`() {
        val process = AdversarialProcess(exitsOnItsOwn = false, destroyWorks = false)
        val started = System.nanoTime()
        val status = run(process, timeoutMillis = 150)
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertEquals(TaskResizeCommand.STATUS_TIMED_OUT, status)
        assertTrue(process.destroyCalled.get())
        assertTrue(process.forciblyCalled.get())
        assertFalse(process.isAlive)
        assertTrue("took ${elapsed}ms", elapsed < 5_000)
    }

    @Test
    fun `blocked stream after exit is bounded and reaped not hung`() {
        // Process exits, stdout blocks until close; the runner must not hang
        // and must reap cleanly. Whether the reader finishes cleanly (SUCCESS)
        // or is torn down (PROCESS_ERROR) is a benign race — both are safe for
        // a resize whose output is discarded; the guarantee under test is no
        // hang and no leak.
        val process = AdversarialProcess(output = "ok", streamIgnoresExit = true)
        val started = System.nanoTime()
        val status = run(process)
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertTrue(
            status == TaskResizeCommand.STATUS_SUCCESS ||
                status == TaskResizeCommand.STATUS_PROCESS_ERROR,
        )
        assertFalse(status == TaskResizeCommand.STATUS_TIMED_OUT)
        assertFalse(process.isAlive)
        assertTrue("took ${elapsed}ms", elapsed < 5_000)
    }

    @Test
    fun `unkillable process is an explicit process error`() {
        val process = AdversarialProcess(exitsOnItsOwn = false, destroyWorks = false, forciblyWorks = false)
        assertEquals(TaskResizeCommand.STATUS_PROCESS_ERROR, run(process, timeoutMillis = 150))
        assertTrue(process.destroyCalled.get())
        assertTrue(process.forciblyCalled.get())
    }

    @Test
    fun `stdout close failure is a process error`() {
        val process = AdversarialProcess(output = "ok", stdoutCloseFails = true)
        assertEquals(TaskResizeCommand.STATUS_PROCESS_ERROR, run(process))
    }

    @Test
    fun `stdout close failure that leaves the reader blocked is a bounded process error`() {
        val process = AdversarialProcess(
            output = "ok",
            streamIgnoresExit = true,
            readIgnoresInterrupt = true,
            stdoutCloseFails = true,
            stdoutCloseUnblocks = false,
        )
        val started = System.nanoTime()
        val status = run(process)
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertEquals(TaskResizeCommand.STATUS_PROCESS_ERROR, status)
        assertTrue("took ${elapsed}ms", elapsed < 5_000)

        process.unwedge() // release the wedged daemon reader for hygiene
    }

    @Test
    fun `caller interruption yields process error and restores the flag`() {
        val process = AdversarialProcess(output = "ok", exitsOnItsOwn = false, streamIgnoresExit = true)
        Thread.currentThread().interrupt()
        val status = run(process)
        val flag = Thread.interrupted()

        assertEquals(TaskResizeCommand.STATUS_PROCESS_ERROR, status)
        assertTrue(flag)
        assertFalse(process.isAlive)
    }

    @Test
    fun `launch failure maps to process error`() {
        assertEquals(TaskResizeCommand.STATUS_PROCESS_ERROR, TaskResizeProcessRunner.run({ error("no start") }, 1_000))
    }
}
