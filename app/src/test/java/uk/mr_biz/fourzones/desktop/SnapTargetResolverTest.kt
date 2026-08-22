package uk.mr_biz.fourzones.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures mirror the empirically observed Samsung topology shape (multiple
 * activatable + minimized desk roots, multi-task desktops, hierarchy-based
 * membership) with arbitrary session-local IDs — observed A9 root IDs are
 * never encoded as semantic constants.
 *
 * The active-desktop map is produced by the real (unchanged) Phase 2B
 * resolver, so these tests also pin that target resolution consumes it
 * as-is rather than re-deriving desktop identity from focus.
 */
class SnapTargetResolverTest {

    private val selfPackage = "com.example.selfapp"

    private fun task(
        id: Int,
        pkg: String = "com.example.app",
        component: String? = "$pkg/.Main",
        visible: Boolean? = true,
        focused: Boolean? = false,
    ) = DesktopTask(
        taskId = id,
        packageName = pkg,
        componentName = component,
        visible = visible,
        focused = focused,
    )

    private fun root(
        id: Int,
        displayId: Int? = 0,
        type: DeskRootType = DeskRootType.ACTIVATABLE,
        visible: Boolean? = false,
        children: List<DesktopTask> = emptyList(),
    ) = DesktopRoot(
        rootTaskId = id,
        displayId = displayId,
        type = type,
        visible = visible,
        visibleRequested = visible,
        createdByOrganizer = true,
        forceHidden = if (type == DeskRootType.MINIMIZED) true else null,
        windowingMode = "freeform",
        childTasks = children,
    )

    private fun snapshot(
        roots: List<DesktopRoot>,
        focusedTaskId: Int?,
        focusedByDisplay: Map<Int, DisplayFocus> = emptyMap(),
        hasScopedEvidence: Boolean = false,
    ) = DesktopTopologySnapshot(
        roots = roots,
        activeDesktopByDisplay = DesktopTopologyParser.assessActiveDesktopByDisplay(roots),
        focusedTaskId = focusedTaskId,
        focusedTaskByDisplay = focusedByDisplay,
        hasDisplayScopedFocusEvidence = hasScopedEvidence,
        evidence = emptyList(),
    )

    /** The observed A9 shape with synthetic IDs: 4 activatable + minimized roots. */
    private fun fourDesktopRoots(
        activeRootId: Int = 310,
        chromeTaskId: Int = 77,
        youtubeTaskId: Int = 4100,
    ) = listOf(
        root(
            activeRootId,
            visible = true,
            children = listOf(
                task(chromeTaskId, pkg = "com.android.chrome"),
                task(youtubeTaskId, pkg = "com.google.android.youtube"),
            ),
        ),
        root(9021, children = listOf(task(15, pkg = "com.example.photos"))),
        root(64, children = listOf(task(888888, pkg = "com.example.store"))),
        root(500100, children = emptyList()),
        root(12, type = DeskRootType.MINIMIZED),
        root(77070, type = DeskRootType.MINIMIZED, children = listOf(task(3, pkg = "com.example.mail"))),
    )

    private fun resolveSingle(
        roots: List<DesktopRoot>,
        focusedTaskId: Int?,
        displayId: Int = 0,
    ): SnapTargetAssessment =
        SnapTargetResolver.resolve(snapshot(roots, focusedTaskId), selfPackage)
            .getValue(displayId)

    // A. Active root Found + focused Chrome child -> Found(Chrome).
    @Test
    fun `focused chrome child of the active root is the target`() {
        val result = resolveSingle(fourDesktopRoots(), focusedTaskId = 77)

        assertEquals(
            SnapTargetAssessment.Found(
                displayId = 0,
                activeDeskRootId = 310,
                targetTaskId = 77,
                packageName = "com.android.chrome",
                componentName = "com.android.chrome/.Main",
            ),
            result,
        )
    }

    // B. Active root Found + focused YouTube child -> Found(YouTube).
    @Test
    fun `focused youtube child of the active root is the target`() {
        val result = resolveSingle(fourDesktopRoots(), focusedTaskId = 4100)

        val found = result as SnapTargetAssessment.Found
        assertEquals(4100, found.targetTaskId)
        assertEquals("com.google.android.youtube", found.packageName)
        assertEquals(310, found.activeDeskRootId)
    }

    // C. Focused task belongs to another inactive activatable root -> no target.
    @Test
    fun `focused task in an inactive activatable root is not a target`() {
        val result = resolveSingle(fourDesktopRoots(), focusedTaskId = 15)

        val noTarget = result as SnapTargetAssessment.NoTarget
        assertTrue(noTarget.reason.contains("belongs to desk root 9021"))
        assertTrue(noTarget.reason.contains("ACTIVATABLE"))
    }

