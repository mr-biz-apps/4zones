package uk.mr_biz.fourzones

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import uk.mr_biz.fourzones.capture.CaptureStartBoundary
import uk.mr_biz.fourzones.capture.TargetCaptureResult
import uk.mr_biz.fourzones.capture.TargetCaptureResultStore
import uk.mr_biz.fourzones.capture.TargetCaptureService
import uk.mr_biz.fourzones.desktop.DesktopTopologyParser
import uk.mr_biz.fourzones.desktop.DesktopTopologyReader
import uk.mr_biz.fourzones.desktop.SnapTargetAssessment
import uk.mr_biz.fourzones.desktop.SnapTargetResolver
import uk.mr_biz.fourzones.display.DisplayDiscovery
import uk.mr_biz.fourzones.display.DisplayInfo
import uk.mr_biz.fourzones.geometry.DesktopWorkAreaAssessment
import uk.mr_biz.fourzones.geometry.DisplayGeometryReader
import uk.mr_biz.fourzones.privileged.PrivilegedBackendStatus
import uk.mr_biz.fourzones.privileged.ShizukuPrivilegedBackend
import uk.mr_biz.fourzones.privileged.TopologyReadResult
import uk.mr_biz.fourzones.product.productReadiness
import uk.mr_biz.fourzones.shortcut.AccessibilityDisclosureConsentStore
import uk.mr_biz.fourzones.shortcut.AccessibilityEnableGate
import uk.mr_biz.fourzones.shortcut.AccessibilitySettingsLaunchResult
import uk.mr_biz.fourzones.shortcut.AccessibilitySettingsLauncher
import uk.mr_biz.fourzones.shortcut.ShortcutBackendStatusStore
import uk.mr_biz.fourzones.shortcut.ShortcutDiagnosticsStore
import uk.mr_biz.fourzones.shortcut.ShortcutObservation
import uk.mr_biz.fourzones.shortcut.ShortcutServiceStatus
import uk.mr_biz.fourzones.shortcut.ShortcutSnapResultDiagnostic
import uk.mr_biz.fourzones.shortcut.ShortcutSnapResultStore
import uk.mr_biz.fourzones.snap.DelayedCancellable
import uk.mr_biz.fourzones.snap.DelayedScheduler
import uk.mr_biz.fourzones.snap.DisplayGeometrySource
import uk.mr_biz.fourzones.snap.SnapExecutionController
import uk.mr_biz.fourzones.snap.SnapExecutionOrchestrator
import uk.mr_biz.fourzones.snap.SnapExecutionState
import uk.mr_biz.fourzones.snap.TopologyFetch
import uk.mr_biz.fourzones.snap.TopologySource
import uk.mr_biz.fourzones.ui.AccessibilityDisclosureDialog
import uk.mr_biz.fourzones.ui.DisplayDiagnosticsScreen
import uk.mr_biz.fourzones.ui.ProductScreen
import uk.mr_biz.fourzones.ui.theme.DexZonesTheme
import uk.mr_biz.fourzones.workspace.WorkspaceInterpreter
import uk.mr_biz.fourzones.workspace.WorkspaceSnapshot
import uk.mr_biz.fourzones.workspace.WorkspaceStateReader

/** Compose-local routing between the product home and the diagnostics screen. */
private enum class AppScreen {
    PRODUCT,
    DIAGNOSTICS,
}

class MainActivity : ComponentActivity() {

    private var screen by mutableStateOf(AppScreen.PRODUCT)

    // Phase 4D: the accessibility prominent-disclosure gate. The gate ensures NO enable path reaches
    // the system-settings deep-link without first showing the disclosure + affirmative consent.
    private val consentStore by lazy { AccessibilityDisclosureConsentStore.forContext(this) }
    private val accessibilityEnableGate by lazy { AccessibilityEnableGate(consentStore) }
    private var showAccessibilityDisclosure by mutableStateOf(false)

