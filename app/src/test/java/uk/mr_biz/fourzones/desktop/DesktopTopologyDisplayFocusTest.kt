package uk.mr_biz.fourzones.desktop

import uk.mr_biz.fourzones.privileged.TopologyDumpFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Display-scoped focus: on S25 external DeX the WindowManager
 * `Display: mDisplayId=N` blocks carry a per-display `mFocusedApp`, so target
 * identification must use focus scoped to each already-resolved display rather
 * than one global focus. All IDs are SYNTHETIC opaque values. Fixtures run
 * through the production filter then parser (integrated).
 */
class DesktopTopologyDisplayFocusTest {

    private val self = "uk.mr_biz.fourzones"
    private val phoneDisplay = 0
    private val extDisplay = 12

    /**
     * @param extFocusTaskId external display's mFocusedApp task, or null to omit.
     * @param secondExtFocus when set, a SECOND differing external mFocusedApp
     * (to exercise conflict).
     * @param reverseChildren emit the active desk children in reverse order.
     * @param withTopResumed add simultaneous topResumedActivity lines to prove
     * they are not the focus signal.
     * @param phoneActiveDesk when true, the phone display also hosts an active
     * organizer Desk (a genuinely two-active-display topology).
     */
    private fun dump(
        extFocusTaskId: Int? = 801,
        secondExtFocus: Int? = null,
        phoneFocusTaskId: Int? = 700,
        reverseChildren: Boolean = false,
        withTopResumed: Boolean = false,
        phoneActiveDesk: Boolean = false,
        phoneDeskFocus: Int? = 610,
        // opaque ids
        deskA: Int = 800, chatgpt: Int = 801, dexzones: Int = 802, shizuku: Int = 803,
        deskB: Int = 810, appA: Int = 811,
        minA: Int = 820, minB: Int = 830,
        phoneTask: Int = 700,
        phoneDesk: Int = 600, phoneChild: Int = 610,
        extId: Int = 12,
    ): String {
        // Built line-by-line with EXACT indentation (root headers at 2 columns,
        // properties/children at 4, child properties at 6) to match the dumpsys
        // grammar the parser is indentation-sensitive about.
        val lines = mutableListOf<String>()

        // Phone display section.
        lines += "Display #$phoneDisplay (activities from top to bottom):"
        if (phoneActiveDesk) {
            lines += "  * Task{pd #$phoneDesk type=undefined name=Desk U=0 visible=true visibleRequested=true mode=freeform translucent=true sz=1}"
            lines += "    mCreatedByOrganizer=true"
            lines += "    * Task{pc #$phoneChild type=standard A=10700:com.example.phonedesk U=0 rootTaskId=$phoneDesk visible=true visibleRequested=true mode=freeform translucent=true sz=1}"
        } else {
            lines += "  * Task{ph #$phoneTask type=standard A=10212:com.android.chrome U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}"
        }

        // External display section.
        lines += "Display #$extId (activities from top to bottom):"
        lines += "  * Task{da #$deskA type=undefined name=Desk U=0 visible=true visibleRequested=true mode=freeform translucent=true sz=3}"
        lines += "    mCreatedByOrganizer=true"
        val activeChildren = buildList {
            add(Triple("c1", chatgpt, "com.openai.chatgpt"))
            add(Triple("c2", dexzones, "uk.mr_biz.fourzones"))
            add(Triple("c3", shizuku, "moe.shizuku.privileged.api"))
        }.let { if (reverseChildren) it.reversed() else it }
        for ((tag, id, pkg) in activeChildren) {
            lines += "    * Task{$tag #$id type=standard A=10000:$pkg U=0 rootTaskId=$deskA visible=true visibleRequested=true mode=freeform translucent=true sz=1}"
            if (withTopResumed) lines += "      topResumedActivity=ActivityRecord{r u0 $pkg/.Main t$id}"
        }
        lines += "  * Task{db #$deskB type=undefined name=Desk U=0 visible=false visibleRequested=false mode=freeform translucent=true sz=1}"
        lines += "    mCreatedByOrganizer=true"
        lines += "    * Task{c4 #$appA type=standard A=10500:com.example.appa U=0 rootTaskId=$deskB visible=false visibleRequested=false mode=freeform translucent=true sz=1}"
        lines += "  * Task{ma #$minA type=undefined name=MinimizedDesk_aa U=0 visible=false visibleRequested=false mode=freeform translucent=true sz=0}"
        lines += "    mCreatedByOrganizer=true"
        lines += "    isForceHidden=true"
        lines += "  * Task{mb #$minB type=undefined name=MinimizedDesk_bb U=0 visible=false visibleRequested=false mode=freeform translucent=true sz=0}"
        lines += "    mCreatedByOrganizer=true"
        lines += "    isForceHidden=true"

        // ActivityTaskSupervisor state with per-display WindowManager focus.
        lines += "ActivityTaskSupervisor state:"
        lines += "  Display: mDisplayId=$extId (organized)"
        lines += "    mCurrentFocus=null"
        extFocusTaskId?.let {
            lines += "    mFocusedApp=ActivityRecord{ext u0 com.openai.chatgpt/.MainActivity t$it}"
        }
        secondExtFocus?.let {
            lines += "    mFocusedApp=ActivityRecord{ext2 u0 com.example.other/.Main t$it}"
        }
        lines += "  Display: mDisplayId=$phoneDisplay (organized)"
        lines += "    mCurrentFocus=Window{w u0 com.android.chrome/x}"
        val phoneFocus = if (phoneActiveDesk) phoneDeskFocus else phoneFocusTaskId
        val phonePkg = if (phoneActiveDesk) "com.example.phonedesk" else "com.android.chrome"
        phoneFocus?.let {
            lines += "    mFocusedApp=ActivityRecord{pf u0 $phonePkg/.Main t$it}"
        }
        return lines.joinToString("\n")
    }

