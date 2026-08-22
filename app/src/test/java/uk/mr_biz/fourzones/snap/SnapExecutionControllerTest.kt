package uk.mr_biz.fourzones.snap

import uk.mr_biz.fourzones.desktop.DesktopTopologyParser
import uk.mr_biz.fourzones.desktop.DesktopTopologySnapshot
import uk.mr_biz.fourzones.geometry.DesktopWorkAreaAssessment
import uk.mr_biz.fourzones.geometry.DisplayGeometryReading
import uk.mr_biz.fourzones.geometry.GeometryInsets
import uk.mr_biz.fourzones.geometry.GeometryRect
import uk.mr_biz.fourzones.geometry.Quadrant
import uk.mr_biz.fourzones.geometry.WorkspaceGeometryCalculator
import uk.mr_biz.fourzones.privileged.TaskResizeGateway
import uk.mr_biz.fourzones.privileged.TaskResizeOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** No-target snapshot: the orchestrator will never call the gateway. */
private fun noTargetSnapshot(): DesktopTopologySnapshot =
    DesktopTopologyParser.parse(
        """
        Display #0 (activities from top to bottom):
          * Task{a #310 type=undefined dw=activatable U=0 visible=false visibleRequested=false mode=freeform translucent=true sz=1}
            mDeskRootTaskType=activatable
        """.trimIndent(),
    )

/** Active desktop on display 0 with one visible chrome target task 77. */
private fun activeSnapshot(): DesktopTopologySnapshot =
    DesktopTopologyParser.parse(
        """
        Display #0 (activities from top to bottom):
          * Task{a #310 type=undefined dw=activatable U=0 visible=true visibleRequested=true mode=freeform translucent=true sz=1}
            mDeskRootTaskType=activatable
            * Task{b #77 type=standard A=10123:com.android.chrome U=0 rootTaskId=310 visible=true visibleRequested=true mode=freeform translucent=true sz=1}
        ActivityTaskSupervisor state:
          mFocusedApp=ActivityRecord{f u0 com.android.chrome/.Main t77}
        """.trimIndent(),
    )

