package uk.mr_biz.fourzones.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * All fixtures are small synthetic strings mirroring the structural shape of
 * the sanitized A9 DeX captures. Task IDs are deliberately arbitrary and
 * non-sequential throughout: the parser must never attach meaning to their
 * numeric values (empirical IDs like 11705 exist on-device but are never
 * assumed anywhere).
 */
class DesktopTopologyParserTest {

    private data class ChildSpec(
        val taskId: Int,
        val pkg: String = "com.example.app",
        val rootRef: Int? = null,
        val component: String? = null,
        val visible: Boolean = false,
    )

    private fun deskRootBlock(
        id: Int,
        type: String? = "activatable",
        visible: Boolean? = false,
        visibleRequested: Boolean? = visible,
        organizer: Boolean = true,
        forceHidden: Boolean? = null,
        includeDwInHeader: Boolean = true,
        includePropertyLine: Boolean = true,
        children: List<ChildSpec> = emptyList(),
    ): String = buildString {
        val dw = if (includeDwInHeader && type != null) "dw=$type " else ""
        val visibleToken = visible?.let { "visible=$it " } ?: ""
        val requestedToken = visibleRequested?.let { "visibleRequested=$it " } ?: ""
        appendLine(
            "  * Task{h$id #$id type=undefined ${dw}U=0 $visibleToken$requestedToken" +
                "mode=freeform translucent=true sz=${children.size}}",
        )
        appendLine("    mCreatedByOrganizer=$organizer")
        if (includePropertyLine && type != null) appendLine("    mDeskRootTaskType=$type")
        if (forceHidden != null) appendLine("    isForceHidden=$forceHidden")
        children.forEach { child ->
            val rootRef = child.rootRef ?: id
            appendLine(
                "    * Task{c${child.taskId} #${child.taskId} type=standard " +
                    "A=10123:${child.pkg} U=0 rootTaskId=$rootRef visible=${child.visible} " +
                    "visibleRequested=${child.visible} mode=freeform translucent=true sz=1}",
            )
            if (child.component != null) {
                appendLine(
                    "      * Hist  #0: ActivityRecord{r${child.taskId} u0 " +
                        "${child.component} t${child.taskId}}",
                )
            }
        }
    }

    private fun homeRootBlock(rootId: Int = 900, taskId: Int = 901): String = buildString {
        appendLine(
            "  * Task{hh #$rootId type=home U=0 visible=true visibleRequested=true " +
                "mode=fullscreen translucent=false sz=1}",
        )
        appendLine(
            "    * Task{hh2 #$taskId type=home " +
                "I=com.example.launcher/.LauncherActivity U=0 rootTaskId=$rootId " +
                "visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}",
        )
        appendLine(
            "      * Hist  #0: ActivityRecord{lh u0 " +
                "com.example.launcher/.LauncherActivity t$taskId}",
        )
    }

    private fun dump(
        vararg rootBlocks: String,
        displayId: Int = 0,
        focusedComponent: String? = null,
        focusedTaskId: Int? = null,
    ): String = buildString {
        appendLine("ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)")
        appendLine("Display #$displayId (activities from top to bottom):")
        rootBlocks.forEach { append(it) }
        appendLine("ActivityTaskSupervisor state:")
        if (focusedTaskId != null) {
            val component = focusedComponent ?: "com.example.app/.Main"
            appendLine("  mFocusedApp=ActivityRecord{f u0 $component t$focusedTaskId}")
        }
    }

    private fun multiDisplayDump(vararg sections: Pair<Int, List<String>>): String = buildString {
        appendLine("ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)")
        sections.forEach { (displayId, rootBlocks) ->
            appendLine("Display #$displayId (activities from top to bottom):")
            rootBlocks.forEach { append(it) }
        }
        appendLine("ActivityTaskSupervisor state:")
    }

    private fun modelRoot(
        id: Int,
        displayId: Int? = 0,
        type: DeskRootType = DeskRootType.ACTIVATABLE,
        visible: Boolean? = null,
        visibleRequested: Boolean? = null,
    ) = DesktopRoot(
        rootTaskId = id,
        displayId = displayId,
        type = type,
        visible = visible,
        visibleRequested = visibleRequested,
        createdByOrganizer = true,
        forceHidden = null,
        windowingMode = "freeform",
        childTasks = emptyList(),
    )