    private fun parse(dumpText: String): DesktopTopologySnapshot =
        DesktopTopologyParser.parse(TopologyDumpFilter.filter(dumpText))

    private fun target(dumpText: String, displayId: Int = 12): SnapTargetAssessment =
        SnapTargetResolver.resolve(parse(dumpText), self).getValue(displayId)

    // 1 & active-desktop identity unchanged.
    @Test
    fun `external active desktop identity is the visible Desk regardless of focus`() {
        val snapshot = parse(dump())
        assertEquals(ActiveDesktopAssessment.Found(800), snapshot.activeDesktopByDisplay[extDisplay])
        // Phone display (fullscreen Chrome) has no desk topology.
        assertEquals(setOf(extDisplay), snapshot.activeDesktopByDisplay.keys)
    }

    // 1 & 2. external display-scoped mFocusedApp resolves the active child.
    @Test
    fun `external scoped focus resolves the active child`() {
        val snapshot = parse(dump())
        assertEquals(DisplayFocus.Task(801), snapshot.focusedTaskByDisplay[extDisplay])
        val found = target(dump()) as SnapTargetAssessment.Found
        assertEquals(801, found.targetTaskId)
        assertEquals(800, found.activeDeskRootId)
        assertEquals("com.openai.chatgpt", found.packageName)
    }

    // 2. phone global focus must not override external scoped focus.
    @Test
    fun `phone global focus does not override external focus`() {
        val snapshot = parse(dump())
        // Global (last-seen) focus is the phone task, but the external display
        // still resolves its own scoped ChatGPT.
        assertEquals(700, snapshot.focusedTaskId)
        assertEquals(DisplayFocus.Task(700), snapshot.focusedTaskByDisplay[phoneDisplay])
        assertTrue(target(dump()) is SnapTargetAssessment.Found)
    }

    // 3. external focus self => NoTarget.
    @Test
    fun `external focus on DexZones host is no target`() {
        val result = target(dump(extFocusTaskId = 802)) as SnapTargetAssessment.NoTarget
        assertTrue(result.reason.contains("host app itself"))
    }

