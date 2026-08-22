// Binder interface for the Shizuku user service running with shell identity.
//
// Deliberately narrow — but NOT read-only. It exposes exactly three named
// operations, plus the destroy() the Shizuku server requires: two are read-only
// (protocolVersion, readActivityTopology) and ONE MUTATES (resizeTask, below).
// It must never grow an arbitrary exec(command) method — every
// privileged operation gets its own explicitly implemented, whitelisted
// method so the normal app process cannot run arbitrary shell commands.
package uk.mr_biz.fourzones.privileged;

interface IDesktopTopologyShellService {
    // Destroy method required by the Shizuku server (fixed transaction code).
    void destroy() = 16777114;

    // Reports the protocol version COMPILED INTO THIS SERVICE PROCESS
    // (TopologyProtocol.VERSION). The main process compares it against its own
    // compiled expectation to detect a stale UserService that survived an
    // `install -r` and would otherwise feed newer code from an older filter.
    // Read-only, returns a single integer, never command output.
    int protocolVersion() = 3;

    // Returns a filtered, structural subset of the read-only
    // "dumpsys activity activities" output: only the task-hierarchy and
    // focus lines needed by the desktop topology parser. Raw dumps are
    // never returned, logged, or persisted.
    String readActivityTopology() = 1;

    // The ONE mutating operation. Runs a FIXED-token command
    //   cmd activity task resize <taskId> <left> <top> <right> <bottom>
    // built from decimal integers only — no shell string, no interpolation,
    // no arbitrary command text ever crosses this boundary. Returns a small
    // integer status code (see TaskResizeGateway.STATUS_*); it never returns
    // command output and never throws command text back to the caller.
    int resizeTask(int taskId, int left, int top, int right, int bottom) = 2;
}
