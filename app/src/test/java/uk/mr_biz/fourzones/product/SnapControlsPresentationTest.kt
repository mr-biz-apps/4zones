package uk.mr_biz.fourzones.product

import uk.mr_biz.fourzones.geometry.GeometryRect
import uk.mr_biz.fourzones.geometry.Quadrant
import uk.mr_biz.fourzones.privileged.PrivilegedBackendStatus
import uk.mr_biz.fourzones.snap.SnapExecutionResult
import uk.mr_biz.fourzones.snap.SnapExecutionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tap-to-snap presentation layer: what enables the zone cards, what a
 * disabled card says, and what one snap tells the user. All pure — the same
 * host-testable shape as [ProductPresentationTest].
 */
class SnapControlsPresentationTest {

    @Test
    fun `only READY enables the zone cards`() {
        assertTrue(snapControlsEnabled(PrivilegedBackendStatus.READY))
        PrivilegedBackendStatus.entries
            .filter { it != PrivilegedBackendStatus.READY }
            .forEach { status ->
                assertFalse("$status must not enable a mutating control", snapControlsEnabled(status))
            }
    }

    @Test
    fun `no observation yet never enables the zone cards`() {
        // Fail conservative: null is "we have not heard from the backend", not "fine".
        assertFalse(snapControlsEnabled(null))
    }

    @Test
    fun `zone-card enablement is independent of the accessibility service`() {
        // The load-bearing property of this feature: tapping a zone needs no
        // accessibility service, so its enablement must not move when the
        // service is off — even though productReadiness collapses to
        // SHORTCUT_SERVICE_DISABLED for EVERY backend value in that case.
        (listOf(null) + PrivilegedBackendStatus.entries).forEach { status ->
            val enabled = snapControlsEnabled(status)

            val readinessWithService = productReadiness(serviceEnabled = true, backendStatus = status)
            val readinessWithoutService =
                productReadiness(serviceEnabled = false, backendStatus = status)
            assertEquals(
                "the readiness model must still be accessibility-dominated for $status",
                ProductReadiness.SHORTCUT_SERVICE_DISABLED,
                readinessWithoutService,
            )

            // Enablement is a function of the backend alone: the two readiness
            // values above differ, the enablement does not.
            assertEquals(
                "tap enablement must not depend on the accessibility service ($status)",
                enabled,
                snapControlsEnabled(status),
            )
            assertEquals(
                "READY backend must enable taps even with shortcuts off ($status)",
                status == PrivilegedBackendStatus.READY,
                enabled,
            )
            // And it is NOT derivable from readiness: with the service off,
            // readiness is identical for a READY and a dead backend, while
            // enablement is not.
            if (status == PrivilegedBackendStatus.READY) {
                assertEquals(ProductReadiness.READY, readinessWithService)
                assertTrue(enabled)
            }
        }
    }

    @Test
    fun `a disabled card always states a reason and a ready one states none`() {
        assertNull(snapDisabledReason(PrivilegedBackendStatus.READY))
        (listOf(null) + PrivilegedBackendStatus.entries).forEach { status ->
            val reason = snapDisabledReason(status)
            if (snapControlsEnabled(status)) {
                assertNull("$status is enabled, so it needs no reason", reason)
            } else {
                assertNotNull("$status must explain itself, not fail silently", reason)
                assertTrue("$status reason must not be blank", reason!!.isNotBlank())
            }
        }
    }

    @Test
    fun `a starting backend is described as transient, not as missing setup`() {
        val starting = "Starting up — zones become tappable in a moment."
        assertEquals(starting, snapDisabledReason(null))
        assertEquals(starting, snapDisabledReason(PrivilegedBackendStatus.CONNECTING))
    }