    // D. Focused task belongs to a minimized root -> no target.
    @Test
    fun `focused task in a minimized root is not a target`() {
        val result = resolveSingle(fourDesktopRoots(), focusedTaskId = 3)

        val noTarget = result as SnapTargetAssessment.NoTarget
        assertTrue(noTarget.reason.contains("belongs to desk root 77070"))
        assertTrue(noTarget.reason.contains("MINIMIZED"))
    }

    // E. Focused task is the host app itself -> no target/self.
    @Test
    fun `focused host app task is never a target`() {
        val roots = listOf(
            root(
                310,
                visible = true,
                children = listOf(
                    task(41, pkg = selfPackage, component = "$selfPackage/.MainActivity"),
                    task(42, pkg = "com.android.chrome"),
                ),
            ),
        )

        val result = resolveSingle(roots, focusedTaskId = 41)

        val noTarget = result as SnapTargetAssessment.NoTarget
        assertTrue(noTarget.reason.contains("host app itself"))
    }

    @Test
    fun `self detection also works from component name alone`() {
        val roots = listOf(
            root(
                310,
                visible = true,
                children = listOf(
                    task(41, pkg = "com.example.other", component = "$selfPackage/.MainActivity"),
                ),
            ),
        )

        val result = resolveSingle(roots, focusedTaskId = 41)

        assertTrue((result as SnapTargetAssessment.NoTarget).reason.contains("host app itself"))
    }

    // F. Focused launcher/system task outside any desk root -> no target.
    @Test
    fun `focus outside the desk-root hierarchy is not a target`() {
        // Launcher focus: mFocusedApp points at a task no desk root contains
        // (the observed real-world case, and the adb-launch case).
        val result = resolveSingle(fourDesktopRoots(), focusedTaskId = 999)

        val noTarget = result as SnapTargetAssessment.NoTarget
        assertTrue(noTarget.reason.contains("outside the desk-root hierarchy"))
    }

    // G. No focused task -> no target.
    @Test
    fun `no focused task means no target`() {
        val result = resolveSingle(fourDesktopRoots(), focusedTaskId = null)

        assertTrue((result as SnapTargetAssessment.NoTarget).reason.contains("no focused task"))
    }

    // H. Active desktop None -> no target.
    @Test
    fun `no active desktop means no target is manufactured`() {
        val roots = fourDesktopRoots().map { it.copy(visible = false, visibleRequested = false) }

        val result = resolveSingle(roots, focusedTaskId = 77)

        // Even though task 77 exists and is focused, no target appears.
        val noTarget = result as SnapTargetAssessment.NoTarget
        assertTrue(noTarget.reason.contains("no active desktop"))
    }

    // I. Active desktop Ambiguous -> propagated ambiguity, no target.
    @Test
    fun `ambiguous active desktop propagates without guessing a target`() {
        val roots = listOf(
            root(310, visible = true, children = listOf(task(77))),
            root(311, visible = true, children = listOf(task(78))),
        )

        val result = resolveSingle(roots, focusedTaskId = 77)

        val ambiguous = result as SnapTargetAssessment.Ambiguous
        assertTrue(ambiguous.reason.contains("ambiguous"))
    }

    @Test
    fun `unsupported desk topology propagates as unsupported`() {
        val roots = listOf(root(310, type = DeskRootType.UNKNOWN, visible = true))

        val result = resolveSingle(roots, focusedTaskId = 77)

        assertTrue(result is SnapTargetAssessment.Unsupported)
    }

    // J. Explicitly invisible focused child -> no target.
    @Test
    fun `explicitly invisible focused child is not a target`() {
        val roots = listOf(
            root(
                310,
                visible = true,
                children = listOf(task(77, pkg = "com.android.chrome", visible = false)),
            ),
        )

        val result = resolveSingle(roots, focusedTaskId = 77)

        assertTrue((result as SnapTargetAssessment.NoTarget).reason.contains("not visible"))
    }

    @Test
    fun `unknown visibility is conservatively not a target and says so`() {
        val roots = listOf(
            root(
                310,
                visible = true,
                children = listOf(task(77, pkg = "com.android.chrome", visible = null)),
            ),
        )

        val result = resolveSingle(roots, focusedTaskId = 77)

        val noTarget = result as SnapTargetAssessment.NoTarget
        assertTrue(noTarget.reason.contains("unknown visibility"))
        assertTrue(noTarget.reason.contains("conservatively"))
    }

