package uk.mr_biz.fourzones.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import uk.mr_biz.fourzones.geometry.Quadrant
import uk.mr_biz.fourzones.snap.SnapExecutionResult
import uk.mr_biz.fourzones.snap.SnapExecutionState

/**
 * Diagnostic section for delayed one-shot snap EXECUTION. Unlike every prior
 * section this MUTATES the resolved target task. Presentational only: the
 * controller owns all logic; the buttons request a delayed snap and the state
 * arrives precomputed.
 */
@Composable
fun SnapExecutionCard(
    state: SnapExecutionState,
    onRequestSnap: (Quadrant) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Snap execution diagnostics",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "MUTATES the safe target task. Press a quadrant, then switch to the " +
                    "intended app within 5 s. The target is captured at timer fire, not " +
                    "at press.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onRequestSnap(Quadrant.TOP_LEFT) }) { Text("TL in 5 s") }
                Button(onClick = { onRequestSnap(Quadrant.TOP_RIGHT) }) { Text("TR in 5 s") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onRequestSnap(Quadrant.BOTTOM_LEFT) }) { Text("BL in 5 s") }
                Button(onClick = { onRequestSnap(Quadrant.BOTTOM_RIGHT) }) { Text("BR in 5 s") }
            }
            Text("State: ${describeState(state)}")
            if (state is SnapExecutionState.Completed) {
                ResultBody(state.result)
            }
        }
    }
}

@Composable
private fun ResultBody(result: SnapExecutionResult) {
    Text(
        text = "Result: ${describeResult(result)}",
        style = MaterialTheme.typography.bodyMedium,
    )
    when (result) {
        is SnapExecutionResult.AppliedAndVerified -> {
            Text("  Quadrant: ${result.quadrant}")
            Text("  Target: ${result.componentName ?: result.packageName ?: "?"}")
            Text("  Display: ${result.displayId}")
            Text("  Requested = observed bounds: ${result.bounds}")
            Text("  Task (ephemeral): ${result.taskId}")
        }
        is SnapExecutionResult.PostconditionMismatch -> {
            Text("  Quadrant: ${result.quadrant}, display: ${result.displayId}")
            Text("  Requested bounds: ${result.requested}")
            Text("  Observed bounds: ${result.observed ?: "task no longer present"}")
            Text("  Task (ephemeral): ${result.taskId}")
        }
        else -> Unit
    }
}

private fun describeState(state: SnapExecutionState): String = when (state) {
    SnapExecutionState.Idle -> "idle"
    is SnapExecutionState.Pending -> "pending (${state.quadrant} in 5 s)"
    is SnapExecutionState.Executing -> "executing (${state.quadrant})"
    is SnapExecutionState.Completed -> "completed"
}

private fun describeResult(result: SnapExecutionResult): String = when (result) {
    is SnapExecutionResult.AppliedAndVerified -> "APPLIED_AND_VERIFIED"
    is SnapExecutionResult.NoTarget -> "NO_TARGET: ${result.reason}"
    is SnapExecutionResult.Cancelled -> "CANCELLED (superseded/stopped before mutation)"
    is SnapExecutionResult.GeometryUnavailable -> "GEOMETRY_UNAVAILABLE: ${result.reason}"
    is SnapExecutionResult.PreconditionChanged -> "PRECONDITION_CHANGED: ${result.reason}"
    is SnapExecutionResult.PrivilegeUnavailable -> "PRIVILEGE_UNAVAILABLE: ${result.status}"
    is SnapExecutionResult.TopologyUnavailable -> "TOPOLOGY_UNAVAILABLE: ${result.reason}"
    is SnapExecutionResult.InvalidDestination -> "INVALID_DESTINATION: ${result.reason}"
    is SnapExecutionResult.CommandFailed -> "COMMAND_FAILED: ${result.reason}"
    is SnapExecutionResult.CommandTimedOut -> "COMMAND_TIMED_OUT"
    is SnapExecutionResult.PostconditionUnavailable -> "POSTCONDITION_UNAVAILABLE: ${result.reason}"
    is SnapExecutionResult.PostconditionMismatch -> "POSTCONDITION_MISMATCH"
}
