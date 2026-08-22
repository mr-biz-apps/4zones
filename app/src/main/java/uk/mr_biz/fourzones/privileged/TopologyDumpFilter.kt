package uk.mr_biz.fourzones.privileged

/**
 * Thrown when the filtered structural output exceeds the binder-safe limit.
 * The contract is FAIL CLOSED: a truncated prefix of a topology dump can be
 * syntactically valid yet semantically wrong (e.g. an activatable root after
 * the cutoff would have turned FOUND into AMBIGUOUS), so oversized output is
 * an error — never a partial result.
 */
class TopologyOutputTooLargeException(limit: Int) : IllegalStateException(
    "Filtered topology output exceeded $limit characters; failing closed " +
        "instead of returning a truncated topology",
)

/**
 * Pure structural filter applied INSIDE the privileged service before any
 * dump text crosses the binder back to the app process.
 *
 * Purpose:
 *  - data minimization: the app receives only the task-hierarchy and focus
 *    lines the topology parser needs, never the full activity dump (which
 *    contains intents, package paths, and other unrelated data);
 *  - robustness: output is capped far below binder transaction limits, and
 *    exceeding the cap fails closed (see [TopologyOutputTooLargeException]).
 *
 * This is a TRUE ALLOWLIST: every retained line must match an explicit
 * structural pattern. There is no generic "keep top-level lines" rule —
 * unknown top-level dump output is discarded. The filter tracks display
 * regions itself, so task-shaped lines echoed after a discarded unknown
 * heading are also discarded and can never masquerade as topology.
 *
 * The filter is line-based and preserves order and indentation of retained
 * lines, so the filtered output remains a valid (smaller) input for the
 * parser. It is framework-free and JVM-testable.
 */
object TopologyDumpFilter {

    /** Hard cap well below the ~1 MB binder transaction limit. */
    const val MAX_OUTPUT_CHARS = 400_000

    /**
     * Exact top-level form that opens an activities hierarchy region. The
     * only other retained top-level form is [SUPERVISOR_BOUNDARY]; every
     * other unindented line is discarded (and still closes the region).
     */
    private val DISPLAY_SECTION_OPEN = Regex("""^Display #\d+ \(activities from top to bottom\):""")

    /**
     * Exact boundary form the parser recognizes as terminating the hierarchy
     * region. Retained so the parser's region tracking sees the same
     * boundary the filter used.
     */
    private const val SUPERVISOR_BOUNDARY = "ActivityTaskSupervisor state:"

    /** Indented lines retained only while inside a display region. */
    private val IN_REGION_TRIMMED_PREFIXES = listOf(
        "* Hist ", // component records for child-task diagnostics
        "mDeskRootTaskType=",
        "mCreatedByOrganizer=",
        "isForceHidden=",
    )

    /** Task header form; retained and used to anchor the direct-property indent. */
    private const val TASK_HEADER_PREFIX = "* Task{"

    /**
     * dumpsys indents a task's direct property lines exactly this many columns
     * past its `* Task{...}` header's leading whitespace. Task bounds are
     * retained ONLY at that exact offset (see [DesktopTopologyParser], which
     * enforces the same rule); nested Activity/window/configuration standalone
     * mBounds are more deeply indented and never retained.
     */
    private const val DIRECT_PROPERTY_INDENT_STEP = 2

    /**
     * Task OUTER bounds for Phase 2C3A postcondition verification. Only the
     * standalone `mBounds=Rect(l, t - r, b)` task line is retained — fully
     * anchored so anything with trailing content is discarded, and nested
     * winConfig mBounds (which live inside `mGlobalConfig=`/`...Configuration=`
     * lines that never start with `mBounds=`) are likewise never retained.
     */
    private val TASK_BOUNDS_LINE =
        Regex("""^mBounds=Rect\(\s*-?\d+,\s*-?\d+\s*-\s*-?\d+,\s*-?\d+\)$""")

    /** The one focus record the parser uses; it appears outside the region. */
    private const val FOCUS_TRIMMED_PREFIX = "mFocusedApp="

    /**
     * WindowManager display block header inside ActivityTaskSupervisor state.
     * Retained so the parser can scope each mFocusedApp to its display; it is
     * one short line per display, not a broadening of raw dump retention.
     *
     * FULLY ANCHORED (matched against the trimmed line): only the observed
     * `Display: mDisplayId=<n> (organized)` form (optional `(organized)`,
     * nothing else trailing) is treated as a WM header. A malformed line such
     * as `Display: mDisplayId=6garbage` is NOT retained as a WM header, so it
     * can never reach the parser as authoritative scope evidence.
     *
     * This pattern is duplicated verbatim in DesktopTopologyParser (they
     * cannot share a symbol without a package dependency cycle); a parity test
     * proves the two grammars accept/reject identical forms.
     */
    private val WM_DISPLAY_HEADER =
        Regex("""^Display: mDisplayId=(\d+)(?: \(organized\))?\s*$""")