    // 1. Four activatable desktop roots are parsed independently.
    @Test
    fun `four activatable desk roots parse independently`() {
        val snapshot = DesktopTopologyParser.parse(
            dump(
                deskRootBlock(7, visible = true, children = listOf(ChildSpec(3200))),
                deskRootBlock(5003, children = listOf(ChildSpec(41))),
                deskRootBlock(12, children = listOf(ChildSpec(77777))),
                deskRootBlock(88400, children = emptyList()),
                deskRootBlock(660, type = "minimized", forceHidden = true),
                deskRootBlock(59, type = "minimized", forceHidden = true),
                deskRootBlock(1000001, type = "minimized", forceHidden = true),
            ),
        )

        assertEquals(7, snapshot.roots.size)
        assertEquals(4, snapshot.activatableRoots.size)
        assertEquals(3, snapshot.minimizedRoots.size)
        // Each root keeps its own children and state; nothing is merged.
        assertEquals(
            listOf(7, 5003, 12, 88400),
            snapshot.activatableRoots.map { it.rootTaskId },
        )
        assertEquals(listOf(3200), snapshot.activatableRoots[0].childTasks.map { it.taskId })
        assertEquals(listOf(41), snapshot.activatableRoots[1].childTasks.map { it.taskId })
        assertEquals(0, snapshot.activatableRoots[3].childTasks.size)
        assertTrue(snapshot.minimizedRoots.all { it.forceHidden == true })
    }

    // 2 & 14. Root task IDs are arbitrary/non-sequential and never semantic;
    // nothing depends on empirically observed values like 11705.
    @Test
    fun `root task ids are opaque and never used semantically`() {
        fun structure(a: Int, b: Int, c: Int) = dump(
            deskRootBlock(a, visible = true),
            deskRootBlock(b),
            deskRootBlock(c, type = "minimized"),
        )

        val small = DesktopTopologyParser.parse(structure(a = 1, b = 2, c = 3))
        val weird = DesktopTopologyParser.parse(structure(a = 2147000000, b = 4, c = 999999))
        val empirical = DesktopTopologyParser.parse(structure(a = 11705, b = 11717, c = 11713))

        // Identical structure => identical semantics; only the opaque IDs differ.
        assertEquals(ActiveDesktopAssessment.Found(1), small.activeDesktopByDisplay[0])
        assertEquals(ActiveDesktopAssessment.Found(2147000000), weird.activeDesktopByDisplay[0])
        assertEquals(ActiveDesktopAssessment.Found(11705), empirical.activeDesktopByDisplay[0])
        listOf(small, weird, empirical).forEach { snapshot ->
            assertEquals(2, snapshot.activatableRoots.size)
            assertEquals(1, snapshot.minimizedRoots.size)
        }
        // Roots keep document order, not numeric order.
        assertEquals(listOf(2147000000, 4, 999999), weird.roots.map { it.rootTaskId })
    }

    // 3. Exactly one positively visible activatable root -> FOUND.
    @Test
    fun `single visible activatable root is found`() {
        val snapshot = DesktopTopologyParser.parse(
            dump(
                deskRootBlock(42, visible = true),
                deskRootBlock(9917),
                deskRootBlock(303, type = "minimized"),
            ),
        )

        assertEquals(ActiveDesktopAssessment.Found(42), snapshot.activeDesktopByDisplay[0])
    }

    // 4. No visible activatable root -> NONE.
    @Test
    fun `no visible activatable root means none`() {
        val snapshot = DesktopTopologyParser.parse(
            dump(
                deskRootBlock(42),
                deskRootBlock(9917),
                deskRootBlock(303, type = "minimized"),
            ),
        )

        assertEquals(ActiveDesktopAssessment.None, snapshot.activeDesktopByDisplay[0])
    }

    // 5. Two visible activatable roots on the SAME display -> AMBIGUOUS.
    @Test
    fun `two visible activatable roots on one display are ambiguous`() {
        val snapshot = DesktopTopologyParser.parse(
            dump(
                deskRootBlock(42, visible = true),
                deskRootBlock(9917, visible = true),
            ),
        )

        assertEquals(
            ActiveDesktopAssessment.Ambiguous(listOf(42, 9917)),
            snapshot.activeDesktopByDisplay[0],
        )
    }