    // 4. external focus inactive-desktop child => NoTarget.
    @Test
    fun `external focus on an inactive Desk child is no target`() {
        val result = target(dump(extFocusTaskId = 811)) as SnapTargetAssessment.NoTarget
        assertTrue(result.reason.contains("810"))
    }

    // 5. external focus launcher / task outside hierarchy => NoTarget.
    @Test
    fun `external focus outside the desk hierarchy is no target`() {
        val result = target(dump(extFocusTaskId = 99999)) as SnapTargetAssessment.NoTarget
        assertTrue(result.reason.contains("outside the desk-root hierarchy"))
    }

    // 6. external focus unknown task => NoTarget (same as above with a stray id).
    @Test
    fun `external focus on an unknown task is no target`() {
        assertTrue(target(dump(extFocusTaskId = 12345)) is SnapTargetAssessment.NoTarget)
    }

    // 7. missing external focus while the scoped-focus grammar is present
    // (WM display headers exist) => fail closed, never legacy global.
    @Test
    fun `missing external scoped focus fails closed`() {
        val snapshot = parse(dump(extFocusTaskId = null))
        assertTrue(snapshot.hasDisplayScopedFocusEvidence)
        val result = target(dump(extFocusTaskId = null)) as SnapTargetAssessment.NoTarget
        assertTrue(result.reason.contains("no usable scoped focus"))
    }

    // BLOCKING 1 (integrated): the external display's WM block has a null
    // scoped focus, while the legacy GLOBAL focus (from the phone display's
    // block, seen last) names a valid external child. The external display
    // MUST fail closed — scoped grammar present but no usable external focus.
    private fun scopedNullExternalDump(externalFocusLine: String): String = buildString {
        appendLine("Display #12 (activities from top to bottom):")
        appendLine("  * Task{da #800 type=undefined name=Desk U=0 visible=true visibleRequested=true mode=freeform translucent=true sz=1}")
        appendLine("    mCreatedByOrganizer=true")
        appendLine("    * Task{c1 #801 type=standard A=10000:com.openai.chatgpt U=0 rootTaskId=800 visible=true visibleRequested=true mode=freeform translucent=true sz=1}")
        appendLine("Display #0 (activities from top to bottom):")
        appendLine("  * Task{ph #700 type=standard A=10212:com.android.chrome U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}")
        appendLine("ActivityTaskSupervisor state:")
        appendLine("  Display: mDisplayId=12 (organized)")
        appendLine("    $externalFocusLine")
        // Phone block, seen LAST, names the external child 801 (the legacy
        // global would otherwise wrongly point inside the external desktop).
        appendLine("  Display: mDisplayId=0 (organized)")
        appendLine("    mFocusedApp=ActivityRecord{g u0 com.openai.chatgpt/.Main t801}")
    }

    @Test
    fun `null external scoped focus fails closed despite a plausible global child`() {
        val snapshot = parse(scopedNullExternalDump("mFocusedApp=null"))

        assertTrue(snapshot.hasDisplayScopedFocusEvidence)
        assertEquals(null, snapshot.focusedTaskByDisplay[extDisplay]) // no usable external entry
        assertEquals(801, snapshot.focusedTaskId) // global names the active child
        assertTrue(
            SnapTargetResolver.resolve(snapshot, self).getValue(extDisplay) is SnapTargetAssessment.NoTarget,
        )
    }

    @Test
    fun `malformed external scoped focus fails closed despite a plausible global child`() {
        val snapshot = parse(scopedNullExternalDump("mFocusedApp=ActivityRecord{garbled-no-task}"))

        assertTrue(snapshot.hasDisplayScopedFocusEvidence)
        assertEquals(null, snapshot.focusedTaskByDisplay[extDisplay])
        assertTrue(
            SnapTargetResolver.resolve(snapshot, self).getValue(extDisplay) is SnapTargetAssessment.NoTarget,
        )
    }

