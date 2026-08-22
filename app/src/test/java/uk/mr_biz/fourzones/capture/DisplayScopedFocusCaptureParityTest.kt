package uk.mr_biz.fourzones.capture

import uk.mr_biz.fourzones.desktop.DesktopTopologyParser
import uk.mr_biz.fourzones.desktop.DisplayFocus
import uk.mr_biz.fourzones.desktop.SnapTargetAssessment
import uk.mr_biz.fourzones.desktop.SnapTargetResolver
import uk.mr_biz.fourzones.geometry.DesktopWorkAreaAssessment
import uk.mr_biz.fourzones.geometry.DisplayGeometryReading
import uk.mr_biz.fourzones.geometry.GeometryInsets
import uk.mr_biz.fourzones.geometry.GeometryRect
import uk.mr_biz.fourzones.geometry.Quadrant
import uk.mr_biz.fourzones.geometry.WorkspaceGeometryCalculator
import uk.mr_biz.fourzones.privileged.PrivilegedBackend
import uk.mr_biz.fourzones.privileged.PrivilegedBackendStatus
import uk.mr_biz.fourzones.privileged.TaskResizeGateway
import uk.mr_biz.fourzones.privileged.TaskResizeOutcome
import uk.mr_biz.fourzones.privileged.TopologyReadResult
import uk.mr_biz.fourzones.snap.DisplayGeometrySource
import uk.mr_biz.fourzones.snap.SnapExecutionOrchestrator
import uk.mr_biz.fourzones.snap.TopologyFetch
import uk.mr_biz.fourzones.snap.TopologySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end proof that the ONE shared read-only target-acquisition layer
 * (parser -> [SnapTargetResolver]) honors display-scoped focus identically for
 * BOTH the one-shot capture ([TargetCaptureController]) and the Phase 2C3A
 * pre-mutation path ([SnapExecutionOrchestrator]) — the root-cause audit for
 * the S25 external-DeX "focused task 19398 is outside the desk-root hierarchy"
 * result.
 *
 * The fixtures reproduce the S25 shape: a phone display (0) whose GLOBAL
 * focus is a phone task A, and an external display (6) hosting a `name=Desk`
 * activatable root whose display-scoped focus is the external app B. Neither
 * path may ever apply the phone's global focus A to the external desktop.
 */
class DisplayScopedFocusCaptureParityTest {

    private val self = "uk.mr_biz.fourzones"
    private val phoneTaskA = 19398
    private val externalTaskB = 600
    private val chrome = "com.android.chrome"

    // ------------------------------------------------------------- fixtures

    /**
     * External S25 DeX WITH the WindowManager display-scoped focus grammar:
     * display 6's mFocusedApp is the external chrome task B, display 0's is
     * the phone task A. Display 0 is listed last so the legacy GLOBAL focus
     * (last mFocusedApp seen) is the phone task A — the exact condition that
     * must NOT override display 6's scoped focus.
     */
    private fun scopedDump(taskBBounds: GeometryRect? = null): String = buildString {
        appendLine("Display #0 (activities from top to bottom):")
        appendLine(
            "  * Task{p #100 type=standard A=10001:com.phone.launcher U=0 " +
                "visible=true visibleRequested=true mode=fullscreen}",
        )
        appendLine(
            "    * Task{pa #$phoneTaskA type=standard A=10002:com.phone.app U=0 " +
                "rootTaskId=100 visible=true visibleRequested=true mode=fullscreen}",
        )
        appendLine("Display #6 (activities from top to bottom):")
        appendLine(
            "  * Task{d #500 type=undefined name=Desk U=0 visible=true " +
                "visibleRequested=true mode=freeform}",
        )
        appendLine("    mCreatedByOrganizer=true")
        appendLine(
            "    * Task{db #$externalTaskB type=standard A=10003:$chrome U=0 " +
                "rootTaskId=500 visible=true visibleRequested=true mode=freeform}",
        )
        // Task-outer bounds at the child's direct-property indent (4 + 2 = 6).
        if (taskBBounds != null) {
            appendLine(
                "      mBounds=Rect(${taskBBounds.left}, ${taskBBounds.top} - " +
                    "${taskBBounds.right}, ${taskBBounds.bottom})",
            )
        }
        appendLine("ActivityTaskSupervisor state:")
        appendLine("  Display: mDisplayId=6")
        appendLine("    mFocusedApp=ActivityRecord{f2 u0 $chrome/.Main t$externalTaskB}")
        appendLine("  Display: mDisplayId=0")
        appendLine("    mFocusedApp=ActivityRecord{f1 u0 com.phone.app/.Main t$phoneTaskA}")
    }