    @Test
    fun `each disabled state names the recovery action that state actually needs`() {
        assertEquals(
            "Zones need window access. Install Shizuku, start its service, then come back.",
            snapDisabledReason(PrivilegedBackendStatus.NOT_INSTALLED),
        )
        listOf(
            PrivilegedBackendStatus.BINDER_UNAVAILABLE,
            PrivilegedBackendStatus.BINDER_DIED,
        ).forEach { status ->
            assertEquals(
                "Zones need window access. Start Shizuku, then come back.",
                snapDisabledReason(status),
            )
        }
        // The commonest observed failure, and the one a flattened line got
        // wrong: Shizuku IS running, 4Zones is simply not allowed in it, so
        // "Start Shizuku" told the user to do what they had already done.
        listOf(
            PrivilegedBackendStatus.PERMISSION_REQUIRED,
            PrivilegedBackendStatus.PERMISSION_DENIED,
        ).forEach { status ->
            assertEquals(
                "Zones need window access. Allow 4Zones in Shizuku, then come back.",
                snapDisabledReason(status),
            )
        }
        assertEquals(
            "Zones need window access. Update Shizuku, then restart it.",
            snapDisabledReason(PrivilegedBackendStatus.UNSUPPORTED_SERVER),
        )
        assertEquals(
            "Zones need window access. Restart 4Zones.",
            snapDisabledReason(PrivilegedBackendStatus.USER_SERVICE_VERSION_MISMATCH),
        )
    }

    @Test
    fun `two statuses share a reason exactly when they share a recovery state`() {
        // The rule, asserted rather than remembered: this screen may collapse
        // two statuses into one sentence ONLY where productReadiness already
        // collapses them, so the setup card and the disabled reason can never
        // describe one device state differently.
        PrivilegedBackendStatus.entries.forEach { a ->
            PrivilegedBackendStatus.entries.forEach { b ->
                assertEquals(
                    "$a and $b: readiness grouping and reason grouping disagree",
                    productReadiness(serviceEnabled = true, backendStatus = a) ==
                        productReadiness(serviceEnabled = true, backendStatus = b),
                    snapDisabledReason(a) == snapDisabledReason(b),
                )
            }
        }
    }

    @Test
    fun `no disabled reason instructs an action the build cannot offer`() {
        // 0.9.1-beta shipped "or set up direct access" — the unreleased embedded
        // entry point, absent from the release tree. The string travelled even
        // though the button did not, so the app named a door it did not have.
        (listOf(null) + PrivilegedBackendStatus.entries).forEach { status ->
            val reason = snapDisabledReason(status) ?: return@forEach
            assertFalse(
                "\"$reason\" names an entry point the released build has no button for",
                reason.contains("direct access", ignoreCase = true),
            )
        }
    }

    @Test
    fun `idle says nothing and waiting says what to do`() {
        assertNull(snapFeedbackLine(SnapExecutionState.Idle))
        assertEquals(
            "Snapping to top left shortly — bring the window you want to the front.",
            snapFeedbackLine(SnapExecutionState.Pending(Quadrant.TOP_LEFT)),
        )
        assertEquals(
            "Snapping to bottom right shortly — bring the window you want to the front.",
            snapFeedbackLine(SnapExecutionState.Pending(Quadrant.BOTTOM_RIGHT)),
        )
        assertEquals(
            "Moving the window…",
            snapFeedbackLine(SnapExecutionState.Executing(Quadrant.TOP_RIGHT)),
        )
    }

    @Test
    fun `every pending quadrant names its own zone`() {
        Quadrant.entries.forEach { quadrant ->
            val position = shortcutZoneReference.first { it.quadrant == quadrant }
                .positionLabel
                .lowercase()
            assertTrue(
                "pending line for $quadrant must name its zone",
                snapFeedbackLine(SnapExecutionState.Pending(quadrant))!!.contains(position),
            )
        }
    }

    @Test
    fun `a verified snap reports success in one short sentence`() {
        assertEquals(
            "Snapped to top left.",
            snapFeedbackLine(SnapExecutionState.Completed(appliedResult(Quadrant.TOP_LEFT))),
        )
    }

    @Test
    fun `every failure outcome produces one short sentence`() {
        completedFailures().forEach { result ->
            val line = snapFeedbackLine(SnapExecutionState.Completed(result))
            assertNotNull("${result::class.simpleName} must tell the user something", line)
            assertTrue(line!!.isNotBlank())
            assertTrue(
                "${result::class.simpleName} line is not a short sentence: \"$line\"",
                line.length <= 80,
            )
        }
    }