    // Per-display scoping: one active desktop per display is FOUND for each,
    // never a cross-display AMBIGUOUS.
    @Test
    fun `one active root per display is found independently not ambiguous`() {
        val snapshot = DesktopTopologyParser.parse(
            multiDisplayDump(
                0 to listOf(
                    deskRootBlock(510, visible = true),
                    deskRootBlock(511),
                ),
                14 to listOf(
                    deskRootBlock(9040, visible = true),
                    deskRootBlock(9041, type = "minimized"),
                ),
            ),
        )

        assertEquals(
            mapOf(
                0 to ActiveDesktopAssessment.Found(510),
                14 to ActiveDesktopAssessment.Found(9040),
            ),
            snapshot.activeDesktopByDisplay,
        )
    }

    @Test
    fun `display ids are arbitrary keys with no ordering semantics`() {
        val snapshot = DesktopTopologyParser.parse(
            multiDisplayDump(
                999999 to listOf(deskRootBlock(11, visible = true)),
                3 to listOf(
                    deskRootBlock(21, visible = true),
                    deskRootBlock(22, visible = true),
                ),
            ),
        )

        // Each display assessed on its own facts; key values carry no meaning
        // and a "larger" display ID changes nothing.
        assertEquals(ActiveDesktopAssessment.Found(11), snapshot.activeDesktopByDisplay[999999])
        assertEquals(
            ActiveDesktopAssessment.Ambiguous(listOf(21, 22)),
            snapshot.activeDesktopByDisplay[3],
        )
        assertEquals(setOf(999999, 3), snapshot.activeDesktopByDisplay.keys)
    }

    @Test
    fun `roots without display association are excluded not reassigned`() {
        val roots = listOf(
            modelRoot(70, displayId = 0, visible = true, visibleRequested = true),
            modelRoot(80, displayId = null, visible = true, visibleRequested = true),
        )

        val byDisplay = DesktopTopologyParser.assessActiveDesktopByDisplay(roots)

        // Root 80 is not silently attributed to display 0: display 0 still
        // has exactly one candidate, and no phantom display entry exists.
        assertEquals(mapOf(0 to ActiveDesktopAssessment.Found(70)), byDisplay)
    }

    // 6. Missing mDeskRootTaskType/dw fields -> UNSUPPORTED (no display entry).
    @Test
    fun `absent desk fields mean unsupported`() {
        val snapshot = DesktopTopologyParser.parse(
            dump(
                homeRootBlock(),
                deskRootBlock(500, type = null, visible = true),
            ),
        )

        assertTrue(snapshot.roots.isEmpty())
        assertTrue(snapshot.activeDesktopByDisplay.isEmpty())
        assertTrue(snapshot.evidence.any { it.contains("Not guessing") })
    }

    // 7. Minimized roots never become active desktops — even if a hostile
    // dump claims a minimized root is visible.
    @Test
    fun `minimized roots never become active`() {
        val snapshot = DesktopTopologyParser.parse(
            dump(
                deskRootBlock(64, type = "minimized", visible = true),
                deskRootBlock(2001),
            ),
        )

        assertEquals(ActiveDesktopAssessment.None, snapshot.activeDesktopByDisplay[0])
    }

    // 8. A desktop root can contain multiple child tasks.
    @Test
    fun `desk root with multiple children parses all of them`() {
        val snapshot = DesktopTopologyParser.parse(
            dump(
                deskRootBlock(
                    777,
                    visible = true,
                    children = listOf(
                        ChildSpec(15, pkg = "com.example.photos", component = "com.example.photos/.Home"),
                        ChildSpec(90210, pkg = "com.example.chat", component = "com.example.chat/.Chat"),
                        ChildSpec(8, pkg = "com.example.store"),
                    ),
                ),
            ),
        )

        val root = snapshot.activatableRoots.single()
        assertEquals(3, root.childTasks.size)
        assertEquals(listOf(15, 90210, 8), root.childTasks.map { it.taskId })
        assertEquals("com.example.photos/.Home", root.childTasks[0].componentName)
        assertEquals("com.example.chat", root.childTasks[1].packageName)
        // No component/Hist for the third child: package hint still recovered
        // from the task header, component honestly null.
        assertEquals("com.example.store", root.childTasks[2].packageName)
        assertNull(root.childTasks[2].componentName)
    }