    /**
     * The S25 FAILURE reproduction: the SAME two-display hierarchy, but the
     * ActivityTaskSupervisor section carries NO `Display: mDisplayId=N` blocks
     * — only a single global mFocusedApp pointing at the phone task A. Before
     * the fix this made the resolver treat the sole desk-bearing display as a
     * single-display system and apply the phone focus; it must now fail closed.
     */
    private fun noScopedGrammarDump(): String = buildString {
        appendLine("Display #0 (activities from top to bottom):")
        appendLine(
            "  * Task{p #100 type=standard A=10001:com.phone.launcher U=0 " +
                "visible=true visibleRequested=true mode=fullscreen}",
        )
        appendLine(
            "    * Task{pa #$phoneTaskA type=standard A=10002:com.phone.app U=0 " +
                "rootTaskId=100 visible=true visibleRequested=true mode=fullscreen}",
        )
        appendLine("Display #6 (activities from top to bottom):")
        appendLine(
            "  * Task{d #500 type=undefined name=Desk U=0 visible=true " +
                "visibleRequested=true mode=freeform}",
        )
        appendLine("    mCreatedByOrganizer=true")
        appendLine(
            "    * Task{db #$externalTaskB type=standard A=10003:$chrome U=0 " +
                "rootTaskId=500 visible=true visibleRequested=true mode=freeform}",
        )
        appendLine("ActivityTaskSupervisor state:")
        appendLine("  mFocusedApp=ActivityRecord{f1 u0 com.phone.app/.Main t$phoneTaskA}")
    }

    /** Standalone single-display A9 DeX: the legacy global fallback still applies. */
    private fun a9Dump(): String = buildString {
        appendLine("Display #0 (activities from top to bottom):")
        appendLine(
            "  * Task{a #310 type=undefined dw=activatable U=0 visible=true " +
                "visibleRequested=true mode=freeform}",
        )
        appendLine("    mDeskRootTaskType=activatable")
        appendLine(
            "    * Task{b #700 type=standard A=10123:$chrome U=0 rootTaskId=310 " +
                "visible=true visibleRequested=true mode=freeform}",
        )
        appendLine("ActivityTaskSupervisor state:")
        appendLine("  mFocusedApp=ActivityRecord{f u0 $chrome/.Main t700}")
    }

    // --------------------------------------------------------------- fakes

    private class DumpBackend(private val dump: String) : PrivilegedBackend {
        private var statusCallback: ((PrivilegedBackendStatus) -> Unit)? = null
        var stopped = false
        override fun start(onStatusChanged: (PrivilegedBackendStatus) -> Unit) {
            statusCallback = onStatusChanged
            onStatusChanged(PrivilegedBackendStatus.READY)
        }
        override fun stop() { stopped = true }
        override fun requestPermission() = Unit
        override fun readActivityTopology(onResult: (TopologyReadResult) -> Unit) =
            onResult(TopologyReadResult.Success(dump))
    }

    private class ImmediateScheduler : CaptureScheduler {
        private var count = 0
        override fun schedule(delayMillis: Long, action: () -> Unit): CaptureCancellable {
            // Fire only the initial capture-delay action; the readiness timeout
            // (a later schedule) stays inert because the fake backend delivers
            // its READY status and dump synchronously before any timeout.
            if (count++ == 0) action()
            return CaptureCancellable { }
        }
    }

    private val clock = object : CaptureClock {
        override fun wallClockMillis() = 1_723_000_000_000L
        override fun monotonicMillis() = 500L
    }

    private fun captureOnce(dump: String): TargetCaptureResult {
        var result: TargetCaptureResult? = null
        var seq = 0L
        TargetCaptureController(
            backend = DumpBackend(dump),
            selfPackageName = self,
            captureDelayMillis = 0,
            readinessTimeoutMillis = 10_000,
            scheduler = ImmediateScheduler(),
            clock = clock,
            sequencer = { ++seq },
            onResult = { result = it },
        ).start()
        return requireNotNull(result)
    }