    // 8. conflicting external focus => no target.
    @Test
    fun `conflicting external scoped focus fails closed`() {
        val snapshot = parse(dump(extFocusTaskId = 801, secondExtFocus = 803))
        assertEquals(DisplayFocus.Conflicting, snapshot.focusedTaskByDisplay[extDisplay])
        val result = SnapTargetResolver.resolve(snapshot, self).getValue(extDisplay)
        assertTrue((result as SnapTargetAssessment.NoTarget).reason.contains("conflicting"))
    }

    // 9. mCurrentFocus=null with a valid mFocusedApp still resolves.
    @Test
    fun `null mCurrentFocus does not prevent resolution`() {
        // The fixture always emits mCurrentFocus=null; a Found here proves it.
        assertTrue(target(dump()) is SnapTargetAssessment.Found)
    }

    // 10. simultaneous topResumedActivity children do not affect the target.
    @Test
    fun `simultaneous topResumedActivity does not drive the target`() {
        val withResumed = target(dump(withTopResumed = true)) as SnapTargetAssessment.Found
        assertEquals(801, withResumed.targetTaskId)
    }

    // 11. reversed child/root ordering does not affect focus choice.
    @Test
    fun `reversed child ordering does not change the resolved target`() {
        val normal = target(dump()) as SnapTargetAssessment.Found
        val reversed = target(dump(reverseChildren = true)) as SnapTargetAssessment.Found
        assertEquals(normal.targetTaskId, reversed.targetTaskId)
        assertEquals(801, reversed.targetTaskId)
    }

    // 12. permuting display/task ids leaves semantics unchanged.
    @Test
    fun `permuted ids leave the resolution unchanged`() {
        val permuted = dump(
            extFocusTaskId = 4242,
            deskA = 91, chatgpt = 4242, dexzones = 7, shizuku = 100000,
            deskB = 3, appA = 55, minA = 2, minB = 900001,
            phoneTask = 60000, extId = 9,
        )
        val snapshot = parse(permuted)
        assertEquals(ActiveDesktopAssessment.Found(91), snapshot.activeDesktopByDisplay[9])
        val found = SnapTargetResolver.resolve(snapshot, self).getValue(9) as SnapTargetAssessment.Found
        assertEquals(4242, found.targetTaskId)
        assertEquals("com.openai.chatgpt", found.packageName)
    }

    // 15. two genuinely active displays each resolve their OWN scoped focus;
    // removing one display's scoped focus fails that display closed only.
    @Test
    fun `two active displays resolve independently by scoped focus`() {
        val both = parse(dump(phoneActiveDesk = true, phoneDeskFocus = 610, extFocusTaskId = 801))
        assertEquals(ActiveDesktopAssessment.Found(600), both.activeDesktopByDisplay[phoneDisplay])
        assertEquals(ActiveDesktopAssessment.Found(800), both.activeDesktopByDisplay[extDisplay])

        val results = SnapTargetResolver.resolve(both, self)
        assertEquals(610, (results.getValue(phoneDisplay) as SnapTargetAssessment.Found).targetTaskId)
        assertEquals(801, (results.getValue(extDisplay) as SnapTargetAssessment.Found).targetTaskId)

        // Remove the external scoped focus: external fails closed, phone still resolves.
        val extMissing = parse(dump(phoneActiveDesk = true, phoneDeskFocus = 610, extFocusTaskId = null))
        val partial = SnapTargetResolver.resolve(extMissing, self)
        assertTrue(partial.getValue(extDisplay) is SnapTargetAssessment.NoTarget)
        assertTrue(partial.getValue(phoneDisplay) is SnapTargetAssessment.Found)
    }

