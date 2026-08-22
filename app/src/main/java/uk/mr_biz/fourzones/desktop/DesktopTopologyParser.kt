package uk.mr_biz.fourzones.desktop

/**
 * Pure parser for the filtered "dumpsys activity activities" structural dump.
 * No Android dependencies; JVM-testable. Never throws on malformed input —
 * unparseable lines are skipped and missing data stays null.
 *
 * Structure recovered (as observed on A9 DeX hardware):
 *
 *   Display #0 (activities from top to bottom):
 *     * Task{hash #11705 type=undefined dw=activatable ... visible=true ...}
 *       mCreatedByOrganizer=true
 *       mDeskRootTaskType=activatable
 *       * Task{hash #11711 type=standard A=10366:com.pkg rootTaskId=11705 ...}
 *         * Hist  #0: ActivityRecord{hash u0 com.pkg/.Main t11711}
 *
 * Child membership comes from the printed hierarchy: the explicit
 * rootTaskId= back-reference when present, else the enclosing root by
 * indentation. Numeric task-ID values and their proximity are never used.
 */
object DesktopTopologyParser {

    /**
     * dumpsys indents a task's direct property lines exactly two columns past
     * the leading whitespace of that task's `* Task{...}` header (the property
     * text aligns under the `* ` list marker). Task outer bounds are accepted
     * ONLY at this exact offset; anything deeper is a nested
     * Activity/window/configuration property.
     */
    private const val DIRECT_PROPERTY_INDENT_STEP = 2

    fun parse(dump: String): DesktopTopologySnapshot {
        val state = ParseState()
        for (line in dump.lineSequence()) {
            state.consume(line)
        }
        val deskRoots = state.deskRoots()
        val byDisplay = assessActiveDesktopByDisplay(deskRoots)
        return DesktopTopologySnapshot(
            roots = deskRoots,
            activeDesktopByDisplay = byDisplay,
            focusedTaskId = state.focusedTaskId,
            focusedTaskByDisplay = state.focusedTaskByDisplay.toMap(),
            hasDisplayScopedFocusEvidence = state.hasDisplayScopedFocusEvidence,
            observedDisplayIds = state.observedDisplayIds.toSet(),
            evidence = buildEvidence(deskRoots, byDisplay, state.focusedTaskId),
        )
    }

    /**
     * Authoritative per-display assessment: each observed display is judged
     * independently, so one active desktop on display A and one on display B
     * is FOUND for each — never a cross-display AMBIGUOUS. Display IDs are
     * grouping keys only, with no ordering or capability meaning. Roots
     * without a display association are excluded here (never silently
     * assigned to another display) and surfaced via snapshot evidence.
     */
    fun assessActiveDesktopByDisplay(
        roots: List<DesktopRoot>,
    ): Map<Int, ActiveDesktopAssessment> = roots
        .filter { it.displayId != null }
        .groupBy { it.displayId!! }
        .mapValues { (_, group) -> assessActiveDesktop(group) }

    /**
     * Conservative active-desktop decision for ONE display's desk roots,
     * exposed for direct testing. The ONLY accepted positive evidence is an
     * ACTIVATABLE desk root with positively consistent visibility (see
     * [hasPositiveVisibility]). Focus fields, display-ID values, ID ordering
     * and ID numbering are deliberately never inputs to this decision.
     */
    fun assessActiveDesktop(roots: List<DesktopRoot>): ActiveDesktopAssessment {
        if (roots.none { it.type != DeskRootType.UNKNOWN }) {
            return ActiveDesktopAssessment.Unsupported
        }
        val candidates = roots.filter {
            it.type == DeskRootType.ACTIVATABLE && hasPositiveVisibility(it)
        }
        return when (candidates.size) {
            0 -> ActiveDesktopAssessment.None
            1 -> ActiveDesktopAssessment.Found(candidates.single().rootTaskId)
            else -> ActiveDesktopAssessment.Ambiguous(candidates.map { it.rootTaskId })
        }
    }

    /**
     * Positive visibility requires agreement, tolerating only absent data:
     * true/true, true/null and null/true are positive; a contradictory
     * transitional pair (true/false or false/true) is NOT — a temporary NONE
     * or AMBIGUOUS during a desktop-switch animation is acceptable, a false
     * FOUND is not. false/false and null/null are simply not visible.
     */
    fun hasPositiveVisibility(root: DesktopRoot): Boolean {
        val visible = root.visible
        val requested = root.visibleRequested
        return (visible == true && requested != false) ||
            (requested == true && visible != false)
    }

