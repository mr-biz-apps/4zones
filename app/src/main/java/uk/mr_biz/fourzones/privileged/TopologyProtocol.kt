package uk.mr_biz.fourzones.privileged

/**
 * Compiled protocol version of the privileged topology boundary.
 *
 * The SAME source constant is compiled into BOTH the main application and the
 * Shizuku [DesktopTopologyShellService]. When the UserService process is
 * running the CURRENTLY installed APK's code it reports this value over the
 * binder; a STALE UserService process that survived an `install -r` reports
 * its OLD compiled value (or cannot answer the call at all). That divergence —
 * detected by [UserServiceVersionGuard] — is the whole point: the check is
 * based on the code actually loaded in the remote process, never on the
 * PackageManager version, APK timestamp, versionName, main-process BuildConfig,
 * or PID.
 *
 * Bump this whenever the privileged boundary's observable behaviour changes in
 * a way the main process must not consume from an older UserService — most
 * importantly the [TopologyDumpFilter] grammar. Version 2 is the first version
 * whose filter retains the `Display: mDisplayId=N` WindowManager scoped-focus
 * blocks; consuming a version-1 filter's output silently drops display-scoped
 * focus (the S25 external-DeX stale-service defect: scopedGrammar=false despite
 * a valid raw dump).
 */
object TopologyProtocol {
    /** Current compiled privileged-boundary protocol version. */
    const val VERSION = 2
}