    // 13. A9-style legacy single-display dump (no Display: mDisplayId block).
    @Test
    fun `legacy single-display dump without WM display blocks still resolves`() {
        val legacy = """
            Display #0 (activities from top to bottom):
              * Task{a #310 type=undefined dw=activatable U=0 visible=true visibleRequested=true mode=freeform translucent=true sz=1}
                mDeskRootTaskType=activatable
                * Task{b #77 type=standard A=10212:com.android.chrome U=0 rootTaskId=310 visible=true visibleRequested=true mode=freeform translucent=true sz=1}
            ActivityTaskSupervisor state:
              mFocusedApp=ActivityRecord{f u0 com.android.chrome/.Main t77}
        """.trimIndent()

        val snapshot = parse(legacy)
        assertTrue(snapshot.focusedTaskByDisplay.isEmpty()) // no WM display blocks
        val found = SnapTargetResolver.resolve(snapshot, self).getValue(0) as SnapTargetAssessment.Found
        assertEquals(77, found.targetTaskId)
    }

    // The filter retains the WM display marker but drops mCurrentFocus.
    @Test
    fun `filter retains display marker and drops mCurrentFocus`() {
        val filtered = TopologyDumpFilter.filter(dump())
        assertTrue(filtered.contains("Display: mDisplayId=12"))
        assertTrue(filtered.contains("Display: mDisplayId=0"))
        assertTrue(filtered.contains("mFocusedApp="))
        assertTrue(!filtered.contains("mCurrentFocus"))
    }

    // Finding 2 / 7: a RAW top-level boundary that the filter otherwise drops
    // must not let a later mFocusedApp leak into the previous WM display scope.
    private fun leakDump(): String = buildString {
        appendLine("Display #6 (activities from top to bottom):")
        appendLine("  * Task{da #600 type=undefined name=Desk U=0 visible=true visibleRequested=true mode=freeform translucent=true sz=1}")
        appendLine("    mCreatedByOrganizer=true")
        appendLine("    * Task{c1 #601 type=standard A=10000:com.openai.chatgpt U=0 rootTaskId=600 visible=true visibleRequested=true mode=freeform translucent=true sz=1}")
        appendLine("ActivityTaskSupervisor state:")
        appendLine("  Display: mDisplayId=6 (organized)")
        appendLine("    mFocusedApp=ActivityRecord{ext u0 com.openai.chatgpt/.MainActivity t601}")
        // A raw top-level heading the filter does NOT otherwise retain.
        appendLine("WINDOW MANAGER WINDOWS (dumpsys window windows)")
        appendLine("  mFocusedApp=ActivityRecord{stray u0 com.example.other/.Main t999}")
    }

    @Test
    fun `a raw dropped boundary prevents focus scope leaking into the previous display`() {
        val snapshot = parse(leakDump())
        // t601 belongs to display 6; the stray t999 (after the raw boundary)
        // is NOT attributed to display 6.
        assertEquals(DisplayFocus.Task(601), snapshot.focusedTaskByDisplay[6])
        // The stray line participates only as the (unscoped) legacy global.
        assertEquals(999, snapshot.focusedTaskId)
    }

    // Finding 8: inspect the actual filtered text — the stray mFocusedApp must
    // not sit directly under display 6 with no intervening reset boundary.
    @Test
    fun `filtered output does not place the stray focus under the display header`() {
        val filtered = TopologyDumpFilter.filter(leakDump()).lines().filter { it.isNotBlank() }
        val display6Index = filtered.indexOfFirst { it.contains("Display: mDisplayId=6") }
        val strayIndex = filtered.indexOfFirst { it.contains("t999") }
        val boundaryBetween = (display6Index + 1 until strayIndex).any {
            filtered[it].trimStart().startsWith("ActivityTaskSupervisor state:") ||
                filtered[it].startsWith("Display")
        }
        // There is a scope-resetting boundary between the display header and
        // the stray focus, so the parser cannot mis-scope it.
        assertTrue(strayIndex > display6Index)
        assertTrue(boundaryBetween)
    }

    // ----------------------------------------- WM header grammar anchoring

    private val malformedHeaders = listOf(
        "Display: mDisplayId=6garbage",
        "Display: mDisplayId=6 (organized) garbage",
        "xxx Display: mDisplayId=6 (organized)",
        "Display: mDisplayId=",
        "Display: mDisplayId=x (organized)",
        "Display: mDisplayId=6organized",
        "Display:mDisplayId=6 (organized)",
    )