    // 9. Task membership comes from hierarchy, not numeric proximity.
    @Test
    fun `membership follows hierarchy not id adjacency`() {
        // Child #206 is numerically adjacent to root 205 and even printed
        // inside root 205's block, but its hierarchy back-reference says it
        // belongs to root 100. The back-reference must win; adjacency and
        // position must not.
        val snapshot = DesktopTopologyParser.parse(
            dump(
                deskRootBlock(100, visible = true),
                deskRootBlock(
                    205,
                    children = listOf(ChildSpec(206, rootRef = 100)),
                ),
            ),
        )

        val root100 = snapshot.roots.single { it.rootTaskId == 100 }
        val root205 = snapshot.roots.single { it.rootTaskId == 205 }
        assertEquals(listOf(206), root100.childTasks.map { it.taskId })
        assertTrue(root205.childTasks.isEmpty())
    }

    @Test
    fun `child without explicit back-reference attaches to enclosing root`() {
        val block = buildString {
            appendLine(
                "  * Task{x #310 type=undefined dw=activatable U=0 visible=true " +
                    "visibleRequested=true mode=freeform translucent=true sz=1}",
            )
            appendLine("    mDeskRootTaskType=activatable")
            appendLine(
                "    * Task{y #17 type=standard A=10123:com.example.app U=0 " +
                    "visible=true visibleRequested=true mode=freeform translucent=true sz=1}",
            )
        }
        val snapshot = DesktopTopologyParser.parse(dump(block))

        assertEquals(listOf(17), snapshot.roots.single().childTasks.map { it.taskId })
    }

    // 10. Launcher focus does not override the visible activatable decision.
    @Test
    fun `launcher focus does not override visible activatable root`() {
        val snapshot = DesktopTopologyParser.parse(
            dump(
                deskRootBlock(300, visible = true, children = listOf(ChildSpec(41))),
                deskRootBlock(7010),
                homeRootBlock(rootId = 900, taskId = 901),
                focusedComponent = "com.example.launcher/.LauncherActivity",
                focusedTaskId = 901,
            ),
        )

        // Focus went to the launcher, but the visible activatable root wins;
        // focus is recorded as data only.
        assertEquals(ActiveDesktopAssessment.Found(300), snapshot.activeDesktopByDisplay[0])
        assertEquals(901, snapshot.focusedTaskId)
        assertTrue(snapshot.roots.flatMap { it.childTasks }.none { it.focused == true })
        assertTrue(snapshot.evidence.any { it.contains("NOT used for active-desktop identity") })
    }

    @Test
    fun `focused desk child task is marked focused as diagnostic data`() {
        val snapshot = DesktopTopologyParser.parse(
            dump(
                deskRootBlock(300, visible = true, children = listOf(ChildSpec(41, visible = true))),
                focusedComponent = "com.example.app/.Main",
                focusedTaskId = 41,
            ),
        )

        assertEquals(true, snapshot.roots.single().childTasks.single().focused)
        // ...and the assessment is still visibility-based, not focus-based.
        assertEquals(ActiveDesktopAssessment.Found(300), snapshot.activeDesktopByDisplay[0])
    }

    // 11. Unknown/new desk-root type fails conservatively.
    @Test
    fun `unknown desk root type is conservative`() {
        val onlyUnknown = DesktopTopologyParser.parse(
            dump(deskRootBlock(88, type = "floating", visible = true)),
        )
        val unknownBesideKnown = DesktopTopologyParser.parse(
            dump(
                deskRootBlock(88, type = "floating", visible = true),
                deskRootBlock(4400, visible = true),
            ),
        )

        // Only unrecognized values: field semantics are unproven, do not guess.
        assertEquals(
            ActiveDesktopAssessment.Unsupported,
            onlyUnknown.activeDesktopByDisplay[0],
        )
        assertEquals(DeskRootType.UNKNOWN, onlyUnknown.roots.single().type)
        // A visible unknown-typed root never becomes an active-desktop candidate.
        assertEquals(
            ActiveDesktopAssessment.Found(4400),
            unknownBesideKnown.activeDesktopByDisplay[0],
        )
    }

