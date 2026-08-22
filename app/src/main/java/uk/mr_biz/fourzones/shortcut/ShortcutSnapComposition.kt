package uk.mr_biz.fourzones.shortcut

import android.content.Context
import android.os.Handler
import android.os.Looper
import uk.mr_biz.fourzones.geometry.DisplayGeometryReader
import uk.mr_biz.fourzones.geometry.Quadrant
import uk.mr_biz.fourzones.privileged.ShizukuPrivilegedBackend
import uk.mr_biz.fourzones.snap.DisplayGeometrySource
import uk.mr_biz.fourzones.snap.SnapExecutionOrchestrator

/**
 * Application-scoped composition that lets the AccessibilityService run the
 * EXISTING, validated [SnapExecutionOrchestrator] while DexZones' UI (and its
 * Activity-owned backend) is not foregrounded.
 *
 * Lifecycle rationale: `SnapExecutionOrchestrator` and `ShizukuPrivilegedBackend`
 * are otherwise MainActivity-owned and stopped in `onStop`, so they cannot serve
 * a global shortcut fired while another app is focused. Rather than share one
 * backend instance across the Activity and the service (which would give the
 * two components conflicting start/stop ownership of a single privileged
 * session), this composition owns its OWN backend session for the service's
 * lifetime — exactly the pattern the one-shot capture service already uses, and
 * which Shizuku's multi-bind supports. It reuses the SAME orchestrator, parser,
 * resolver, geometry, gateway, and version-safe backend lifecycle; it duplicates
 * none of that logic. Every backend session runs the reviewed
 * handshake/replacement/generation guarantees unchanged.
 *
 * All lifecycle and request calls run on the main thread; the constructor's
 * [onEvent] sink is invoked on the main thread.
 */
class ShortcutSnapComposition(
    context: Context,
    selfPackageName: String,
    onEvent: (ShortcutSnapEvent) -> Unit,
) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    // The service's own privileged session (version-safe, restartable),
    // independent of MainActivity's. Also the single mutating authority.
    private val backend = ShizukuPrivilegedBackend(appContext)
    private val geometryReader = DisplayGeometryReader(appContext)

    // Fresh geometry at fire time (per-display, from each display's own maximum
    // window metrics). Topology is read through the NON-QUEUING backend read for
    // EVERY orchestrator fetch (T1/T2/T3): a shortcut is instantaneous, so if the
    // verified service is unavailable at any read admission the transaction fails
    // closed immediately and is never resumed after reconnect (see
    // ShortcutTopologySource / backend.readActivityTopologyNow). The parser/
    // resolver/geometry/orchestrator are reused as-is.
    private val topologySource = shortcutTopologySource(backend::readActivityTopologyNow)
    private val geometrySource = DisplayGeometrySource { displayId -> geometryReader.read(displayId) }

    private val orchestrator = SnapExecutionOrchestrator(
        topologySource = topologySource,
        geometrySource = geometrySource,
        gateway = backend,
        selfPackageName = selfPackageName,
    )

    private val controller = ShortcutSnapController(
        // The SAME verified-service truth the backend uses to permit mutation: a
        // non-ready shortcut is rejected here, before the orchestrator, so its
        // read can never be queued by the coordinator and replayed after READY.
        readiness = backend::currentStatus,
        executor = { quadrant, isCancelled, onResult ->
            orchestrator.execute(quadrant, isCancelled, onResult)
        },
        onEvent = onEvent,
    )

    /** Main-thread: activate the controller and start the privileged session. */
    fun start() {
        controller.start()
        // The backend eager-connects and version-verifies; a shortcut fired
        // before READY fails closed through the orchestrator (no queue).
        // The status callback is observation only: it publishes THIS backend's
        // live status to the process-local store and triggers nothing.
        backend.start { status -> ShortcutBackendStatusStore.publish(status) }
    }

    /** Main-thread: invalidate in-flight work and release the session. */
    fun stop() {
        controller.stop()
        // Order constraint: backend.stop() first makes this session's status
        // callback inert (started=false, callback nulled, session gate closed),
        // THEN clear() publishes null as the FINAL shared observation — so a
        // retired session can never republish after the clear.
        backend.stop()
        ShortcutBackendStatusStore.clear()
    }

    /** Submits one snap for [quadrant]; hops to the main thread defensively. */
    fun requestSnap(quadrant: Quadrant) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            controller.requestSnap(quadrant)
        } else {
            mainHandler.post { controller.requestSnap(quadrant) }
        }
    }
}