    private val validHeaders = listOf(
        "Display: mDisplayId=6 (organized)",
        "Display: mDisplayId=6",
    )

    /** Minimal dump: one active external Desk (child 801) + a single WM header. */
    private fun headerGrammarDump(header: String): String = buildString {
        appendLine("Display #6 (activities from top to bottom):")
        appendLine("  * Task{da #800 type=undefined name=Desk U=0 visible=true visibleRequested=true mode=freeform translucent=true sz=1}")
        appendLine("    mCreatedByOrganizer=true")
        appendLine("    * Task{c1 #801 type=standard A=10000:com.openai.chatgpt U=0 rootTaskId=800 visible=true visibleRequested=true mode=freeform translucent=true sz=1}")
        appendLine("ActivityTaskSupervisor state:")
        appendLine("  $header")
        appendLine("    mFocusedApp=ActivityRecord{x u0 com.openai.chatgpt/.MainActivity t801}")
    }

    // 3 & 5: malformed headers set no scoped evidence, no scope, and never
    // manufacture a scoped Found — even though a plausible child (801) sits in
    // the sole active desktop and the global focus names it.
    @Test
    fun `malformed WM headers establish no scoped evidence or scope`() {
        for (header in malformedHeaders) {
            val snapshot = DesktopTopologyParser.parse(headerGrammarDump(header))
            assertTrue("evidence set for '$header'", !snapshot.hasDisplayScopedFocusEvidence)
            assertTrue("scoped entry created for '$header'", snapshot.focusedTaskByDisplay.isEmpty())
        }
    }

    @Test
    fun `malformed WM header never manufactures a scoped Found via focusedTaskByDisplay`() {
        // Integrated raw -> filter -> parser -> resolver. Active display is 6;
        // a bug would scope 801 to display 6 and report a SCOPED Found.
        val snapshot = parse(headerGrammarDump("Display: mDisplayId=6garbage"))
        assertTrue(!snapshot.hasDisplayScopedFocusEvidence)
        assertTrue(snapshot.focusedTaskByDisplay.isEmpty()) // NOT scoped to display 6
        // The only Found path here is the legacy single-display fallback
        // (no scoped grammar), which is the established contract — never scoped.
        val result = SnapTargetResolver.resolve(snapshot, self).getValue(6)
        assertTrue(result is SnapTargetAssessment.Found)
    }

    @Test
    fun `filter does not retain malformed WM headers`() {
        for (header in malformedHeaders) {
            val filtered = TopologyDumpFilter.filter(headerGrammarDump(header))
            assertTrue("filter retained malformed '$header'", !filtered.contains(header))
        }
    }

    // 4 & 6: valid headers still establish scoped evidence and populate focus.
    @Test
    fun `valid WM headers establish scoped focus`() {
        for (header in validHeaders) {
            val snapshot = DesktopTopologyParser.parse(headerGrammarDump(header))
            assertTrue("evidence for '$header'", snapshot.hasDisplayScopedFocusEvidence)
            assertEquals("scope for '$header'", DisplayFocus.Task(801), snapshot.focusedTaskByDisplay[6])
        }
    }

    // Parity: the filter and parser accept/reject exactly the same forms.
    @Test
    fun `filter and parser WM header grammars agree`() {
        for (header in malformedHeaders + validHeaders) {
            val dumpText = headerGrammarDump(header)
            val filterRetains = TopologyDumpFilter.filter(dumpText).contains(header)
            val parserAccepts = DesktopTopologyParser.parse(dumpText).hasDisplayScopedFocusEvidence
            assertEquals("grammar disagreement on '$header'", filterRetains, parserAccepts)
        }
    }