    // 12. Malformed/truncated input does not crash the parser.
    @Test
    fun `malformed input never crashes`() {
        val truncated = dump(deskRootBlock(9, visible = true)).let { it.take(it.length / 2) }
        val inputs = listOf(
            "",
            "garbage\nmore garbage\n ",
            "  * Task{",
            "  * Task{x #notanumber type=undefined dw=activatable}",
            "  * Task{x #99999999999999999999 type=undefined dw=activatable U=0}",
            "Display #0 (activities from top to bottom):",
            truncated,
            "  mDeskRootTaskType=activatable\n  * Hist #0: ActivityRecord{",
        )

        inputs.forEach { input ->
            val snapshot = DesktopTopologyParser.parse(input)
            // Whatever survived parsing, the result is a valid snapshot.
            assertTrue(snapshot.evidence.isNotEmpty())
        }
    }

    // 13. Display IDs remain data only.
    @Test
    fun `display ids are recorded but never interpreted`() {
        val onDisplay0 = DesktopTopologyParser.parse(
            dump(deskRootBlock(21, visible = true), displayId = 0),
        )
        val onDisplay5 = DesktopTopologyParser.parse(
            dump(deskRootBlock(21, visible = true), displayId = 5),
        )

        assertEquals(0, onDisplay0.roots.single().displayId)
        assertEquals(5, onDisplay5.roots.single().displayId)
        // Same facts => same verdict; only the scope key differs.
        assertEquals(
            onDisplay0.activeDesktopByDisplay.values.single(),
            onDisplay5.activeDesktopByDisplay.values.single(),
        )
        assertEquals(setOf(5), onDisplay5.activeDesktopByDisplay.keys)
    }

    @Test
    fun `task lines repeated in later dump sections are not re-parsed`() {
        val text = buildString {
            appendLine("ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)")
            appendLine("Display #0 (activities from top to bottom):")
            append(deskRootBlock(61, visible = true))
            appendLine("ActivityTaskSupervisor state:")
            // Echoes of task lines outside the hierarchy region, as seen in
            // real dumps (organizer/launch-params sections).
            appendLine(
                "      * Task{h61 #61 type=undefined dw=activatable U=0 visible=true " +
                    "visibleRequested=true mode=freeform translucent=true sz=1}",
            )
            appendLine(
                "      * Task{zz #4141 type=undefined dw=activatable U=0 visible=true " +
                    "visibleRequested=true mode=freeform translucent=true sz=0}",
            )
        }
        val snapshot = DesktopTopologyParser.parse(text)

        // Only the root from the hierarchy region exists; no duplicates, no
        // phantom root 4141, and the assessment stays FOUND (not AMBIGUOUS).
        assertEquals(listOf(61), snapshot.roots.map { it.rootTaskId })
        assertEquals(ActiveDesktopAssessment.Found(61), snapshot.activeDesktopByDisplay[0])
    }

    // Finding 4: every visible/visibleRequested combination.
    @Test
    fun `visibility candidacy truth table is conservative`() {
        fun assess(visible: Boolean?, requested: Boolean?): ActiveDesktopAssessment =
            DesktopTopologyParser.assessActiveDesktop(
                listOf(modelRoot(31, visible = visible, visibleRequested = requested)),
            )

        // Positive: agreement, or one side unavailable.
        assertEquals(ActiveDesktopAssessment.Found(31), assess(visible = true, requested = true))
        assertEquals(ActiveDesktopAssessment.Found(31), assess(visible = true, requested = null))
        assertEquals(ActiveDesktopAssessment.Found(31), assess(visible = null, requested = true))
        // Contradictory transitional states are never positively active.
        assertEquals(ActiveDesktopAssessment.None, assess(visible = true, requested = false))
        assertEquals(ActiveDesktopAssessment.None, assess(visible = false, requested = true))
        // Plainly not visible.
        assertEquals(ActiveDesktopAssessment.None, assess(visible = false, requested = false))
        assertEquals(ActiveDesktopAssessment.None, assess(visible = null, requested = null))
        assertEquals(ActiveDesktopAssessment.None, assess(visible = false, requested = null))
        assertEquals(ActiveDesktopAssessment.None, assess(visible = null, requested = false))
    }