    // D-1-CORR (H7d): the recovery state for a FAILED settings deep-link. Set ONLY from the outcome of
    // AccessibilitySettingsLauncher.open(...) in onAccessibilityDisclosureAccepted(); it is assigned the
    // full outcome every time (success CLEARS a stale message, failure raises it), so there is exactly
    // one message and no replacement/stacking policy is needed. Activity-local like
    // showAccessibilityDisclosure, so recreation clears it — an explicitly recorded residual: it loses a
    // transient message but cannot latch, cannot revoke consent, and cannot bypass the disclosure.
    private var showAccessibilitySettingsUnavailable by mutableStateOf(false)

    // Product readiness input: the shortcut-owned backend's own published
    // status (ShortcutBackendStatusStore) — NEVER this Activity's separate
    // backend, whose status (topologyBackendStatus below) stays diagnostic.
    private var shortcutBackendStatus by mutableStateOf<PrivilegedBackendStatus?>(null)

    private var displays by mutableStateOf<List<DisplayInfo>>(emptyList())
    private var workspace by mutableStateOf<WorkspaceSnapshot?>(null)
    private var topologyBackendStatus by mutableStateOf<PrivilegedBackendStatus?>(null)
    private var topologyOutcome by mutableStateOf<DesktopTopologyReader.Outcome?>(null)
    private var snapTargets by mutableStateOf<Map<Int, SnapTargetAssessment>?>(null)
    private var captureResult by mutableStateOf<TargetCaptureResult?>(null)
    private var workspaceGeometry by mutableStateOf<List<DesktopWorkAreaAssessment>>(emptyList())
    private var snapExecutionState by mutableStateOf<SnapExecutionState>(SnapExecutionState.Idle)
    private var shortcutServiceEnabled by mutableStateOf(false)
    private var lastShortcut by mutableStateOf<ShortcutObservation?>(null)
    private var lastSnapResult by mutableStateOf<ShortcutSnapResultDiagnostic?>(null)

    // Holds only the application context; started/stopped with this activity.
    private lateinit var displayDiscovery: DisplayDiscovery

    private val workspaceStateReader = WorkspaceStateReader()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Holds only the application context. Started/stopped with the activity;
    // the privileged path stays behind the PrivilegedBackend abstraction.
    private lateinit var privilegedBackend: ShizukuPrivilegedBackend
    private lateinit var topologyReader: DesktopTopologyReader

    // Phase 2C3A: the only mutating path. The controller schedules a delayed
    // one-shot; the orchestrator does every read/resolve/geometry/revalidate
    // fresh at fire time and performs the single privileged resize.
    private lateinit var snapExecutionController: SnapExecutionController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val geometryReader = DisplayGeometryReader(applicationContext)
        displayDiscovery = DisplayDiscovery(applicationContext) { infos ->
            displays = infos
            // Geometry is derived independently per discovered display,
            // from each display's own maximum window metrics — never from
            // this Activity's current bounds.
            workspaceGeometry = infos.map { geometryReader.read(it.properties.displayId) }
        }
        privilegedBackend = ShizukuPrivilegedBackend(applicationContext)
        topologyReader = DesktopTopologyReader(privilegedBackend)