    @Test
    fun `a superseded or stopped snap says nothing`() {
        // The controller never publishes this state (a replacement request
        // publishes its own Pending line), so a "cancelled" flash would be noise.
        assertNull(
            snapFeedbackLine(
                SnapExecutionState.Completed(SnapExecutionResult.Cancelled(Quadrant.TOP_LEFT)),
            ),
        )
    }

    @Test
    fun `feedback never surfaces component names, task ids, bounds or backend status`() {
        val poison = listOf(
            "com.evil.package",
            "com.evil.package/.MainActivity",
            "4321",
            "1920",
            "GeometryRect",
            "BINDER_UNAVAILABLE",
            "PRIVILEGE_UNAVAILABLE",
            "raw-engine-reason",
            "Quadrant",
            "TOP_LEFT",
        )
        val lines = buildList {
            add(appliedResult(Quadrant.TOP_LEFT))
            addAll(completedFailures())
        }.mapNotNull { snapFeedbackLine(SnapExecutionState.Completed(it)) } +
            listOfNotNull(
                snapFeedbackLine(SnapExecutionState.Executing(Quadrant.TOP_LEFT)),
                snapFeedbackLine(SnapExecutionState.Pending(Quadrant.TOP_LEFT)),
            )

        assertTrue(lines.isNotEmpty())
        lines.forEach { line ->
            poison.forEach { leak ->
                assertFalse("\"$line\" leaks \"$leak\"", line.contains(leak))
            }
        }
    }

    @Test
    fun `no line assumes a mouse or prescribes which comes first`() {
        // Focusing the window first and tapping second works exactly as well as
        // the reverse, and on a device with no external monitor focus-first is
        // the only order there is. "Click the window" assumed a mouse AND a
        // second display; three separate lines said it.
        val lines = buildList {
            add(ProductCopy.TAP_INSTRUCTION)
            (listOf(null) + PrivilegedBackendStatus.entries).forEach { status ->
                snapDisabledReason(status)?.let(::add)
            }
            Quadrant.entries.forEach { quadrant ->
                add(snapFeedbackLine(SnapExecutionState.Pending(quadrant))!!)
                add(snapFeedbackLine(SnapExecutionState.Executing(quadrant))!!)
            }
            addAll(
                (listOf(appliedResult(Quadrant.TOP_LEFT)) + completedFailures())
                    .mapNotNull { snapFeedbackLine(SnapExecutionState.Completed(it)) },
            )
        }
        assertTrue(lines.isNotEmpty())
        lines.forEach { line ->
            assertFalse("\"$line\" assumes a mouse", line.contains("click", ignoreCase = true))
        }
    }

    private fun appliedResult(quadrant: Quadrant) = SnapExecutionResult.AppliedAndVerified(
        quadrant = quadrant,
        displayId = 1920,
        taskId = 4321,
        packageName = "com.evil.package",
        componentName = "com.evil.package/.MainActivity",
        bounds = GeometryRect(0, 0, 1920, 1080),
    )

    /**
     * Every non-success, non-cancelled outcome, each carrying poison values in
     * its diagnostic fields so a leak fails a test rather than reaching a user.
     */
    private fun completedFailures(): List<SnapExecutionResult> {
        val quadrant = Quadrant.BOTTOM_RIGHT
        val reason = "raw-engine-reason com.evil.package/.MainActivity"
        return listOf(
            SnapExecutionResult.NoTarget(quadrant, reason),
            SnapExecutionResult.GeometryUnavailable(quadrant, reason),
            SnapExecutionResult.PreconditionChanged(quadrant, reason),
            SnapExecutionResult.PrivilegeUnavailable(
                quadrant,
                PrivilegedBackendStatus.BINDER_UNAVAILABLE,
            ),
            SnapExecutionResult.TopologyUnavailable(quadrant, reason),
            SnapExecutionResult.InvalidDestination(quadrant, reason),
            SnapExecutionResult.CommandFailed(quadrant, reason),
            SnapExecutionResult.CommandTimedOut(quadrant),
            SnapExecutionResult.PostconditionUnavailable(quadrant, reason),
            SnapExecutionResult.PostconditionMismatch(
                quadrant = quadrant,
                displayId = 1920,
                taskId = 4321,
                requested = GeometryRect(0, 0, 1920, 1080),
                observed = GeometryRect(0, 0, 960, 540),
                reason = reason,
            ),
        )
    }
}