    private class QueuedTopologySource(fetches: List<TopologyFetch>) : TopologySource {
        private val queue = ArrayDeque(fetches)
        override fun fetch(onResult: (TopologyFetch) -> Unit) =
            onResult(queue.removeFirstOrNull() ?: TopologyFetch.Failed("no queued topology"))
    }

    private class MapGeometrySource(private val byDisplay: Map<Int, DesktopWorkAreaAssessment>) :
        DisplayGeometrySource {
        override fun read(displayId: Int): DesktopWorkAreaAssessment =
            byDisplay[displayId] ?: DesktopWorkAreaAssessment.Unsupported(displayId, "no geometry")
    }

    private class RecordingGateway : TaskResizeGateway {
        var lastTaskId: Int? = null
            private set
        var invocations = 0
            private set
        override fun resizeTask(
            taskId: Int,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
            onResult: (TaskResizeOutcome) -> Unit,
        ) {
            invocations++
            lastTaskId = taskId
            onResult(TaskResizeOutcome.CommandSucceeded)
        }
    }

    private fun externalGeometry(displayId: Int = 6): DesktopWorkAreaAssessment.Found =
        WorkspaceGeometryCalculator.calculate(
            DisplayGeometryReading(
                displayId = displayId,
                maximumBounds = GeometryRect(0, 0, 3840, 2160),
                statusBarInsets = GeometryInsets.NONE,
                navigationBarInsets = GeometryInsets(0, 0, 0, 56),
                captionBarInsets = GeometryInsets.NONE,
                systemBarInsets = GeometryInsets(0, 0, 0, 56),
                systemOverlayInsets = GeometryInsets.NONE,
                displayCutoutInsets = GeometryInsets.NONE,
                densityScale = 1.0f,
                densityDpi = 160,
            ),
        ) as DesktopWorkAreaAssessment.Found

    // ------------------------------------------------------------ parser lock

    @Test
    fun `scoped dump exposes external scoped focus and phone global focus`() {
        val snapshot = DesktopTopologyParser.parse(scopedDump())

        assertTrue(snapshot.hasDisplayScopedFocusEvidence)
        assertEquals(setOf(0, 6), snapshot.observedDisplayIds)
        // Legacy global focus is the phone task A (last mFocusedApp seen).
        assertEquals(phoneTaskA, snapshot.focusedTaskId)
        // Display 6's scoped focus is the external app B, not the phone.
        assertEquals(DisplayFocus.Task(externalTaskB), snapshot.focusedTaskByDisplay[6])
        assertEquals(DisplayFocus.Task(phoneTaskA), snapshot.focusedTaskByDisplay[0])
    }

    // ------------------------------------------------------- capture path

    @Test
    fun `one-shot capture resolves the external target B, never the phone focus A`() {
        val captured = captureOnce(scopedDump()) as TargetCaptureResult.Captured

        val found = captured.targets.getValue(6) as SnapTargetAssessment.Found
        assertEquals(externalTaskB, found.targetTaskId)
        assertEquals(chrome, found.packageName)
        // The phone task A is never surfaced as a target on any display.
        assertFalse(captured.targets.values.any {
            it is SnapTargetAssessment.Found && it.targetTaskId == phoneTaskA
        })
    }

    // --------------------------------------------- Phase 2C3A T1/T2 path

