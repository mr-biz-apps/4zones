package uk.mr_biz.fourzones.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S25 + external-DeX dialect: Samsung emits NO mDeskRootTaskType/dw markers.
 * Desktop roots are top-level, organizer-created, freeform roots whose header
 * name is exactly "Desk"; minimized roots are the "MinimizedDesk_" force-hidden
 * form. These tests use sanitized realistic fixtures with SYNTHETIC opaque IDs
 * — no numeric task/root/display value carries meaning.
 */
class DesktopTopologyExternalDexTest {

    private val self = "uk.mr_biz.fourzones"

    /**
     * One external display (#2) with three organizer-created name=Desk roots
     * (one visible with ChatGPT/DexZones/Shizuku children, two invisible with
     * one child each) and two force-hidden MinimizedDesk_ roots. Display #0 is
     * the phone display carrying an organizer SplitRoot (must NOT be mistaken
     * for a desktop). `type=` deliberately varies across Desk roots to prove it
     * is not a discriminator.
     *
     * IDs are parameters so a test can prove classification is ID-independent.
     */
    private fun s25Dump(
        deskA: Int = 700, chatgpt: Int = 701, dexzones: Int = 702, shizuku: Int = 703,
        deskB: Int = 710, appA: Int = 711,
        deskC: Int = 720, appB: Int = 721,
        minA: Int = 730, minB: Int = 740,
        splitRoot: Int = 500, phoneApp: Int = 501,
        externalDisplay: Int = 2,
        minASuffix: String = "9f3a1c",
        minBSuffix: String = "0021bb",
        focusedTaskId: Int = dexzones,
    ): String = """
        Display #0 (activities from top to bottom):
          * Task{sp #$splitRoot type=undefined name=SplitRoot U=0 visible=false visibleRequested=false mode=freeform translucent=true sz=1}
            mCreatedByOrganizer=true
            * Task{spc #$phoneApp type=standard A=10100:com.example.phoneapp U=0 rootTaskId=$splitRoot visible=false visibleRequested=false mode=freeform translucent=true sz=1}
        Display #$externalDisplay (activities from top to bottom):
          * Task{da #$deskA type=undefined name=Desk U=0 visible=true visibleRequested=true mode=freeform translucent=true sz=3}
            mCreatedByOrganizer=true
            isMinimized=false
            * Task{c1 #$chatgpt type=standard A=10281:com.openai.chatgpt U=0 rootTaskId=$deskA visible=true visibleRequested=true mode=freeform translucent=true sz=1}
            * Task{c2 #$dexzones type=standard A=10366:uk.mr_biz.fourzones U=0 rootTaskId=$deskA visible=true visibleRequested=true mode=freeform translucent=true sz=1}
            * Task{c3 #$shizuku type=standard A=10400:moe.shizuku.privileged.api U=0 rootTaskId=$deskA visible=true visibleRequested=true mode=freeform translucent=true sz=1}
          * Task{db #$deskB type=standard name=Desk U=0 visible=false visibleRequested=false mode=freeform translucent=true sz=1}
            mCreatedByOrganizer=true
            * Task{c4 #$appA type=standard A=10500:com.example.appa U=0 rootTaskId=$deskB visible=false visibleRequested=false mode=freeform translucent=true sz=1}
          * Task{dc #$deskC type=undefined name=Desk U=0 visible=false visibleRequested=false mode=freeform translucent=true sz=1}
            mCreatedByOrganizer=true
            * Task{c5 #$appB type=standard A=10600:com.example.appb U=0 rootTaskId=$deskC visible=false visibleRequested=false mode=freeform translucent=true sz=1}
          * Task{ma #$minA type=undefined name=MinimizedDesk_$minASuffix U=0 visible=false visibleRequested=false mode=freeform translucent=true sz=0}
            mForceHiddenFlags=FLAG_FORCE_HIDDEN_FOR_TASK_ORG
            mCreatedByOrganizer=true
            isForceHidden=true
          * Task{mb #$minB type=undefined name=MinimizedDesk_$minBSuffix U=0 visible=false visibleRequested=false mode=freeform translucent=true sz=0}
            mForceHiddenFlags=FLAG_FORCE_HIDDEN_FOR_TASK_ORG
            mCreatedByOrganizer=true
            isForceHidden=true
        ActivityTaskSupervisor state:
          Display: mDisplayId=0
            mFocusedApp=ActivityRecord{fp u0 com.example.phoneapp/.Main t$phoneApp}
          Display: mDisplayId=$externalDisplay
            mFocusedApp=ActivityRecord{f u0 uk.mr_biz.fourzones/.MainActivity t$focusedTaskId}
    """.trimIndent()

    private fun activatableIds(snapshot: DesktopTopologySnapshot) =
        snapshot.activatableRoots.map { it.rootTaskId }

