package uk.mr_biz.fourzones.privileged

import androidx.annotation.Keep
import kotlin.system.exitProcess

/**
 * Shizuku user service. This class is instantiated by the Shizuku server in a
 * separate process running with SHELL identity (uid 2000) — sufficient for both
 * the activity dumps this service reads and the one fixed-token task-resize
 * command it runs, with no root involved.
 *
 * Least privilege — which is NOT the same as read-only: it implements exactly
 * the named operations of [IDesktopTopologyShellService] and nothing else. Two
 * of them are read-only ([protocolVersion], [readActivityTopology]); ONE MUTATES
 * ([resizeTask], below). There is no generic command interface, and output is
 * structurally filtered before it leaves this process. Raw dumps are never
 * logged or persisted.
 */
@Keep
class DesktopTopologyShellService : IDesktopTopologyShellService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    /**
     * Reports the protocol version compiled into THIS process's code. A stale
     * process that survived an `install -r` returns its own (older) compiled
     * constant, letting the main process fail closed rather than consume stale
     * filtered topology. See [TopologyProtocol] and [UserServiceVersionGuard].
     */
    override fun protocolVersion(): Int = TopologyProtocol.VERSION

    override fun readActivityTopology(): String = try {
        TopologyProcessRunner.run(
            launch = {
                // FIXED, non-parameterized command. The launcher lambda is a
                // test seam for TopologyProcessRunner only — never a way to
                // run arbitrary commands. stderr is merged into stdout so a
                // full stderr pipe can never deadlock the child.
                ProcessBuilder("dumpsys", "activity", "activities")
                    .redirectErrorStream(true)
                    .start()
            },
        )
    } catch (e: IllegalStateException) {
        // IllegalStateException (incl. TopologyOutputTooLargeException) is a
        // type Parcel can marshal back to the caller instead of killing this
        // process; the app side surfaces it as a command failure.
        throw e
    } catch (e: Exception) {
        throw IllegalStateException("readActivityTopology failed: ${e.message}")
    }

    /**
     * The ONE mutating operation. Builds a FIXED-token argv from decimal
     * integers via [TaskResizeCommand] (no shell string, no interpolation),
     * rejects inverted/empty bounds before any launch, runs it with bounded
     * timeout/cleanup, and returns a small integer status code. Never returns
     * command output; never throws command text across the binder.
     */
    override fun resizeTask(taskId: Int, left: Int, top: Int, right: Int, bottom: Int): Int {
        val argv = TaskResizeCommand.argvOrNull(taskId, left, top, right, bottom)
            ?: return TaskResizeCommand.STATUS_REJECTED
        return try {
            TaskResizeProcessRunner.run(
                launch = {
                    // Fixed argv only. stderr merged so a full stderr pipe
                    // cannot deadlock the child; output is drained and discarded.
                    ProcessBuilder(argv).redirectErrorStream(true).start()
                },
            )
        } catch (e: Exception) {
            TaskResizeCommand.STATUS_PROCESS_ERROR
        }
    }
}
