package uk.mr_biz.fourzones.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The privileged mutation must be a fixed argv of integer tokens — never a
 * shell string. These tests pin that construction; there is deliberately no
 * generic shell API to test.
 */
class TaskResizeCommandTest {

    @Test
    fun `builds fixed argv from integer tokens`() {
        val argv = TaskResizeCommand.argvOrNull(123, -10, 20, 500, 700)

        assertEquals(
            listOf("cmd", "activity", "task", "resize", "123", "-10", "20", "500", "700"),
            argv,
        )
    }

    @Test
    fun `negative coordinates remain single decimal integer tokens`() {
        val argv = TaskResizeCommand.argvOrNull(1, -1920, -1080, -10, -5)!!

        // Nine tokens exactly; the negatives are plain decimal, not split or quoted.
        assertEquals(9, argv.size)
        assertEquals("-1920", argv[5])
        assertEquals("-1080", argv[6])
    }

    @Test
    fun `extreme int bounds do not alter the argument structure`() {
        val argv = TaskResizeCommand.argvOrNull(
            Int.MAX_VALUE, Int.MIN_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, Int.MAX_VALUE,
        )!!

        assertEquals(9, argv.size)
        assertEquals(Int.MAX_VALUE.toString(), argv[4])
        assertEquals(Int.MIN_VALUE.toString(), argv[5])
        assertEquals(Int.MIN_VALUE.toString(), argv[6])
    }

    @Test
    fun `inverted or empty bounds are rejected before any process`() {
        assertNull(TaskResizeCommand.argvOrNull(1, 500, 0, 100, 100)) // right <= left
        assertNull(TaskResizeCommand.argvOrNull(1, 0, 500, 100, 100)) // bottom <= top
        assertNull(TaskResizeCommand.argvOrNull(1, 100, 100, 100, 200)) // empty width
        assertNull(TaskResizeCommand.argvOrNull(1, 100, 100, 200, 100)) // empty height
    }

    @Test
    fun `no token ever contains shell metacharacters from integer arguments`() {
        val argv = TaskResizeCommand.argvOrNull(7, -3, -4, 800, 600)!!
        argv.forEach { token ->
            assertEquals(false, token.any { it in " ;|&$`\"'\\\n" })
        }
    }
}
