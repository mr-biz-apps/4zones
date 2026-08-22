package uk.mr_biz.fourzones.snap

import uk.mr_biz.fourzones.desktop.ActiveDesktopAssessment
import uk.mr_biz.fourzones.desktop.DeskRootType
import uk.mr_biz.fourzones.desktop.DesktopRoot
import uk.mr_biz.fourzones.desktop.DesktopTask
import uk.mr_biz.fourzones.desktop.DesktopTopologyParser
import uk.mr_biz.fourzones.desktop.DesktopTopologySnapshot
import uk.mr_biz.fourzones.desktop.TaskBounds
import uk.mr_biz.fourzones.geometry.DesktopWorkAreaAssessment
import uk.mr_biz.fourzones.geometry.DisplayGeometryReading
import uk.mr_biz.fourzones.geometry.GeometryInsets
import uk.mr_biz.fourzones.geometry.GeometryRect
import uk.mr_biz.fourzones.geometry.Quadrant
import uk.mr_biz.fourzones.geometry.WorkspaceGeometryCalculator
import uk.mr_biz.fourzones.privileged.PrivilegedBackendStatus
import uk.mr_biz.fourzones.privileged.TaskResizeGateway
import uk.mr_biz.fourzones.privileged.TaskResizeOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Orchestrator coverage using fake seams — never touches adb/cmd/Shizuku.
 * Fixtures are model-level; where hardware-shaped dimensions appear they are
 * test fixtures only, and destinations come from the REAL Phase 2C2
 * calculator so "sends exactly the destination" is verified against
 * production geometry, not a hand-copied constant.
 */
class SnapExecutionOrchestratorTest {

    private val self = "uk.mr_biz.fourzones"

    // -------------------------------------------------------------- fakes

    private class QueuedTopologySource(private val queue: ArrayDeque<TopologyFetch>) : TopologySource {
        var fetchCount = 0
            private set

        override fun fetch(onResult: (TopologyFetch) -> Unit) {
            fetchCount++
            onResult(queue.removeFirstOrNull() ?: TopologyFetch.Failed("no queued topology"))
        }
    }

    /** Stores each fetch callback so a test can deliver T1/T2/T3 on demand. */
    private class ManualTopologySource : TopologySource {
        val callbacks = mutableListOf<(TopologyFetch) -> Unit>()
        override fun fetch(onResult: (TopologyFetch) -> Unit) { callbacks += onResult }
        fun deliver(index: Int, fetch: TopologyFetch) = callbacks[index](fetch)
    }

    private class MapGeometrySource(
        private val byDisplay: Map<Int, DesktopWorkAreaAssessment>,
        private val default: (Int) -> DesktopWorkAreaAssessment = {
            DesktopWorkAreaAssessment.Unsupported(it, "no geometry for display")
        },
    ) : DisplayGeometrySource {
        override fun read(displayId: Int): DesktopWorkAreaAssessment =
            byDisplay[displayId] ?: default(displayId)
    }

