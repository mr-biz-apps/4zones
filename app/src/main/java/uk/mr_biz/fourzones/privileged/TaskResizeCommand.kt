package uk.mr_biz.fourzones.privileged

/**
 * Pure construction of the fixed task-resize command argv. Framework-free and
 * JVM-testable so the "no arbitrary shell" property is provable in isolation.
 *
 * The command is a FIXED token list plus five decimal-integer arguments:
 *
 *     cmd activity task resize <taskId> <left> <top> <right> <bottom>
 *
 * Every variable argument is an Int rendered as a decimal string, so there is
 * no shell, no interpolation, and no quoting problem to get wrong. This is
 * deliberately NOT a general command builder: it takes typed integers only,
 * never a command name or free text.
 */
object TaskResizeCommand {

    /** Integer status codes returned across the binder (see [TaskResizeGateway]). */
    const val STATUS_SUCCESS = 0
    const val STATUS_COMMAND_FAILED = 1
    const val STATUS_TIMED_OUT = 2
    const val STATUS_PROCESS_ERROR = 3

    /** Rejected before any process launch (invalid/inverted bounds). */
    const val STATUS_REJECTED = 4

    /**
     * Builds the fixed argv. Rejects inverted/empty bounds by returning null —
     * the caller must not launch a process for a rejected rectangle. Coordinate
     * magnitude (including Int.MIN_VALUE/MAX_VALUE) never changes the argv
     * structure; each remains a single decimal token.
     */
    fun argvOrNull(taskId: Int, left: Int, top: Int, right: Int, bottom: Int): List<String>? {
        if (right <= left || bottom <= top) return null
        return listOf(
            "cmd", "activity", "task", "resize",
            taskId.toString(),
            left.toString(),
            top.toString(),
            right.toString(),
            bottom.toString(),
        )
    }
}