    // A valid header with a null focus beneath it still blocks the legacy
    // fallback (evidence present) — anchoring did not regress this.
    @Test
    fun `valid header with null focus keeps evidence and fails closed`() {
        val dumpText = buildString {
            appendLine("Display #6 (activities from top to bottom):")
            appendLine("  * Task{da #800 type=undefined name=Desk U=0 visible=true visibleRequested=true mode=freeform translucent=true sz=1}")
            appendLine("    mCreatedByOrganizer=true")
            appendLine("    * Task{c1 #801 type=standard A=10000:com.openai.chatgpt U=0 rootTaskId=800 visible=true visibleRequested=true mode=freeform translucent=true sz=1}")
            appendLine("ActivityTaskSupervisor state:")
            appendLine("  Display: mDisplayId=6 (organized)")
            appendLine("    mFocusedApp=null")
        }
        val snapshot = parse(dumpText)
        assertTrue(snapshot.hasDisplayScopedFocusEvidence)
        assertTrue(SnapTargetResolver.resolve(snapshot, self).getValue(6) is SnapTargetAssessment.NoTarget)
    }

    // -------------------------------- malformed WM sibling scope termination

    /** valid display-6 (focus t600), then a malformed sibling, then focus t801. */
    private fun malformedSiblingDump(sibling: String): String = buildString {
        appendLine("Display #6 (activities from top to bottom):")
        appendLine("  * Task{da #800 type=undefined name=Desk U=0 visible=true visibleRequested=true mode=freeform translucent=true sz=2}")
        appendLine("    mCreatedByOrganizer=true")
        appendLine("    * Task{c0 #600 type=standard A=10000:com.example.six U=0 rootTaskId=800 visible=true visibleRequested=true mode=freeform translucent=true sz=1}")
        appendLine("    * Task{c1 #801 type=standard A=10001:com.openai.chatgpt U=0 rootTaskId=800 visible=true visibleRequested=true mode=freeform translucent=true sz=1}")
        appendLine("ActivityTaskSupervisor state:")
        appendLine("  Display: mDisplayId=6 (organized)")
        appendLine("    mFocusedApp=ActivityRecord{a u0 com.example.six/.Main t600}")
        appendLine("  $sibling")
        appendLine("    mFocusedApp=ActivityRecord{b u0 com.openai.chatgpt/.Main t801}")
    }

    // 6 (integrated): a malformed sibling cannot extend the previous scope.
    @Test
    fun `malformed WM sibling does not extend the previous display scope`() {
        val snapshot = parse(malformedSiblingDump("Display: mDisplayId=7garbage"))

        assertEquals(DisplayFocus.Task(600), snapshot.focusedTaskByDisplay[6])
        assertEquals(null, snapshot.focusedTaskByDisplay[7])
        // t801 was NOT attributed to display 6 (would have made it Conflicting).
        assertTrue(snapshot.focusedTaskByDisplay[6] !is DisplayFocus.Conflicting)
        assertTrue(snapshot.hasDisplayScopedFocusEvidence) // from the valid display 6
        // Resolver: display 6's scoped focus is t600 (com.example.six), not t801.
        val found = SnapTargetResolver.resolve(snapshot, self).getValue(6) as SnapTargetAssessment.Found
        assertEquals(600, found.targetTaskId)
        assertEquals("com.example.six", found.packageName)
    }

    // 7 (parser-only): the parser is safe even fed the raw sequence directly.
    @Test
    fun `parser terminates scope on a malformed sibling without the filter`() {
        val raw = buildString {
            appendLine("Display #6 (activities from top to bottom):")
            appendLine("  * Task{da #800 type=undefined name=Desk U=0 visible=true visibleRequested=true mode=freeform translucent=true sz=2}")
            appendLine("    mCreatedByOrganizer=true")
            appendLine("    * Task{c0 #600 type=standard A=10000:com.example.six U=0 rootTaskId=800 visible=true visibleRequested=true mode=freeform translucent=true sz=1}")
            appendLine("    * Task{c1 #801 type=standard A=10001:com.openai.chatgpt U=0 rootTaskId=800 visible=true visibleRequested=true mode=freeform translucent=true sz=1}")
            appendLine("ActivityTaskSupervisor state:")
            appendLine("  Display: mDisplayId=6 (organized)")
            appendLine("    mFocusedApp=ActivityRecord{a u0 com.example.six/.Main t600}")
            appendLine("  Display: mDisplayId=7garbage")
            appendLine("    mFocusedApp=ActivityRecord{b u0 com.openai.chatgpt/.Main t801}")
        }
        // Parse the RAW dump directly (no filter).
        val snapshot = DesktopTopologyParser.parse(raw)

        assertEquals(DisplayFocus.Task(600), snapshot.focusedTaskByDisplay[6])
        assertEquals(null, snapshot.focusedTaskByDisplay[7])
        assertEquals(801, snapshot.focusedTaskId) // t801 is only the unscoped global
    }