    private class FakeGateway(private val outcome: TaskResizeOutcome) : TaskResizeGateway {
        var invocations = 0
            private set
        var lastTaskId: Int? = null
            private set
        var lastRect: GeometryRect? = null
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
            lastRect = GeometryRect(left, top, right, bottom)
            onResult(outcome)
        }
    }

    // ------------------------------------------------------------ fixtures

    private fun task(
        id: Int,
        pkg: String = "com.android.chrome",
        visible: Boolean? = true,
        bounds: TaskBounds? = null,
    ) = DesktopTask(
        taskId = id,
        packageName = pkg,
        componentName = "$pkg/.Main",
        visible = visible,
        focused = null,
        bounds = bounds,
    )

    private fun root(
        rootId: Int,
        displayId: Int?,
        type: DeskRootType = DeskRootType.ACTIVATABLE,
        visible: Boolean? = false,
        children: List<DesktopTask> = emptyList(),
    ) = DesktopRoot(
        rootTaskId = rootId,
        displayId = displayId,
        type = type,
        visible = visible,
        visibleRequested = visible,
        createdByOrganizer = true,
        forceHidden = if (type == DeskRootType.MINIMIZED) true else null,
        windowingMode = "freeform",
        childTasks = children,
    )

    private fun snapshot(roots: List<DesktopRoot>, focusedTaskId: Int?) = DesktopTopologySnapshot(
        roots = roots,
        activeDesktopByDisplay = DesktopTopologyParser.assessActiveDesktopByDisplay(roots),
        focusedTaskId = focusedTaskId,
        evidence = emptyList(),
    )

    /** Active desktop on [displayId] with one visible target task [taskId]. */
    private fun activeSnapshot(
        displayId: Int = 0,
        rootId: Int = 310,
        taskId: Int = 77,
        pkg: String = "com.android.chrome",
        taskBounds: TaskBounds? = null,
    ) = snapshot(
        roots = listOf(
            root(rootId, displayId, visible = true, children = listOf(task(taskId, pkg, bounds = taskBounds))),
            root(rootId + 1, displayId, type = DeskRootType.MINIMIZED),
        ),
        focusedTaskId = taskId,
    )

    private fun a9Geometry(displayId: Int = 0): DesktopWorkAreaAssessment.Found =
        WorkspaceGeometryCalculator.calculate(
            DisplayGeometryReading(
                displayId = displayId,
                maximumBounds = GeometryRect(0, 0, 1920, 1200),
                statusBarInsets = GeometryInsets(0, 45, 0, 0),
                navigationBarInsets = GeometryInsets(0, 0, 0, 72),
                captionBarInsets = GeometryInsets.NONE,
                systemBarInsets = GeometryInsets(0, 45, 0, 72),
                systemOverlayInsets = GeometryInsets.NONE,
                displayCutoutInsets = GeometryInsets.NONE,
                densityScale = 1.5f,
                densityDpi = 240,
            ),
        ) as DesktopWorkAreaAssessment.Found

    private fun qreatorGeometry(displayId: Int = 14): DesktopWorkAreaAssessment.Found =
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

    private fun run(
        quadrant: Quadrant,
        topology: List<TopologyFetch>,
        geometry: Map<Int, DesktopWorkAreaAssessment>,
        gateway: FakeGateway,
    ): Pair<SnapExecutionResult, QueuedTopologySource> {
        val source = QueuedTopologySource(ArrayDeque(topology))
        val orchestrator = SnapExecutionOrchestrator(source, MapGeometrySource(geometry), gateway, self)
        var result: SnapExecutionResult? = null
        orchestrator.execute(quadrant) { result = it }
        return requireNotNull(result) to source
    }

    // ----------------------------------------------------------- SUCCESS

    @Test
    fun `a9 TL sends exactly the phase 2c2 TL destination and verifies`() {
        val destination = a9Geometry().destinationQuadrants.getValue(Quadrant.TOP_LEFT)
        val t1 = TopologyFetch.Fetched(activeSnapshot())
        val t2 = TopologyFetch.Fetched(activeSnapshot())
        val t3 = TopologyFetch.Fetched(
            activeSnapshot(
                taskBounds = TaskBounds(destination.left, destination.top, destination.right, destination.bottom),
            ),
        )
        val gateway = FakeGateway(TaskResizeOutcome.CommandSucceeded)

        val (result, _) = run(Quadrant.TOP_LEFT, listOf(t1, t2, t3), mapOf(0 to a9Geometry()), gateway)

        assertEquals(GeometryRect(0, 45, 954, 580), destination)
        assertEquals(1, gateway.invocations)
        assertEquals(77, gateway.lastTaskId)
        assertEquals(destination, gateway.lastRect)
        assertTrue(result is SnapExecutionResult.AppliedAndVerified)
        assertEquals(destination, (result as SnapExecutionResult.AppliedAndVerified).bounds)
    }

    @Test
    fun `a9 BR sends exactly the BR destination`() {
        val destination = a9Geometry().destinationQuadrants.getValue(Quadrant.BOTTOM_RIGHT)
        val active = activeSnapshot()
        val verified = activeSnapshot(
            taskBounds = TaskBounds(destination.left, destination.top, destination.right, destination.bottom),
        )
        val gateway = FakeGateway(TaskResizeOutcome.CommandSucceeded)

        val (result, _) = run(
            Quadrant.BOTTOM_RIGHT,
            listOf(TopologyFetch.Fetched(active), TopologyFetch.Fetched(active), TopologyFetch.Fetched(verified)),
            mapOf(0 to a9Geometry()),
            gateway,
        )

        assertEquals(GeometryRect(966, 592, 1920, 1128), destination)
        assertEquals(destination, gateway.lastRect)
        assertTrue(result is SnapExecutionResult.AppliedAndVerified)
    }

    @Test
    fun `qreator TR uses external-display geometry`() {
        val destination = qreatorGeometry(14).destinationQuadrants.getValue(Quadrant.TOP_RIGHT)
        val active = activeSnapshot(displayId = 14, rootId = 900, taskId = 4100, pkg = "com.google.android.youtube")
        val verified = activeSnapshot(
            displayId = 14, rootId = 900, taskId = 4100, pkg = "com.google.android.youtube",
            taskBounds = TaskBounds(destination.left, destination.top, destination.right, destination.bottom),
        )
        val gateway = FakeGateway(TaskResizeOutcome.CommandSucceeded)

        val (result, _) = run(
            Quadrant.TOP_RIGHT,
            listOf(TopologyFetch.Fetched(active), TopologyFetch.Fetched(active), TopologyFetch.Fetched(verified)),
            mapOf(14 to qreatorGeometry(14)),
            gateway,
        )

        assertEquals(GeometryRect(1924, 0, 3840, 1048), destination)
        assertEquals(destination, gateway.lastRect)
        assertTrue(result is SnapExecutionResult.AppliedAndVerified)
    }

    @Test
    fun `target display's geometry is used not the host or default display`() {
        // Target resolves to display 14; display 0 also has (different)
        // geometry available. The gateway must receive display 14's rect.
        val destination14 = qreatorGeometry(14).destinationQuadrants.getValue(Quadrant.TOP_LEFT)
        val active = activeSnapshot(displayId = 14, rootId = 900, taskId = 4100)
        val verified = activeSnapshot(
            displayId = 14, rootId = 900, taskId = 4100,
            taskBounds = TaskBounds(destination14.left, destination14.top, destination14.right, destination14.bottom),
        )
        val gateway = FakeGateway(TaskResizeOutcome.CommandSucceeded)

        run(
            Quadrant.TOP_LEFT,
            listOf(TopologyFetch.Fetched(active), TopologyFetch.Fetched(active), TopologyFetch.Fetched(verified)),
            mapOf(0 to a9Geometry(0), 14 to qreatorGeometry(14)),
            gateway,
        )

        assertEquals(destination14, gateway.lastRect)
        // Sanity: display 0's TL differs, proving we did not use the default.
        assertTrue(a9Geometry(0).destinationQuadrants.getValue(Quadrant.TOP_LEFT) != destination14)
    }

    // --------------------------------------------------------- NO MUTATION

    private fun assertNoMutation(result: SnapExecutionResult, gateway: FakeGateway) {
        assertEquals(0, gateway.invocations)
    }

    @Test
    fun `self target does not mutate`() {
        val gateway = FakeGateway(TaskResizeOutcome.CommandSucceeded)
        val snap = activeSnapshot(pkg = self)
        val (result, _) = run(Quadrant.TOP_LEFT, listOf(TopologyFetch.Fetched(snap)), mapOf(0 to a9Geometry()), gateway)

        assertTrue(result is SnapExecutionResult.NoTarget)
        assertNoMutation(result, gateway)
    }

    @Test
    fun `launcher focus outside desk hierarchy does not mutate`() {
        // Focused task id is not any desk-root child.
        val snap = snapshot(
            roots = listOf(root(310, 0, visible = true, children = listOf(task(77)))),
            focusedTaskId = 9999,
        )
        val gateway = FakeGateway(TaskResizeOutcome.CommandSucceeded)
        val (result, _) = run(Quadrant.TOP_LEFT, listOf(TopologyFetch.Fetched(snap)), mapOf(0 to a9Geometry()), gateway)

        assertTrue(result is SnapExecutionResult.NoTarget)
        assertNoMutation(result, gateway)
    }

    @Test
    fun `no active desktop does not mutate`() {
        val snap = snapshot(
            roots = listOf(root(310, 0, visible = false, children = listOf(task(77)))),
            focusedTaskId = 77,
        )
        val gateway = FakeGateway(TaskResizeOutcome.CommandSucceeded)
        val (result, _) = run(Quadrant.TOP_LEFT, listOf(TopologyFetch.Fetched(snap)), mapOf(0 to a9Geometry()), gateway)

        assertTrue(result is SnapExecutionResult.NoTarget)
        assertNoMutation(result, gateway)
    }

    @Test
    fun `ambiguous active desktop does not mutate`() {
        val snap = snapshot(
            roots = listOf(
                root(310, 0, visible = true, children = listOf(task(77))),
                root(311, 0, visible = true, children = listOf(task(78))),
            ),
            focusedTaskId = 77,
        )
        val gateway = FakeGateway(TaskResizeOutcome.CommandSucceeded)
        val (result, _) = run(Quadrant.TOP_LEFT, listOf(TopologyFetch.Fetched(snap)), mapOf(0 to a9Geometry()), gateway)

        assertTrue(result is SnapExecutionResult.NoTarget)
        assertNoMutation(result, gateway)
    }

    @Test
    fun `target changed between first and second read does not mutate`() {
        val t1 = TopologyFetch.Fetched(activeSnapshot(taskId = 77))
        val t2 = TopologyFetch.Fetched(activeSnapshot(taskId = 88)) // different focused task
        val gateway = FakeGateway(TaskResizeOutcome.CommandSucceeded)

        val (result, _) = run(Quadrant.TOP_LEFT, listOf(t1, t2), mapOf(0 to a9Geometry()), gateway)

        assertTrue(result is SnapExecutionResult.PreconditionChanged)
        assertNoMutation(result, gateway)
    }

    @Test
    fun `target display changed between reads does not mutate`() {
        val t1 = TopologyFetch.Fetched(activeSnapshot(displayId = 0, taskId = 77))
        val t2 = TopologyFetch.Fetched(activeSnapshot(displayId = 14, rootId = 900, taskId = 77))
        val gateway = FakeGateway(TaskResizeOutcome.CommandSucceeded)

        val (result, _) = run(
            Quadrant.TOP_LEFT, listOf(t1, t2),
            mapOf(0 to a9Geometry(0), 14 to qreatorGeometry(14)), gateway,
        )

        assertTrue(result is SnapExecutionResult.PreconditionChanged)
        assertNoMutation(result, gateway)
    }

    @Test
    fun `geometry unavailable does not mutate`() {
        val gateway = FakeGateway(TaskResizeOutcome.CommandSucceeded)
        val (result, _) = run(
            Quadrant.TOP_LEFT, listOf(TopologyFetch.Fetched(activeSnapshot())),
            mapOf(0 to DesktopWorkAreaAssessment.Unsupported(0, "detached")), gateway,
        )

        assertTrue(result is SnapExecutionResult.GeometryUnavailable)
        assertNoMutation(result, gateway)
    }

    @Test
    fun `geometry invalid does not mutate`() {
        val gateway = FakeGateway(TaskResizeOutcome.CommandSucceeded)
        val (result, _) = run(
            Quadrant.TOP_LEFT, listOf(TopologyFetch.Fetched(activeSnapshot())),
            mapOf(0 to DesktopWorkAreaAssessment.Invalid(0, "insets consume all width")), gateway,
        )

        assertTrue(result is SnapExecutionResult.GeometryUnavailable)
        assertNoMutation(result, gateway)
    }

    @Test
    fun `privilege unavailable at first read does not mutate`() {
        val gateway = FakeGateway(TaskResizeOutcome.CommandSucceeded)
        val (result, source) = run(
            Quadrant.TOP_LEFT,
            listOf(TopologyFetch.Unavailable(PrivilegedBackendStatus.PERMISSION_REQUIRED)),
            mapOf(0 to a9Geometry()), gateway,
        )

        assertTrue(result is SnapExecutionResult.PrivilegeUnavailable)
        assertEquals(1, source.fetchCount) // never even reached geometry/second read
        assertNoMutation(result, gateway)
    }

    @Test
    fun `permission denied at first read does not mutate`() {
        val gateway = FakeGateway(TaskResizeOutcome.CommandSucceeded)
        val (result, _) = run(
            Quadrant.TOP_LEFT,
            listOf(TopologyFetch.Unavailable(PrivilegedBackendStatus.PERMISSION_DENIED)),
            mapOf(0 to a9Geometry()), gateway,
        )

        assertEquals(
            PrivilegedBackendStatus.PERMISSION_DENIED,
            (result as SnapExecutionResult.PrivilegeUnavailable).status,
        )
        assertNoMutation(result, gateway)
    }

    @Test
    fun `stale user service version mismatch fails closed without resolving or mutating`() {
        // A stale UserService surfaces as an unavailable backend; the mutation
        // path must stop at read 1 — never resolve a target, never mutate.
        val gateway = FakeGateway(TaskResizeOutcome.CommandSucceeded)
        val (result, source) = run(
            Quadrant.TOP_LEFT,
            listOf(TopologyFetch.Unavailable(PrivilegedBackendStatus.USER_SERVICE_VERSION_MISMATCH)),
            mapOf(0 to a9Geometry()), gateway,
        )

        assertEquals(
            PrivilegedBackendStatus.USER_SERVICE_VERSION_MISMATCH,
            (result as SnapExecutionResult.PrivilegeUnavailable).status,
        )
        assertEquals(1, source.fetchCount) // no revalidation/geometry/verification read
        assertNoMutation(result, gateway)
    }

    @Test
    fun `gateway backend-unavailable maps to privilege unavailable`() {
        val gateway = FakeGateway(
            TaskResizeOutcome.BackendUnavailable(PrivilegedBackendStatus.BINDER_DIED),
        )
        val active = activeSnapshot()
        val (result, _) = run(
            Quadrant.TOP_LEFT,
            listOf(TopologyFetch.Fetched(active), TopologyFetch.Fetched(active)),
            mapOf(0 to a9Geometry()), gateway,
        )

        assertEquals(1, gateway.invocations)
        assertTrue(result is SnapExecutionResult.PrivilegeUnavailable)
    }

    @Test
    fun `gateway rejection maps to invalid destination`() {
        val gateway = FakeGateway(TaskResizeOutcome.Rejected("inverted"))
        val active = activeSnapshot()
        val (result, _) = run(
            Quadrant.TOP_LEFT,
            listOf(TopologyFetch.Fetched(active), TopologyFetch.Fetched(active)),
            mapOf(0 to a9Geometry()), gateway,
        )

        assertTrue(result is SnapExecutionResult.InvalidDestination)
    }

    // -------------------------------------------------------- COMMAND FAILURE

    @Test
    fun `nonzero command result maps to command failed`() {
        val gateway = FakeGateway(TaskResizeOutcome.CommandFailed)
        val active = activeSnapshot()
        val (result, _) = run(
            Quadrant.TOP_LEFT,
            listOf(TopologyFetch.Fetched(active), TopologyFetch.Fetched(active)),
            mapOf(0 to a9Geometry()), gateway,
        )

        assertTrue(result is SnapExecutionResult.CommandFailed)
    }

    @Test
    fun `command timeout maps to command timed out`() {
        val gateway = FakeGateway(TaskResizeOutcome.TimedOut)
        val active = activeSnapshot()
        val (result, _) = run(
            Quadrant.TOP_LEFT,
            listOf(TopologyFetch.Fetched(active), TopologyFetch.Fetched(active)),
            mapOf(0 to a9Geometry()), gateway,
        )

        assertTrue(result is SnapExecutionResult.CommandTimedOut)
    }

    @Test
    fun `gateway process error maps to command failed`() {
        val gateway = FakeGateway(TaskResizeOutcome.ProcessError)
        val active = activeSnapshot()
        val (result, _) = run(
            Quadrant.TOP_LEFT,
            listOf(TopologyFetch.Fetched(active), TopologyFetch.Fetched(active)),
            mapOf(0 to a9Geometry()), gateway,
        )

        assertTrue(result is SnapExecutionResult.CommandFailed)
    }

    // ---------------------------------------------------------- POSTCONDITION

    @Test
    fun `task vanished after command is a mismatch with null observed`() {
        val destination = a9Geometry().destinationQuadrants.getValue(Quadrant.TOP_LEFT)
        val active = activeSnapshot()
        // T3 has no matching task.
        val t3 = TopologyFetch.Fetched(
            snapshot(listOf(root(310, 0, visible = true, children = emptyList())), focusedTaskId = null),
        )
        val gateway = FakeGateway(TaskResizeOutcome.CommandSucceeded)

        val (result, _) = run(
            Quadrant.TOP_LEFT,
            listOf(TopologyFetch.Fetched(active), TopologyFetch.Fetched(active), t3),
            mapOf(0 to a9Geometry()), gateway,
        )

        val mismatch = result as SnapExecutionResult.PostconditionMismatch
        assertNull(mismatch.observed)
        assertEquals(destination, mismatch.requested)
    }

    @Test
    fun `requested not equal observed is a mismatch`() {
        val active = activeSnapshot()
        val t3 = TopologyFetch.Fetched(activeSnapshot(taskBounds = TaskBounds(1, 2, 3, 4)))
        val gateway = FakeGateway(TaskResizeOutcome.CommandSucceeded)

        val (result, _) = run(
            Quadrant.TOP_LEFT,
            listOf(TopologyFetch.Fetched(active), TopologyFetch.Fetched(active), t3),
            mapOf(0 to a9Geometry()), gateway,
        )

        val mismatch = result as SnapExecutionResult.PostconditionMismatch
        assertEquals(GeometryRect(1, 2, 3, 4), mismatch.observed)
    }

    @Test
    fun `observed bounds unavailable is postcondition unavailable`() {
        val active = activeSnapshot()
        val t3 = TopologyFetch.Fetched(activeSnapshot(taskBounds = null))
        val gateway = FakeGateway(TaskResizeOutcome.CommandSucceeded)

        val (result, _) = run(
            Quadrant.TOP_LEFT,
            listOf(TopologyFetch.Fetched(active), TopologyFetch.Fetched(active), t3),
            mapOf(0 to a9Geometry()), gateway,
        )

        assertTrue(result is SnapExecutionResult.PostconditionUnavailable)
    }

    @Test
    fun `exact observed bounds is applied and verified`() {
        val destination = a9Geometry().destinationQuadrants.getValue(Quadrant.TOP_LEFT)
        val active = activeSnapshot()
        val t3 = TopologyFetch.Fetched(
            activeSnapshot(
                taskBounds = TaskBounds(destination.left, destination.top, destination.right, destination.bottom),
            ),
        )
        val gateway = FakeGateway(TaskResizeOutcome.CommandSucceeded)

        val (result, _) = run(
            Quadrant.TOP_LEFT,
            listOf(TopologyFetch.Fetched(active), TopologyFetch.Fetched(active), t3),
            mapOf(0 to a9Geometry()), gateway,
        )

        assertTrue(result is SnapExecutionResult.AppliedAndVerified)
    }

    // ------------------------------------------- cancellation at the boundary

    @Test
    fun `supersession after first read but before second completes does not mutate`() {
        val source = ManualTopologySource()
        var cancelled = false
        val gateway = FakeGateway(TaskResizeOutcome.CommandSucceeded)
        val orchestrator = SnapExecutionOrchestrator(source, MapGeometrySource(mapOf(0 to a9Geometry())), gateway, self)
        var result: SnapExecutionResult? = null
        orchestrator.execute(Quadrant.TOP_LEFT, isCancelled = { cancelled }) { result = it }

        // T1 completes; geometry read runs; T2 fetch is now queued.
        source.deliver(0, TopologyFetch.Fetched(activeSnapshot()))
        // Replacement/stop happens here — after T1 started, before T2 completes.
        cancelled = true
        source.deliver(1, TopologyFetch.Fetched(activeSnapshot()))

        assertEquals(0, gateway.invocations)
        assertTrue(result is SnapExecutionResult.Cancelled)
    }

    @Test
    fun `not cancelled proceeds through the boundary to mutation`() {
        val source = ManualTopologySource()
        val gateway = FakeGateway(TaskResizeOutcome.CommandSucceeded)
        val destination = a9Geometry().destinationQuadrants.getValue(Quadrant.TOP_LEFT)
        val orchestrator = SnapExecutionOrchestrator(source, MapGeometrySource(mapOf(0 to a9Geometry())), gateway, self)
        var result: SnapExecutionResult? = null
        orchestrator.execute(Quadrant.TOP_LEFT, isCancelled = { false }) { result = it }

        source.deliver(0, TopologyFetch.Fetched(activeSnapshot()))
        source.deliver(1, TopologyFetch.Fetched(activeSnapshot()))
        source.deliver(
            2,
            TopologyFetch.Fetched(
                activeSnapshot(
                    taskBounds = TaskBounds(destination.left, destination.top, destination.right, destination.bottom),
                ),
            ),
        )

        assertEquals(1, gateway.invocations)
        assertTrue(result is SnapExecutionResult.AppliedAndVerified)
    }

    // --------------------------------- T3 active-desktop membership (IMPORTANT)

    @Test
    fun `task on an inactive desktop of the same display does not verify`() {
        val destination = a9Geometry().destinationQuadrants.getValue(Quadrant.TOP_LEFT)
        val active = activeSnapshot(rootId = 310, taskId = 77)
        // T3: the target task 77 sits under an INACTIVE root; a DIFFERENT root
        // is the active desktop on display 0. Bounds even match.
        val t3 = snapshot(
            roots = listOf(
                root(990, 0, visible = true, children = listOf(task(555))),
                root(
                    310, 0, visible = false,
                    children = listOf(
                        task(77, bounds = TaskBounds(destination.left, destination.top, destination.right, destination.bottom)),
                    ),
                ),
            ),
            focusedTaskId = 555,
        )
        val gateway = FakeGateway(TaskResizeOutcome.CommandSucceeded)

        val (result, _) = run(
            Quadrant.TOP_LEFT,
            listOf(TopologyFetch.Fetched(active), TopologyFetch.Fetched(active), TopologyFetch.Fetched(t3)),
            mapOf(0 to a9Geometry()), gateway,
        )

        val mismatch = result as SnapExecutionResult.PostconditionMismatch
        assertTrue(mismatch.reason.contains("active desktop"))
    }

    @Test
    fun `no active desktop at postcondition does not verify`() {
        val destination = a9Geometry().destinationQuadrants.getValue(Quadrant.TOP_LEFT)
        val active = activeSnapshot(rootId = 310, taskId = 77)
        // T3: root present but not positively visible => None; task bounds match.
        val t3 = snapshot(
            roots = listOf(
                root(
                    310, 0, visible = false,
                    children = listOf(
                        task(77, bounds = TaskBounds(destination.left, destination.top, destination.right, destination.bottom)),
                    ),
                ),
            ),
            focusedTaskId = 77,
        )
        val gateway = FakeGateway(TaskResizeOutcome.CommandSucceeded)

        val (result, _) = run(
            Quadrant.TOP_LEFT,
            listOf(TopologyFetch.Fetched(active), TopologyFetch.Fetched(active), TopologyFetch.Fetched(t3)),
            mapOf(0 to a9Geometry()), gateway,
        )

        assertTrue(result is SnapExecutionResult.PostconditionMismatch)
    }

    @Test
    fun `duplicate task ids at postcondition do not verify`() {
        val active = activeSnapshot(rootId = 310, taskId = 77)
        // Two roots both containing task 77 (malformed) => ambiguous, not verified.
        val t3 = snapshot(
            roots = listOf(
                root(310, 0, visible = true, children = listOf(task(77, bounds = TaskBounds(0, 45, 954, 580)))),
                root(311, 0, visible = false, children = listOf(task(77, bounds = TaskBounds(0, 45, 954, 580)))),
            ),
            focusedTaskId = 77,
        )
        val gateway = FakeGateway(TaskResizeOutcome.CommandSucceeded)

        val (result, _) = run(
            Quadrant.TOP_LEFT,
            listOf(TopologyFetch.Fetched(active), TopologyFetch.Fetched(active), TopologyFetch.Fetched(t3)),
            mapOf(0 to a9Geometry()), gateway,
        )

        val mismatch = result as SnapExecutionResult.PostconditionMismatch
        assertNull(mismatch.observed)
        assertTrue(mismatch.reason.contains("ambiguous"))
    }

    @Test
    fun `postcondition topology unavailable is reported`() {
        val active = activeSnapshot()
        val gateway = FakeGateway(TaskResizeOutcome.CommandSucceeded)

        val (result, _) = run(
            Quadrant.TOP_LEFT,
            listOf(
                TopologyFetch.Fetched(active),
                TopologyFetch.Fetched(active),
                TopologyFetch.Unavailable(PrivilegedBackendStatus.BINDER_DIED),
            ),
            mapOf(0 to a9Geometry()), gateway,
        )

        assertEquals(1, gateway.invocations)
        assertTrue(result is SnapExecutionResult.PostconditionUnavailable)
    }
}