    /** True when the two visibility fields actively contradict each other. */
    fun hasContradictoryVisibility(root: DesktopRoot): Boolean =
        (root.visible == true && root.visibleRequested == false) ||
            (root.visible == false && root.visibleRequested == true)

    // ---------------------------------------------------------------- parsing

    // Literal braces are ALWAYS explicitly escaped (\{ and \}) outside
    // character classes: Android's ICU regex implementation rejects a bare
    // literal `}` that OpenJDK tolerates (PatternSyntaxException at class
    // initialization on device — observed on the A9, Android 16/API 36).
    private val DISPLAY_HEADER = Regex("""^Display #(\d+) \(activities""")
    // WindowManager display block inside ActivityTaskSupervisor state — a
    // DIFFERENT grammar from the task-hierarchy `Display #N (activities...)`
    // header. Scopes the mFocusedApp line(s) that follow it to display N.
    //
    // FULLY ANCHORED (matchEntire on the trimmed line): the observed hardware
    // form is `Display: mDisplayId=<n> (organized)`; the `(organized)` suffix
    // is optional but nothing else may trail. A malformed line such as
    // `Display: mDisplayId=6garbage` must NOT be accepted — it must not set
    // scoped evidence, establish a scope, or scope a later mFocusedApp.
    //
    // This pattern is duplicated verbatim in TopologyDumpFilter (filter and
    // parser cannot share a symbol without a package dependency cycle); a
    // parity test proves the two grammars accept/reject identical forms.
    private val WM_DISPLAY_HEADER =
        Regex("""^Display: mDisplayId=(\d+)(?: \(organized\))?\s*$""")

    // Narrow "WM-display-looking sibling" candidate: a line that begins with
    // the WM display-header structural prefix. ANY such line terminates the
    // previous WM focus scope; only one that also FULL-matches
    // [WM_DISPLAY_HEADER] establishes a new scope. Kept in sync with the same
    // constant in TopologyDumpFilter (parity-tested).
    private const val WM_DISPLAY_PREFIX = "Display: mDisplayId="
    private val TASK_LINE = Regex("""^(\s+)\* Task\{(\S+) #(\d+) ([^}]*)\}""")
    private val HIST_LINE = Regex("""\* Hist .*?ActivityRecord\{\S+ u\d+ (\S+) t(\d+)""")
    private val FOCUSED_APP = Regex("""mFocusedApp=ActivityRecord\{\S+ u\d+ (\S+) t(\d+)""")

    // Structural task-outer-bounds line: `mBounds=Rect(l, t - r, b)`. FULLY
    // anchored (^...$ on the trimmed line) so any trailing/malformed content
    // is rejected (=> null), and nested winConfig mBounds can never match.
    // Braces escaped for Android ICU regex (see TASK_LINE note).
    private val TASK_BOUNDS =
        Regex("""^mBounds=Rect\(\s*(-?\d+),\s*(-?\d+)\s*-\s*(-?\d+),\s*(-?\d+)\)$""")

    private class MutableTask(
        val taskId: Int,
        val indent: Int,
        val attrs: Map<String, String>,
    ) {
        var component: String? = null
        var bounds: TaskBounds? = null

        fun packageHint(): String? =
            attrs["A"]?.substringAfter(':', missingDelimiterValue = "")?.ifEmpty { null }
                ?: attrs["I"]?.substringBefore('/')?.ifEmpty { null }
    }

    private class MutableRoot(
        val rootTaskId: Int,
        val indent: Int,
        val displayId: Int?,
        val attrs: Map<String, String>,
    ) {
        // Header dw= token is the fallback; the mDeskRootTaskType property
        // line overrides it when present. Both carried the same values in
        // every A9 hardware capture.
        var deskTypeRaw: String? = attrs["dw"]
        var createdByOrganizer: Boolean? = null
        var forceHidden: Boolean? = null
        val children = mutableListOf<MutableTask>()

        val headerName: String? get() = attrs["name"]
        val windowingMode: String? get() = attrs["mode"]
        val headerVisible: Boolean? get() = attrs["visible"]?.toBooleanStrictOrNull()
        val headerVisibleRequested: Boolean?
            get() = attrs["visibleRequested"]?.toBooleanStrictOrNull()
    }