    // 8 (filtered output): a parser-visible scope break sits between the valid
    // display-6 block and the later t801 focus.
    @Test
    fun `filtered output breaks scope between display 6 and the later focus`() {
        val filtered = TopologyDumpFilter.filter(malformedSiblingDump("Display: mDisplayId=7garbage"))
            .lines().filter { it.isNotBlank() }
        val display6 = filtered.indexOfFirst { it.contains("Display: mDisplayId=6") }
        val t801 = filtered.indexOfFirst { it.contains("t801") }
        assertTrue(t801 > display6)
        // The malformed sibling is not retained.
        assertTrue(filtered.none { it.contains("7garbage") })
        // A scope-resetting boundary exists between the display-6 block and t801,
        // so the stream cannot read as two display-6 mFocusedApp records.
        val breakBetween = (display6 + 1 until t801).any {
            filtered[it].trimStart().startsWith("ActivityTaskSupervisor state:") ||
                filtered[it].startsWith("Display")
        }
        assertTrue(breakBetween)
        // Only ONE mFocusedApp sits under display 6 (t600), not two.
        val focusUnder6 = (display6 + 1 until t801).count { filtered[it].contains("mFocusedApp=") }
        assertEquals(1, focusUnder6)
    }

    // 9 (valid sibling): normal transitions still scope independently; the new
    // termination step runs before opening the new valid scope.
    @Test
    fun `valid sibling still scopes independently`() {
        val snapshot = parse(malformedSiblingDump("Display: mDisplayId=7 (organized)"))
        assertEquals(DisplayFocus.Task(600), snapshot.focusedTaskByDisplay[6])
        assertEquals(DisplayFocus.Task(801), snapshot.focusedTaskByDisplay[7])
    }

    @Test
    fun `two WM display blocks scope independently`() {
        val dumpText = buildString {
            appendLine("Display #6 (activities from top to bottom):")
            appendLine("  * Task{d6 #600 type=undefined name=Desk U=0 visible=true visibleRequested=true mode=freeform translucent=true sz=1}")
            appendLine("    mCreatedByOrganizer=true")
            appendLine("    * Task{a6 #601 type=standard A=10000:com.example.six U=0 rootTaskId=600 visible=true visibleRequested=true mode=freeform translucent=true sz=1}")
            appendLine("Display #7 (activities from top to bottom):")
            appendLine("  * Task{d7 #700 type=undefined name=Desk U=0 visible=true visibleRequested=true mode=freeform translucent=true sz=1}")
            appendLine("    mCreatedByOrganizer=true")
            appendLine("    * Task{a7 #701 type=standard A=10001:com.example.seven U=0 rootTaskId=700 visible=true visibleRequested=true mode=freeform translucent=true sz=1}")
            appendLine("ActivityTaskSupervisor state:")
            appendLine("  Display: mDisplayId=6 (organized)")
            appendLine("    mFocusedApp=ActivityRecord{x u0 com.example.six/.Main t601}")
            appendLine("  Display: mDisplayId=7 (organized)")
            appendLine("    mFocusedApp=ActivityRecord{y u0 com.example.seven/.Main t701}")
        }
        val snapshot = parse(dumpText)

        assertEquals(DisplayFocus.Task(601), snapshot.focusedTaskByDisplay[6])
        assertEquals(DisplayFocus.Task(701), snapshot.focusedTaskByDisplay[7])
    }
}