    /**
     * Narrow "WM-display-looking sibling" candidate prefix. ANY line beginning
     * with this terminates the previous WM focus scope; only one that also
     * full-matches [WM_DISPLAY_HEADER] opens a new one. Kept in sync with the
     * same constant in DesktopTopologyParser (parity-tested).
     */
    private const val WM_DISPLAY_PREFIX = "Display: mDisplayId="

    fun filter(rawDump: String): String = filterLines(rawDump.lineSequence())

    /**
     * Streaming variant: consumes lines one at a time so only retained
     * (filtered) content is ever accumulated — memory stays bounded by
     * [MAX_OUTPUT_CHARS] regardless of raw dump size.
     *
     * @throws TopologyOutputTooLargeException the moment retained output
     * would exceed [MAX_OUTPUT_CHARS]; no partial result is returned.
     */
    fun filterLines(lines: Sequence<String>): String {
        val out = StringBuilder()
        var inDisplayRegion = false
        // Leading-whitespace width of the most recent in-region task header, or
        // -1 when there is no current task (region just opened/closed). Bounds
        // are retained only at currentTaskIndent + DIRECT_PROPERTY_INDENT_STEP.
        var currentTaskIndent = -1
        // True after a `Display: mDisplayId=N` header has been emitted and no
        // scope-resetting top-level line has been emitted since. Tracks the
        // raw WM display scope so a dropped top-level boundary cannot let a
        // later mFocusedApp be mis-scoped to a stale display in the parser.
        var wmScopeEmitted = false

        fun append(text: String) {
            if (out.length + text.length + 1 > MAX_OUTPUT_CHARS) {
                throw TopologyOutputTooLargeException(MAX_OUTPUT_CHARS)
            }
            out.append(text).append('\n')
        }

        for (line in lines) {
            if (line.isEmpty()) continue

            if (!line.first().isWhitespace()) {
                // Any top-level line ends the current task AND WM focus scope.
                currentTaskIndent = -1
                val retained = when {
                    DISPLAY_SECTION_OPEN.containsMatchIn(line) -> {
                        inDisplayRegion = true
                        true
                    }
                    line.startsWith(SUPERVISOR_BOUNDARY) -> {
                        inDisplayRegion = false
                        true
                    }
                    else -> {
                        // Unknown top-level output: discarded, but it still
                        // terminates the region.
                        inDisplayRegion = false
                        false
                    }
                }
                // A retained top-level boundary resets the parser's WM scope by
                // itself. A DROPPED one does not reach the parser, so emit a
                // synthetic reset (the supervisor boundary, which the parser
                // treats as a scope reset) before any later mFocusedApp.
                if (wmScopeEmitted) {
                    if (!retained) append(SUPERVISOR_BOUNDARY)
                    wmScopeEmitted = false
                }
                if (retained) append(line)
                continue
            }

            val trimmed = line.trimStart()
            val indent = line.length - trimmed.length
            val isWmCandidate = trimmed.startsWith(WM_DISPLAY_PREFIX)
            val isWmHeader = isWmCandidate && WM_DISPLAY_HEADER.matches(trimmed)

            // A malformed WM-display-looking sibling terminates the previous
            // scope: it is dropped, so emit a synthetic reset (which the parser
            // treats as a scope break) if a scope was open. A VALID header
            // re-scopes the parser by itself and needs no reset.
            if (isWmCandidate && !isWmHeader && wmScopeEmitted) {
                append(SUPERVISOR_BOUNDARY)
                wmScopeEmitted = false
            }

            val retained = when {
                trimmed.startsWith(FOCUS_TRIMMED_PREFIX) -> true
                isWmHeader -> true
                !inDisplayRegion -> false
                trimmed.startsWith(TASK_HEADER_PREFIX) -> {
                    currentTaskIndent = indent
                    true
                }
                IN_REGION_TRIMMED_PREFIXES.any { trimmed.startsWith(it) } -> true
                // Task bounds: the exact standalone rectangle form AND the
                // current task's exact direct-property indentation. Nested
                // Activity/config/window mBounds are discarded here too.
                trimmed.startsWith("mBounds=") ->
                    currentTaskIndent >= 0 &&
                        indent == currentTaskIndent + DIRECT_PROPERTY_INDENT_STEP &&
                        TASK_BOUNDS_LINE.matches(trimmed)
                else -> false
            }
            if (!retained) continue
            // Emitting a valid WM display header opens a raw focus scope.
            if (isWmHeader) wmScopeEmitted = true
            append(line)
        }
        return out.toString()
    }
}