    /**
     * Classifies a top-level root across BOTH observed Samsung DeX dialects.
     *
     * Precedence: an explicit A9 marker (header `dw=` or the
     * `mDeskRootTaskType=` property) is AUTHORITATIVE and is honored exactly
     * as before — including mapping an unrecognized explicit value to
     * [DeskRootType.UNKNOWN]. The S25 external-DeX organizer/name fallback is
     * consulted ONLY when no explicit marker is present, so A9 semantics can
     * never be reinterpreted.
     *
     * Returns null when the root is not a desk root in either dialect.
     */
    private fun classifyRoot(root: MutableRoot): DeskRootType? {
        root.deskTypeRaw?.let {
            return when (it) {
                "activatable" -> DeskRootType.ACTIVATABLE
                "minimized" -> DeskRootType.MINIMIZED
                else -> DeskRootType.UNKNOWN
            }
        }
        if (isExternalActivatableDesk(root)) return DeskRootType.ACTIVATABLE
        if (isExternalMinimizedDesk(root)) return DeskRootType.MINIMIZED
        return null
    }

    /**
     * S25 external-DeX activatable desktop: a top-level, organizer-created,
     * freeform root whose header name is EXACTLY "Desk". Visibility is NOT a
     * classification input — an inactive Desk root is still an activatable
     * desktop; visibility only decides which one is Found. The organizer +
     * freeform + exact-name conjunction excludes ordinary freeform apps and
     * organizer SplitRoots.
     */
    private fun isExternalActivatableDesk(root: MutableRoot): Boolean =
        root.headerName == "Desk" &&
            root.createdByOrganizer == true &&
            root.windowingMode == "freeform"

    /**
     * S25 external-DeX minimized desk root: the conservative form — a
     * top-level, organizer-created, freeform root whose header name begins
     * with "MinimizedDesk_", that is non-visible on both flags AND
     * force-hidden. The numeric suffix is NEVER parsed or used as identity.
     * Requiring all of visible=false, visibleRequested=false and
     * isForceHidden=true keeps a merely-invisible task from being mistaken
     * for a minimized desk root.
     */
    private fun isExternalMinimizedDesk(root: MutableRoot): Boolean =
        root.headerName?.startsWith("MinimizedDesk_") == true &&
            root.createdByOrganizer == true &&
            root.windowingMode == "freeform" &&
            root.headerVisible == false &&
            root.headerVisibleRequested == false &&
            root.forceHidden == true

    private class ParseState {
        val allRoots = mutableListOf<MutableRoot>()
        val tasksById = mutableMapOf<Int, MutableTask>()
        var focusedTaskId: Int? = null
        val focusedTaskByDisplay = mutableMapOf<Int, DisplayFocus>()
        var hasDisplayScopedFocusEvidence = false
        // Every physical display seen in a `Display #N (activities...)` header,
        // used only to tell single-display from multi-display systems (see
        // snapshot.observedDisplayIds). Never used for identity or ordering.
        val observedDisplayIds = mutableSetOf<Int>()

        private var currentDisplayId: Int? = null
        private var inDisplaySection = false
        private var currentRoot: MutableRoot? = null
        private var currentChild: MutableTask? = null
        // The WindowManager `Display: mDisplayId=N` block currently in scope
        // (in the ActivityTaskSupervisor state). Distinct from the task
        // hierarchy's `Display #N (activities...)` sections.
        private var currentFocusDisplayId: Int? = null