    // 1. topology support detected.
    @Test
    fun `external dex topology is detected`() {
        val snapshot = DesktopTopologyParser.parse(s25Dump())
        assertTrue(snapshot.roots.isNotEmpty())
        // The phone SplitRoot is not a desk root, so it is absent.
        assertTrue(snapshot.roots.none { it.rootTaskId == 500 })
    }

    // 2. three activatable logical desktop roots recognised.
    @Test
    fun `three name-Desk roots are activatable`() {
        val snapshot = DesktopTopologyParser.parse(s25Dump())
        assertEquals(listOf(700, 710, 720), activatableIds(snapshot))
        assertTrue(snapshot.activatableRoots.all { it.type == DeskRootType.ACTIVATABLE })
    }

    // 3. minimized roots recognised separately.
    @Test
    fun `minimizedDesk roots are minimized and separate`() {
        val snapshot = DesktopTopologyParser.parse(s25Dump())
        assertEquals(listOf(730, 740), snapshot.minimizedRoots.map { it.rootTaskId })
        assertTrue(snapshot.minimizedRoots.all { it.type == DeskRootType.MINIMIZED })
    }

    // 4 & 5. exactly one active desktop Found, the visible Desk root.
    @Test
    fun `the single visible Desk root is the active desktop`() {
        val snapshot = DesktopTopologyParser.parse(s25Dump())
        assertEquals(ActiveDesktopAssessment.Found(700), snapshot.activeDesktopByDisplay[2])
        // The phone display has no desk topology at all.
        assertTrue(snapshot.activeDesktopByDisplay.keys == setOf(2))
    }

    // 6. children belong structurally to the active root.
    @Test
    fun `active Desk root owns its three children structurally`() {
        val snapshot = DesktopTopologyParser.parse(s25Dump())
        val active = snapshot.roots.single { it.rootTaskId == 700 }
        assertEquals(listOf(701, 702, 703), active.childTasks.map { it.taskId })
        assertEquals(
            listOf("com.openai.chatgpt", "uk.mr_biz.fourzones", "moe.shizuku.privileged.api"),
            active.childTasks.map { it.packageName },
        )
    }

    // 7. inactive-desktop children cannot become the current target.
    @Test
    fun `a focused child of an inactive Desk root is not a target`() {
        // Focus the child of the inactive desk B.
        val snapshot = DesktopTopologyParser.parse(s25Dump(focusedTaskId = 711))
        val target = SnapTargetResolver.resolve(snapshot, self).getValue(2)
        val noTarget = target as SnapTargetAssessment.NoTarget
        assertTrue(noTarget.reason.contains("710"))
    }

    // 8. DexZones host is excluded when it is the focused task.
    @Test
    fun `focused DexZones host is never a target`() {
        val snapshot = DesktopTopologyParser.parse(s25Dump(focusedTaskId = 702))
        val target = SnapTargetResolver.resolve(snapshot, self).getValue(2)
        assertTrue((target as SnapTargetAssessment.NoTarget).reason.contains("host app itself"))
    }

    @Test
    fun `a focused non-host child of the active Desk root is a valid target`() {
        // ChatGPT focused: a genuine safe target within the active desktop.
        val snapshot = DesktopTopologyParser.parse(s25Dump(focusedTaskId = 701))
        val target = SnapTargetResolver.resolve(snapshot, self).getValue(2)
        val found = target as SnapTargetAssessment.Found
        assertEquals(701, found.targetTaskId)
        assertEquals(700, found.activeDeskRootId)
        assertEquals(2, found.displayId)
        assertEquals("com.openai.chatgpt", found.packageName)
    }

    // 9 & 10. numeric suffixes / IDs are irrelevant to classification.
    @Test
    fun `changing every opaque id and suffix leaves classification unchanged`() {
        val base = DesktopTopologyParser.parse(s25Dump())
        val permuted = DesktopTopologyParser.parse(
            s25Dump(
                deskA = 91001, chatgpt = 4, dexzones = 88, shizuku = 123456,
                deskB = 2, appA = 999999, deskC = 71, appB = 5,
                minA = 3, minB = Int.MAX_VALUE,
                splitRoot = 60000, phoneApp = 60001,
                externalDisplay = 9,
                minASuffix = "ZZZZ", minBSuffix = "a1b2c3d4",
                focusedTaskId = 88,
            ),
        )

        assertEquals(3, base.activatableRoots.size)
        assertEquals(3, permuted.activatableRoots.size)
        assertEquals(2, permuted.minimizedRoots.size)
        // Active desktop is the visible Desk root whatever its opaque id.
        assertEquals(ActiveDesktopAssessment.Found(91001), permuted.activeDesktopByDisplay[9])
        // Self is still excluded regardless of ids.
        val target = SnapTargetResolver.resolve(permuted, self).getValue(9)
        assertTrue(target is SnapTargetAssessment.NoTarget)
    }
}