    // K. Opaque/adjacent IDs must not influence selection.
    @Test
    fun `id values and proximity never influence selection`() {
        // The focused task (206) is numerically adjacent to inactive root
        // 205 and far from its actual parent 100 — hierarchy must win.
        val hierarchyWins = listOf(
            root(100, visible = true, children = listOf(task(206, pkg = "com.example.far"))),
            root(205, children = listOf(task(101, pkg = "com.example.near"))),
        )
        val found = resolveSingle(hierarchyWins, focusedTaskId = 206)
        assertEquals(206, (found as SnapTargetAssessment.Found).targetTaskId)
        assertEquals(100, found.activeDeskRootId)

        // The same structure under entirely different, extreme ID values
        // resolves identically — IDs are opaque handles.
        val extremeIds = listOf(
            root(2147000000, visible = true, children = listOf(task(1, pkg = "com.example.far"))),
            root(2, children = listOf(task(2146999999, pkg = "com.example.near"))),
        )
        val foundExtreme = resolveSingle(extremeIds, focusedTaskId = 1)
        assertEquals(1, (foundExtreme as SnapTargetAssessment.Found).targetTaskId)
        assertEquals(2147000000, foundExtreme.activeDeskRootId)
    }

    // L. Displays stay independently scoped; no display-0 assumption. Each
    // display resolves its OWN display-scoped focus — a global focus is never
    // attributed across displays.
    @Test
    fun `displays are resolved independently by their own scoped focus`() {
        val roots = listOf(
            root(310, displayId = 6, visible = true, children = listOf(task(77, pkg = "com.android.chrome"))),
            root(9040, displayId = 14, visible = true, children = listOf(task(4100, pkg = "com.google.android.youtube"))),
        )
        val results = SnapTargetResolver.resolve(
            snapshot(
                roots,
                focusedTaskId = 4100,
                focusedByDisplay = mapOf(
                    6 to DisplayFocus.Task(77),
                    14 to DisplayFocus.Task(4100),
                ),
            ),
            selfPackage,
        )

        assertEquals(setOf(6, 14), results.keys)
        val chrome = results.getValue(6) as SnapTargetAssessment.Found
        assertEquals(6, chrome.displayId)
        assertEquals(77, chrome.targetTaskId)
        val youtube = results.getValue(14) as SnapTargetAssessment.Found
        assertEquals(14, youtube.displayId)
        assertEquals(4100, youtube.targetTaskId)
    }

    // A global focus must NOT be attributed across multiple displays: with no
    // scoped focus and two active displays, both fail closed.
    @Test
    fun `global focus is not applied across multiple displays`() {
        val roots = listOf(
            root(310, displayId = 6, visible = true, children = listOf(task(77, pkg = "com.android.chrome"))),
            root(9040, displayId = 14, visible = true, children = listOf(task(4100, pkg = "com.google.android.youtube"))),
        )

        val results = SnapTargetResolver.resolve(snapshot(roots, focusedTaskId = 4100), selfPackage)

        assertTrue(results.getValue(6) is SnapTargetAssessment.NoTarget)
        assertTrue(results.getValue(14) is SnapTargetAssessment.NoTarget)
    }

    // BLOCKING 1: an empty scoped map does NOT resurrect the legacy fallback
    // when the scoped-focus grammar was present but produced no usable focus.
    @Test
    fun `empty scoped map with scoped evidence fails closed despite a valid global child`() {
        val roots = listOf(
            root(310, displayId = 0, visible = true, children = listOf(task(77, pkg = "com.android.chrome"))),
        )
        // One active display, global focus is a valid child, but the scoped
        // grammar was present (evidence) and yielded no usable focus.
        val result = SnapTargetResolver.resolve(
            snapshot(roots, focusedTaskId = 77, focusedByDisplay = emptyMap(), hasScopedEvidence = true),
            selfPackage,
        ).getValue(0)

        assertTrue(result is SnapTargetAssessment.NoTarget)
    }

    // Complement: with NO scoped grammar at all, the single-display legacy
    // global fallback still resolves.
    @Test
    fun `empty scoped map without scoped evidence uses the legacy global fallback`() {
        val roots = listOf(
            root(310, displayId = 0, visible = true, children = listOf(task(77, pkg = "com.android.chrome"))),
        )
        val result = SnapTargetResolver.resolve(
            snapshot(roots, focusedTaskId = 77, focusedByDisplay = emptyMap(), hasScopedEvidence = false),
            selfPackage,
        ).getValue(0)

        assertEquals(77, (result as SnapTargetAssessment.Found).targetTaskId)
    }

    @Test
    fun `self package is a parameter not a constant`() {
        // The same fixture flips outcome purely by the caller-supplied
        // identity, proving nothing is hard-coded in the resolver.
        val roots = listOf(
            root(310, visible = true, children = listOf(task(77, pkg = "com.example.selfapp"))),
        )
        val snapshotData = snapshot(roots, focusedTaskId = 77)

        val asSelf = SnapTargetResolver.resolve(snapshotData, "com.example.selfapp").getValue(0)
        val asOtherApp = SnapTargetResolver.resolve(snapshotData, "com.other.identity").getValue(0)

        assertTrue(asSelf is SnapTargetAssessment.NoTarget)
        assertTrue(asOtherApp is SnapTargetAssessment.Found)
    }
}