        fun consume(line: String) {
            if (line.isEmpty()) return

            if (!line.first().isWhitespace()) {
                // Any top-level heading ends the WindowManager display block.
                currentFocusDisplayId = null
                val displayMatch = DISPLAY_HEADER.find(line)
                if (displayMatch != null) {
                    currentDisplayId = displayMatch.groupValues[1].toIntOrNull()
                    currentDisplayId?.let { observedDisplayIds += it }
                    inDisplaySection = true
                } else if (!line.startsWith("ACTIVITY MANAGER")) {
                    // Any other top-level heading ends the hierarchy region;
                    // task lines repeated in later dump sections must not
                    // re-parse as topology.
                    inDisplaySection = false
                    currentRoot = null
                    currentChild = null
                }
                return
            }

            // WindowManager display block header inside ActivityTaskSupervisor
            // state; scopes the mFocusedApp that follows to this display.
            // Observing the header alone is evidence the scoped-focus grammar
            // exists — even if its mFocusedApp turns out null/malformed/absent.
            val wmTrimmed = line.trimStart()
            if (wmTrimmed.startsWith(WM_DISPLAY_PREFIX)) {
                // A WM-display-looking sibling ALWAYS terminates the previous
                // scope FIRST, then re-establishes one only if the full
                // anchored grammar matches. A malformed candidate therefore
                // leaves the parser unscoped (a following mFocusedApp cannot
                // inherit the prior display) and sets no new evidence.
                currentFocusDisplayId = null
                val match = WM_DISPLAY_HEADER.matchEntire(wmTrimmed)
                if (match != null) {
                    hasDisplayScopedFocusEvidence = true
                    currentFocusDisplayId = match.groupValues[1].toIntOrNull()
                }
                return
            }

            // Focus: recorded both as the legacy global (last seen) AND scoped
            // to the current WindowManager display block when one is in scope.
            // Focus is NEVER an input to the active-desktop assessment.
            val focusMatch = FOCUSED_APP.find(line)
            if (focusMatch != null) {
                val taskId = focusMatch.groupValues[2].toIntOrNull()
                if (taskId != null) {
                    focusedTaskId = taskId
                    currentFocusDisplayId?.let { recordDisplayFocus(it, taskId) }
                }
                return
            }

            if (!inDisplaySection) return

            val taskMatch = TASK_LINE.find(line)
            if (taskMatch != null) {
                consumeTaskLine(taskMatch)
                return
            }

            val histMatch = HIST_LINE.find(line)
            if (histMatch != null) {
                val taskId = histMatch.groupValues[2].toIntOrNull() ?: return
                val task = tasksById[taskId] ?: return
                if (task.component == null) task.component = histMatch.groupValues[1]
                return
            }

            val trimmed = line.trimStart()

            // Task outer bounds attach structurally to the current child task,
            // and ONLY when this line sits at that task's EXACT direct-property
            // indentation (child-header indent + DIRECT_PROPERTY_INDENT_STEP).
            // A nested Activity/window/configuration standalone `mBounds=Rect`
            // is more deeply indented and must never attach; a shallower
            // sibling/root line likewise. Diagnostic-only; never used for
            // identity or selection.
            if (trimmed.startsWith("mBounds=")) {
                val child = currentChild
                val indent = line.length - trimmed.length
                if (child != null &&
                    child.bounds == null &&
                    indent == child.indent + DIRECT_PROPERTY_INDENT_STEP
                ) {
                    child.bounds = parseTaskBounds(trimmed)
                }
                return
            }

            consumeRootPropertyLine(trimmed)
        }

        /**
         * Records display-scoped focus, failing closed on conflict: a second,
         * DIFFERING focused task for the same display marks it [Conflicting].
         */
        private fun recordDisplayFocus(displayId: Int, taskId: Int) {
            val existing = focusedTaskByDisplay[displayId]
            focusedTaskByDisplay[displayId] = when {
                existing == null -> DisplayFocus.Task(taskId)
                existing is DisplayFocus.Task && existing.taskId == taskId -> existing
                else -> DisplayFocus.Conflicting
            }
        }

        private fun consumeTaskLine(match: MatchResult) {
            val indent = match.groupValues[1].length
            val taskId = match.groupValues[3].toIntOrNull() ?: return
            val attrs = parseAttrs(match.groupValues[4])
            val rootIndent = currentRoot?.indent
            if (rootIndent == null || indent <= rootIndent) {
                val root = MutableRoot(taskId, indent, currentDisplayId, attrs)
                allRoots += root
                currentRoot = root
                currentChild = null
            } else {
                val child = MutableTask(taskId, indent, attrs)
                tasksById[taskId] = child
                currentChild = child
                // Membership: explicit back-reference first (it IS the
                // hierarchy as printed), else the enclosing root.
                val owner = attrs["rootTaskId"]?.toIntOrNull()
                    ?.let { ref -> allRoots.lastOrNull { it.rootTaskId == ref } }
                    ?: currentRoot
                owner?.children?.add(child)
            }
        }

        // Root-level property lines sit between the root header and its first
        // child in every observed capture; the header dw= token remains the
        // fallback if a variant layout ever hides them.
        private fun consumeRootPropertyLine(trimmed: String) {
            val root = currentRoot ?: return
            if (currentChild != null) return
            when {
                trimmed.startsWith("mDeskRootTaskType=") -> {
                    val value = trimmed.substringAfter('=').trim()
                    if (value.isNotEmpty()) root.deskTypeRaw = value
                }
                trimmed.startsWith("mCreatedByOrganizer=") ->
                    root.createdByOrganizer =
                        trimmed.substringAfter('=').trim().toBooleanStrictOrNull()
                trimmed.startsWith("isForceHidden=") ->
                    root.forceHidden =
                        trimmed.substringAfter('=').trim().toBooleanStrictOrNull()
            }
        }