        // Snap-execution seams. Topology and geometry are read FRESH at fire
        // time through these sources; the gateway (the same backend) is the
        // single mutating authority.
        val topologySource = TopologySource { onResult ->
            privilegedBackend.readActivityTopology { r ->
                onResult(
                    when (r) {
                        is TopologyReadResult.Success ->
                            TopologyFetch.Fetched(DesktopTopologyParser.parse(r.filteredDump))
                        is TopologyReadResult.BackendUnavailable ->
                            TopologyFetch.Unavailable(r.status)
                        is TopologyReadResult.CommandFailed ->
                            TopologyFetch.Failed(r.message)
                    },
                )
            }
        }
        val geometrySource = DisplayGeometrySource { displayId -> geometryReader.read(displayId) }
        val orchestrator = SnapExecutionOrchestrator(
            topologySource = topologySource,
            geometrySource = geometrySource,
            gateway = privilegedBackend,
            selfPackageName = packageName,
        )
        val scheduler = DelayedScheduler { delayMillis, action ->
            val runnable = Runnable { action() }
            mainHandler.postDelayed(runnable, delayMillis)
            DelayedCancellable { mainHandler.removeCallbacks(runnable) }
        }
        snapExecutionController = SnapExecutionController(
            orchestrator = orchestrator,
            delayMillis = SNAP_DELAY_MILLIS,
            scheduler = scheduler,
            onStateChanged = { snapExecutionState = it },
        )