private fun a9Geometry(): DesktopWorkAreaAssessment.Found =
    WorkspaceGeometryCalculator.calculate(
        DisplayGeometryReading(
            displayId = 0,
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

private class ManualTopologySource : TopologySource {
    private val callbacks = mutableListOf<(TopologyFetch) -> Unit>()
    override fun fetch(onResult: (TopologyFetch) -> Unit) { callbacks += onResult }
    fun deliver(index: Int, fetch: TopologyFetch) = callbacks[index](fetch)
}

/**
 * Deterministic lifecycle tests for the delayed one-shot controller, using a
 * manual scheduler. They prove single-pending, deterministic replacement, and
 * that stale firings/results can never trigger a mutation.
 */
class SnapExecutionControllerTest {

    private class ManualScheduler : DelayedScheduler {
        private class Entry(val action: () -> Unit) {
            var cancelled = false
        }
        private val entries = mutableListOf<Entry>()

        override fun schedule(delayMillis: Long, action: () -> Unit): DelayedCancellable {
            val entry = Entry(action)
            entries += entry
            return DelayedCancellable { entry.cancelled = true }
        }

        val scheduledCount: Int get() = entries.size
        fun isCancelled(index: Int) = entries[index].cancelled
        fun fire(index: Int, evenIfCancelled: Boolean = false) {
            val entry = entries[index]
            if (!entry.cancelled || evenIfCancelled) entry.action()
        }
    }

    /** Gateway that records invocations; the orchestrator only reaches it on success paths. */
    private class CountingGateway : TaskResizeGateway {
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
            onResult(TaskResizeOutcome.CommandSucceeded)
        }
    }

    private class Harness {
        val scheduler = ManualScheduler()
        val gateway = CountingGateway()
        val states = mutableListOf<SnapExecutionState>()
        val orchestrator = SnapExecutionOrchestrator(
            topologySource = { onResult -> onResult(TopologyFetch.Fetched(noTargetSnapshot())) },
            geometrySource = { id -> DesktopWorkAreaAssessment.Unsupported(id, "n/a") },
            gateway = gateway,
            selfPackageName = "uk.mr_biz.fourzones",
        )
        val controller = SnapExecutionController(
            orchestrator = orchestrator,
            delayMillis = 5_000,
            scheduler = scheduler,
            onStateChanged = { states += it },
        )

        init {
            controller.start()
        }
    }

    @Test
    fun `only one delayed action is pending at a time`() {
        val h = Harness()

        h.controller.requestSnap(Quadrant.TOP_LEFT)
        h.controller.requestSnap(Quadrant.TOP_RIGHT)

        // Two schedules exist, but the first was cancelled by the replacement.
        assertEquals(2, h.scheduler.scheduledCount)
        assertTrue(h.scheduler.isCancelled(0))
    }

    @Test
    fun `replacement cancels the prior pending firing deterministically`() {
        val h = Harness()

        h.controller.requestSnap(Quadrant.TOP_LEFT)
        h.controller.requestSnap(Quadrant.BOTTOM_RIGHT)

        // Firing the cancelled first timer (even if the runtime raced) is inert.
        h.scheduler.fire(0, evenIfCancelled = true)
        // No execution state was produced by the stale firing.
        assertTrue(h.states.none { it is SnapExecutionState.Executing })

        // The current (second) timer runs the execution.
        h.scheduler.fire(1)
        assertTrue(h.states.any { it is SnapExecutionState.Executing })
        assertEquals(0, h.gateway.invocations) // no-target snapshot => no mutation
    }

    @Test
    fun `stale result from a superseded generation cannot publish`() {
        val h = Harness()
        h.controller.requestSnap(Quadrant.TOP_LEFT)
        // Replace before firing the first.
        h.controller.requestSnap(Quadrant.TOP_RIGHT)

        val completedBefore = h.states.count { it is SnapExecutionState.Completed }
        h.scheduler.fire(0, evenIfCancelled = true) // stale generation: no completion
        assertEquals(completedBefore, h.states.count { it is SnapExecutionState.Completed })
    }

    @Test
    fun `stop prevents a late firing from executing or mutating`() {
        val h = Harness()
        h.controller.requestSnap(Quadrant.TOP_LEFT)

        h.controller.stop()
        h.scheduler.fire(0, evenIfCancelled = true)

        assertTrue(h.states.none { it is SnapExecutionState.Executing })
        assertEquals(0, h.gateway.invocations)
    }

    @Test
    fun `requestSnap before start is a no-op`() {
        val scheduler = ManualScheduler()
        val gateway = CountingGateway()
        val orchestrator = SnapExecutionOrchestrator(
            topologySource = { onResult -> onResult(TopologyFetch.Fetched(noTargetSnapshot())) },
            geometrySource = { id -> DesktopWorkAreaAssessment.Unsupported(id, "n/a") },
            gateway = gateway,
            selfPackageName = "uk.mr_biz.fourzones",
        )
        val controller = SnapExecutionController(orchestrator, 5_000, scheduler) {}

        controller.requestSnap(Quadrant.TOP_LEFT)

        assertEquals(0, scheduler.scheduledCount)
    }

    @Test
    fun `replacement after first read but before second completes prevents mutation`() {
        // A manual source lets us interleave a replacement between T1 and T2.
        val source = ManualTopologySource()
        val scheduler = ManualScheduler()
        val gateway = CountingGateway()
        val orchestrator = SnapExecutionOrchestrator(
            topologySource = source,
            geometrySource = { _ -> a9Geometry() },
            gateway = gateway,
            selfPackageName = "uk.mr_biz.fourzones",
        )
        val controller = SnapExecutionController(orchestrator, 5_000, scheduler) {}
        controller.start()

        controller.requestSnap(Quadrant.TOP_LEFT)
        scheduler.fire(0) // execution begins; T1 fetch queued
        source.deliver(0, TopologyFetch.Fetched(activeSnapshot())) // geometry runs; T2 queued

        // Replacement while T2 is still outstanding: bumps the generation, so
        // the old execution's isCancelled() predicate is now true.
        controller.requestSnap(Quadrant.BOTTOM_RIGHT)

        source.deliver(1, TopologyFetch.Fetched(activeSnapshot())) // old T2 completes
        assertEquals(0, gateway.invocations) // no mutation from the superseded run
    }

    @Test
    fun `stop after first read but before second completes prevents mutation`() {
        val source = ManualTopologySource()
        val scheduler = ManualScheduler()
        val gateway = CountingGateway()
        val orchestrator = SnapExecutionOrchestrator(
            topologySource = source,
            geometrySource = { _ -> a9Geometry() },
            gateway = gateway,
            selfPackageName = "uk.mr_biz.fourzones",
        )
        val controller = SnapExecutionController(orchestrator, 5_000, scheduler) {}
        controller.start()

        controller.requestSnap(Quadrant.TOP_LEFT)
        scheduler.fire(0)
        source.deliver(0, TopologyFetch.Fetched(activeSnapshot()))

        controller.stop() // stops before the mutation boundary

        source.deliver(1, TopologyFetch.Fetched(activeSnapshot()))
        assertEquals(0, gateway.invocations)
    }

    @Test
    fun `pending then executing then completed states are published in order`() {
        val h = Harness()
        h.controller.requestSnap(Quadrant.TOP_LEFT)
        h.scheduler.fire(0)

        val kinds = h.states.map { it::class.simpleName }
        val pending = kinds.indexOf("Pending")
        val executing = kinds.indexOf("Executing")
        val completed = kinds.indexOf("Completed")
        assertTrue(pending in 0 until executing && executing < completed)
    }
}