        fun deskRoots(): List<DesktopRoot> = allRoots
            .mapNotNull { root ->
                val type = classifyRoot(root) ?: return@mapNotNull null
                DesktopRoot(
                    rootTaskId = root.rootTaskId,
                    displayId = root.displayId,
                    type = type,
                    visible = root.attrs["visible"]?.toBooleanStrictOrNull(),
                    visibleRequested = root.attrs["visibleRequested"]?.toBooleanStrictOrNull(),
                    createdByOrganizer = root.createdByOrganizer,
                    forceHidden = root.forceHidden,
                    windowingMode = root.attrs["mode"],
                    childTasks = root.children.map { child ->
                        DesktopTask(
                            taskId = child.taskId,
                            packageName = child.packageHint()
                                ?: child.component?.substringBefore('/'),
                            componentName = child.component ?: child.attrs["I"],
                            visible = child.attrs["visible"]?.toBooleanStrictOrNull(),
                            focused = focusedTaskId?.let { it == child.taskId },
                            bounds = child.bounds,
                        )
                    },
                )
            }
    }

    private fun parseTaskBounds(trimmed: String): TaskBounds? {
        val m = TASK_BOUNDS.find(trimmed) ?: return null
        val left = m.groupValues[1].toIntOrNull() ?: return null
        val top = m.groupValues[2].toIntOrNull() ?: return null
        val right = m.groupValues[3].toIntOrNull() ?: return null
        val bottom = m.groupValues[4].toIntOrNull() ?: return null
        return TaskBounds(left, top, right, bottom)
    }

    private fun parseAttrs(raw: String): Map<String, String> {
        val attrs = mutableMapOf<String, String>()
        for (token in raw.split(' ')) {
            val eq = token.indexOf('=')
            if (eq <= 0) continue
            val key = token.substring(0, eq)
            if (key !in attrs) attrs[key] = token.substring(eq + 1)
        }
        return attrs
    }

    private fun buildEvidence(
        roots: List<DesktopRoot>,
        byDisplay: Map<Int, ActiveDesktopAssessment>,
        focusedTaskId: Int?,
    ): List<String> {
        val evidence = mutableListOf<String>()
        if (roots.isEmpty()) {
            evidence += "No mDeskRootTaskType/dw desk-root fields observed: Samsung desk " +
                "topology is UNSUPPORTED/undetermined for this dump. Not guessing."
            return evidence
        }
        val activatable = roots.count { it.type == DeskRootType.ACTIVATABLE }
        val minimized = roots.count { it.type == DeskRootType.MINIMIZED }
        val unknown = roots.count { it.type == DeskRootType.UNKNOWN }
        evidence += "Desk roots observed: $activatable activatable, $minimized minimized" +
            (if (unknown > 0) ", $unknown of unrecognized type (treated conservatively)" else "") +
            ". Root task IDs are opaque handles; their numeric values carry no meaning."
        byDisplay.forEach { (displayId, assessment) ->
            evidence += "Display $displayId: " + describeAssessment(assessment) +
                " (each display is assessed independently; display IDs are scope keys only)."
        }
        val unassociated = roots.filter { it.displayId == null }
        if (unassociated.isNotEmpty()) {
            evidence += "Desk root(s) ${unassociated.map { it.rootTaskId }} have no known " +
                "display association; excluded from active-desktop assessment rather than " +
                "assigned to a display by guesswork."
        }
        val contradictory = roots.filter { hasContradictoryVisibility(it) }
        if (contradictory.isNotEmpty()) {
            evidence += "Desk root(s) ${contradictory.map { it.rootTaskId }} report " +
                "contradictory visibility (visible != visibleRequested): treated as " +
                "transitional, never as positively active."
        }
        if (focusedTaskId != null) {
            evidence += "mFocusedApp task $focusedTaskId recorded diagnostically only — focus " +
                "is NOT used for active-desktop identity (launcher focus can occur while a " +
                "desktop is visibly active)."
        }
        return evidence
    }

    private fun describeAssessment(assessment: ActiveDesktopAssessment): String =
        when (assessment) {
            is ActiveDesktopAssessment.Found ->
                "exactly one activatable desk root is positively visible — " +
                    "root ${assessment.rootTaskId} appears to be the active desktop"
            is ActiveDesktopAssessment.Ambiguous ->
                "multiple activatable desk roots appear positively visible " +
                    "(${assessment.candidateRootTaskIds}); active desktop is AMBIGUOUS"
            ActiveDesktopAssessment.None ->
                "desk topology present but no activatable root is positively visible; " +
                    "no active desktop"
            ActiveDesktopAssessment.Unsupported ->
                "desk-root fields present only with unrecognized values; support " +
                    "undetermined rather than guessed"
        }
}