    @Test
    fun `contradictory visibility yields temporary none not a false found`() {
        // A desktop-switch animation frame: the outgoing root still reports
        // visible=true but visibleRequested=false, the incoming one the
        // reverse. A temporary NONE is acceptable; a false FOUND is not.
        val snapshot = DesktopTopologyParser.parse(
            dump(
                deskRootBlock(614, visible = true, visibleRequested = false),
                deskRootBlock(88012, visible = false, visibleRequested = true),
            ),
        )

        assertEquals(ActiveDesktopAssessment.None, snapshot.activeDesktopByDisplay[0])
        assertTrue(snapshot.evidence.any { it.contains("contradictory visibility") })
    }

    @Test
    fun `contradictory root does not disturb a genuinely visible root`() {
        val snapshot = DesktopTopologyParser.parse(
            dump(
                deskRootBlock(614, visible = true, visibleRequested = false),
                deskRootBlock(2200, visible = true, visibleRequested = true),
            ),
        )

        // The consistent root is FOUND; the transitional one neither joins
        // the candidate set (no AMBIGUOUS) nor blocks the result.
        assertEquals(ActiveDesktopAssessment.Found(2200), snapshot.activeDesktopByDisplay[0])
    }

    @Test
    fun `visibleRequested with visible unavailable counts as positively active`() {
        val snapshot = DesktopTopologyParser.parse(
            dump(deskRootBlock(12, visible = null, visibleRequested = true)),
        )

        assertEquals(ActiveDesktopAssessment.Found(12), snapshot.activeDesktopByDisplay[0])
    }

    @Test
    fun `organizer and force hidden flags are captured when present and null when absent`() {
        val snapshot = DesktopTopologyParser.parse(
            dump(
                deskRootBlock(31, type = "minimized", forceHidden = true),
                deskRootBlock(97, visible = true),
            ),
        )

        val minimized = snapshot.minimizedRoots.single()
        val activatable = snapshot.activatableRoots.single()
        assertEquals(true, minimized.forceHidden)
        assertEquals(true, minimized.createdByOrganizer)
        assertNull(activatable.forceHidden)
        assertNotEquals(false, activatable.forceHidden) // absent stays null, never false
    }

    @Test
    fun `task header line with escaped literal closing brace still parses`() {
        // Android's ICU regex implementation rejects a bare literal `}`
        // (PatternSyntaxException from the parser's static initializer on
        // device — observed on the A9, Android 16/API 36), which OpenJDK
        // tolerated, so the Task-line expression escapes it explicitly as
        // \}. This JVM test cannot emulate ICU and does NOT prove Android
        // compatibility; it pins the exact corrected source expression and
        // its parsing semantics against regression.
        val snapshot = DesktopTopologyParser.parse(
            """
            Display #0 (activities from top to bottom):
              * Task{abc123 #5003 type=standard A=com.example}
                mDeskRootTaskType=activatable
            """.trimIndent(),
        )

        val root = snapshot.roots.single()
        assertEquals(5003, root.rootTaskId)
        assertEquals(DeskRootType.ACTIVATABLE, root.type)
        // Header attributes inside the braces are still recovered, and
        // absent ones stay honestly null.
        assertNull(root.windowingMode)
        assertNull(root.visible)
        assertNull(root.visibleRequested)
        assertEquals(ActiveDesktopAssessment.None, snapshot.activeDesktopByDisplay[0])
    }

    @Test
    fun `desk type from property line wins but header dw token is the fallback`() {
        val headerOnly = DesktopTopologyParser.parse(
            dump(deskRootBlock(55, includePropertyLine = false, visible = true)),
        )
        val propertyOnly = DesktopTopologyParser.parse(
            dump(deskRootBlock(56, includeDwInHeader = false, visible = true)),
        )

        assertEquals(DeskRootType.ACTIVATABLE, headerOnly.roots.single().type)
        assertEquals(DeskRootType.ACTIVATABLE, propertyOnly.roots.single().type)
        assertFalse(
            headerOnly.activeDesktopByDisplay[0] is ActiveDesktopAssessment.Unsupported,
        )
    }
}