        setContent {
            DexZonesTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    when (screen) {
                        AppScreen.PRODUCT -> ProductScreen(
                            // The ONLY readiness derivation: refreshed
                            // Accessibility enablement + the shortcut-owned
                            // backend's published status.
                            readiness = productReadiness(
                                serviceEnabled = shortcutServiceEnabled,
                                backendStatus = shortcutBackendStatus,
                            ),
                            onEnableShortcuts = {
                                // Phase 4D: gated — shows the prominent disclosure first; the
                                // settings deep-link happens ONLY after affirmative consent.
                                requestEnableShortcuts()
                            },
                            // Permission requests go through this Activity's
                            // EXISTING foreground backend; the shortcut backend
                            // observes the app-wide grant independently.
                            onRequestPermission = { privilegedBackend.requestPermission() },
                            onOpenDiagnostics = { screen = AppScreen.DIAGNOSTICS },
                            modifier = Modifier.padding(innerPadding),
                        )
                        AppScreen.DIAGNOSTICS -> {
                            BackHandler { screen = AppScreen.PRODUCT }
                            DiagnosticsRoute(innerPadding = innerPadding)
                        }
                    }
                }
                // Phase 4D: the prominent-disclosure dialog gates BOTH enable deep-links. It is the
                // ONLY route to the settings intent; "Turn on shortcuts" records consent + deep-links,
                // "Not now" dismisses (service stays off; the rest of the app keeps working).
                if (showAccessibilityDisclosure) {
                    AccessibilityDisclosureDialog(
                        onAccept = { onAccessibilityDisclosureAccepted() },
                        onDecline = { onAccessibilityDisclosureDeclined() },
                    )
                }
                // D-1-CORR (H7d): the visible, recoverable feedback for a FAILED deep-link. It is a
                // DIALOG, not a transient snackbar, deliberately: it is announced by TalkBack as a
                // dialog, it stays until the user acknowledges it (a snackbar would time out before a
                // screen-reader user reached it), and it renders in the SAME composition slot as the
                // disclosure dialog, so it is visible over the product screen, the diagnostics screen
                // and the pairing screen alike. Dismissing it is the ONLY way it clears, and clearing
                // it leaves every enable entry point exactly as usable as before.
                if (showAccessibilitySettingsUnavailable) {
                    AccessibilitySettingsUnavailableDialog(
                        onDismiss = { showAccessibilitySettingsUnavailable = false },
                    )
                }
            }
        }
    }

    /**
     * D-1-CORR (H7d) — the manual-recovery message shown when the settings deep-link failed. The copy
     * lives in string resources and deliberately does NOT repeat the disclosure's "on the next screen"
     * promise, which is exactly the promise the failure falsified.
     */
    @Composable
    private fun AccessibilitySettingsUnavailableDialog(onDismiss: () -> Unit) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(text = stringResource(R.string.accessibility_settings_unavailable_title)) },
            text = { Text(text = stringResource(R.string.accessibility_settings_unavailable_body)) },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(R.string.accessibility_settings_unavailable_dismiss))
                }
            },
        )
    }

    /** Phase 4D: an enable request always shows the disclosure first — never a direct deep-link. */
    private fun requestEnableShortcuts() {
        when (accessibilityEnableGate.onEnableRequested()) {
            AccessibilityEnableGate.Action.SHOW_DISCLOSURE -> showAccessibilityDisclosure = true
            AccessibilityEnableGate.Action.DEEP_LINK,
            AccessibilityEnableGate.Action.DISMISS,
            -> Unit
        }
    }

    private fun onAccessibilityDisclosureAccepted() {
        // D-1-CORR (H13): ONE acceptance = AT MOST ONE launch attempt. The disclosure dialog is the only
        // source of this callback and is composed only while the flag is true, so a second tap dispatched
        // in the same frame (double-tap / re-entrant accept) finds the flag already false and is a no-op:
        // no second consent write, no second startActivity. This can NEVER latch — requestEnableShortcuts()
        // sets the flag back to true on every later enable request.
        if (!showAccessibilityDisclosure) return
        showAccessibilityDisclosure = false
        if (accessibilityEnableGate.onConsentAccepted(System.currentTimeMillis()) ==
            AccessibilityEnableGate.Action.DEEP_LINK
        ) {
            // Still never enables the service programmatically — only opens system settings.
            // D-1-CORR (H7d): contained. A recoverable launch failure must not crash the app AFTER
            // consent was persisted and the dialog dismissed. Consent REMAINS granted (the user did
            // consent; the launch outcome does not rewrite that decision) and the user gets the manual
            // route instead. Assigning the full outcome also CLEARS a stale failure message on a later
            // successful attempt.
            val outcome = AccessibilitySettingsLauncher.open {
                startActivity(ShortcutServiceStatus.accessibilitySettingsIntent())
            }
            showAccessibilitySettingsUnavailable =
                outcome == AccessibilitySettingsLaunchResult.UNAVAILABLE
        }
    }

    private fun onAccessibilityDisclosureDeclined() {
        // D-1-CORR (H13): symmetric re-entrancy guard — a repeated decline (double tap, or a dismiss
        // request racing a button tap) must not record a second gate decision. Also cannot latch.
        if (!showAccessibilityDisclosure) return
        accessibilityEnableGate.onConsentDeclined()
        showAccessibilityDisclosure = false
    }

    /**
     * The pre-existing diagnostics screen, unchanged in content and wiring —
     * only routed behind the product screen's "Developer diagnostics" entry.
     * Navigation never starts/stops any backend or reader.
     */
    @Composable
    private fun DiagnosticsRoute(innerPadding: PaddingValues) {
        DisplayDiagnosticsScreen(
            displays = displays,
            workspace = workspace,
            topologyBackendStatus = topologyBackendStatus,
            topologyOutcome = topologyOutcome,
            snapTargets = snapTargets,
            captureResult = captureResult,
            workspaceGeometry = workspaceGeometry,
            snapExecutionState = snapExecutionState,
            onRequestTopologyPermission = { privilegedBackend.requestPermission() },
            onRefreshTopology = { topologyReader.refresh() },
            onStartTargetCapture = {
                // The one-shot capture service owns its own
                // backend/reader lifecycle; started while this
                // Activity is visible, it survives our onStop.
                // The boundary converts framework start failures
                // into a sanitized Failed result instead of a
                // crash.
                CaptureStartBoundary.start {
                    startForegroundService(
                        Intent(this, TargetCaptureService::class.java),
                    )
                }
            },
            onRequestSnap = { quadrant -> snapExecutionController.requestSnap(quadrant) },
            shortcutServiceEnabled = shortcutServiceEnabled,
            // Diagnostic-only mirror of the same shortcut-owned backend status
            // that already drives product readiness — no second store listener.
            shortcutBackendStatus = shortcutBackendStatus,
            lastShortcut = lastShortcut,
            lastSnapResult = lastSnapResult,
            onOpenAccessibilitySettings = {
                // Phase 4D: gated identically to the product-screen path — the
                // disclosure precedes the settings deep-link on EVERY enable route.
                requestEnableShortcuts()
            },
            modifier = Modifier.padding(innerPadding),
        )
    }

    override fun onStart() {
        super.onStart()
        displayDiscovery.start()
        // Pick up any capture result that arrived while we were stopped,
        // then observe live updates (the capture-in-foreground case).
        captureResult = TargetCaptureResultStore.lastResult
        TargetCaptureResultStore.setListener { captureResult = it }
        // Read-only shortcut diagnostics: reflect current enablement and observe
        // live matches. No connection to any mutation path.
        shortcutServiceEnabled = ShortcutServiceStatus.isEnabled(this)
        lastShortcut = ShortcutDiagnosticsStore.lastObservation
        ShortcutDiagnosticsStore.setListener { lastShortcut = it }
        lastSnapResult = ShortcutSnapResultStore.lastResult
        ShortcutSnapResultStore.setListener { lastSnapResult = it }
        // Product readiness input: observe the shortcut-owned backend's status
        // (4B1 store contract) — listener first, then read latest(), so the
        // current value cannot be missed (both are main-thread serialized).
        // A published null immediately leaves READY; this Activity's own
        // backend below never feeds this state.
        ShortcutBackendStatusStore.setListener { shortcutBackendStatus = it }
        shortcutBackendStatus = ShortcutBackendStatusStore.latest()
        // Reader first: its generation guard is what discards stale or
        // post-stop results, including any triggered by backend callbacks.
        topologyReader.start { outcome ->
            topologyOutcome = outcome
            // Read-only target resolution over the fresh snapshot. The own
            // package identity is passed in; the pure resolver hard-codes
            // no application constant.
            snapTargets = (outcome as? DesktopTopologyReader.Outcome.Success)
                ?.snapshot
                ?.let { SnapTargetResolver.resolve(it, packageName) }
        }
        privilegedBackend.start { status ->
            val becameReady =
                status == PrivilegedBackendStatus.READY && topologyBackendStatus != status
            topologyBackendStatus = status
            // Auto-read and the manual Refresh button share the reader's
            // generation ordering: the newest request always wins.
            if (becameReady) topologyReader.refresh()
        }
        snapExecutionController.start()
    }

    override fun onResume() {
        super.onResume()
        refreshWorkspaceState()
        // The user may have toggled the shortcut service in system settings and
        // returned; re-read its enablement.
        shortcutServiceEnabled = ShortcutServiceStatus.isEnabled(this)
    }

    // Undeclared configuration changes recreate the Activity and reach
    // refreshWorkspaceState() through onResume(); these callbacks cover the
    // changes the system delivers to a live Activity without recreation.
    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        refreshWorkspaceState()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        refreshWorkspaceState()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshWorkspaceState()
    }

    override fun onStop() {
        TargetCaptureResultStore.setListener(null)
        ShortcutDiagnosticsStore.setListener(null)
        ShortcutSnapResultStore.setListener(null)
        // Removed with the other store listeners so the process-local singleton
        // never retains or calls a stopped Activity.
        ShortcutBackendStatusStore.setListener(null)
        // Snap controller stops first so a pending/in-flight mutation is
        // invalidated before the backend it depends on is torn down.
        snapExecutionController.stop()
        // Reader stops first so results delivered during backend teardown
        // are already invalidated and this Activity is no longer retained.
        topologyReader.stop()
        privilegedBackend.stop()
        displayDiscovery.stop()
        super.onStop()
    }

    private fun refreshWorkspaceState() {
        val state = workspaceStateReader.read(this)
        workspace = WorkspaceSnapshot(state, WorkspaceInterpreter.interpret(state))
    }

    private companion object {
        const val SNAP_DELAY_MILLIS = 5_000L
    }
}