    @Test
    fun `mutation pre-acquisition (T1 T2) resolves external target B, never phone A`() {
        val geometry = externalGeometry(6)
        val destination = geometry.destinationQuadrants.getValue(Quadrant.TOP_LEFT)
        // T1/T2 revalidation reads have no bounds; T3 verification read reports
        // the resized bounds so the postcondition confirms the mutation on B.
        val preMutation = TopologyFetch.Fetched(DesktopTopologyParser.parse(scopedDump()))
        val verification = TopologyFetch.Fetched(DesktopTopologyParser.parse(scopedDump(destination)))
        val gateway = RecordingGateway()
        val orchestrator = SnapExecutionOrchestrator(
            topologySource = QueuedTopologySource(listOf(preMutation, preMutation, verification)),
            geometrySource = MapGeometrySource(mapOf(6 to geometry)),
            gateway = gateway,
            selfPackageName = self,
        )

        var result: uk.mr_biz.fourzones.snap.SnapExecutionResult? = null
        orchestrator.execute(Quadrant.TOP_LEFT) { result = it }

        // The single mutation was aimed at task B (external chrome), proving the
        // shared acquisition layer resolved B and the phone global focus A never
        // became the target.
        assertEquals(1, gateway.invocations)
        assertEquals(externalTaskB, gateway.lastTaskId)
        assertTrue(result is uk.mr_biz.fourzones.snap.SnapExecutionResult.AppliedAndVerified)
    }

    // ------------------------------------- the S25 failure, now fail-closed

    @Test
    fun `multi-display without scoped grammar fails closed instead of leaking phone focus`() {
        val captured = captureOnce(noScopedGrammarDump()) as TargetCaptureResult.Captured

        val display6 = captured.targets.getValue(6) as SnapTargetAssessment.NoTarget
        // The honest multi-display reason, NOT the pre-fix "outside the
        // desk-root hierarchy" that came from applying the phone focus.
        assertTrue(display6.reason.contains("not applying a global focus across multiple displays"))
        assertFalse(display6.reason.contains("outside the desk-root hierarchy"))
        // The phone task A is never selected.
        assertFalse(captured.targets.values.any {
            it is SnapTargetAssessment.Found && it.targetTaskId == phoneTaskA
        })
    }

    @Test
    fun `standalone single-display A9 still uses the legacy global focus`() {
        val captured = captureOnce(a9Dump()) as TargetCaptureResult.Captured

        val found = captured.targets.getValue(0) as SnapTargetAssessment.Found
        assertEquals(700, found.targetTaskId)
        assertFalse(captured.diagnostics.focusEvidence.hasDisplayScopedFocusEvidence)
        assertEquals(listOf(0), captured.diagnostics.focusEvidence.observedDisplayIds)
    }

    // --------------------------------------------------------- diagnostics

    @Test
    fun `capture diagnostics record sequence timestamp age and focus evidence`() {
        val captured = captureOnce(scopedDump()) as TargetCaptureResult.Captured
        val d = captured.diagnostics

        assertEquals(1L, d.sequenceNumber)
        assertEquals(1_723_000_000_000L, d.wallClockMillis)
        assertEquals(500L, d.monotonicMillis)
        // Monotonic age is computed from a later monotonic reading.
        assertEquals(250L, d.ageMillis(750L))
        assertEquals(0L, d.ageMillis(400L)) // never negative

        val evidence = d.focusEvidence
        assertTrue(evidence.hasDisplayScopedFocusEvidence)
        assertEquals(listOf(0, 6), evidence.observedDisplayIds)
        assertEquals(phoneTaskA, evidence.globalFocusedTaskId)
        assertEquals("task $externalTaskB", evidence.scopedFocusByDisplay[6])
        assertEquals("task $phoneTaskA", evidence.scopedFocusByDisplay[0])

        // Per-display projection mirrors the resolved target.
        val perDisplay6 = d.perDisplay.single { it.displayId == 6 }
        assertEquals(CaptureResultKind.FOUND, perDisplay6.result)
        assertEquals(externalTaskB, perDisplay6.taskId)
        assertEquals(chrome, perDisplay6.packageName)
    }

    @Test
    fun `diagnostics do not affect eligibility`() {
        // The eligibility map from the capture equals the resolver's own map on
        // the same snapshot: diagnostics are a pure projection, never an input.
        val snapshot = DesktopTopologyParser.parse(scopedDump())
        val directly = SnapTargetResolver.resolve(snapshot, self)
        val captured = captureOnce(scopedDump()) as TargetCaptureResult.Captured

        assertEquals(directly, captured.targets)
        // No-scoped diagnostics carry no invented focus for display 6.
        val failClosed = captureOnce(noScopedGrammarDump()) as TargetCaptureResult.Captured
        assertNull(failClosed.diagnostics.focusEvidence.scopedFocusByDisplay[6])
    }
}
