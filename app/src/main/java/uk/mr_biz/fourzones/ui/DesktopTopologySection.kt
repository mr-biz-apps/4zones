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
import uk.mr_biz.fourzones.capture.TargetCaptureResult
import uk.mr_biz.fourzones.desktop.ActiveDesktopAssessment
import uk.mr_biz.fourzones.desktop.DesktopRoot
import uk.mr_biz.fourzones.desktop.DesktopTopologyReader
import uk.mr_biz.fourzones.desktop.DesktopTopologySnapshot
import uk.mr_biz.fourzones.desktop.SnapTargetAssessment
import uk.mr_biz.fourzones.privileged.PrivilegedBackendStatus

/**
 * Diagnostic card for privileged desktop topology. Presentational only: all
 * data arrives via [DesktopTopologyReader.Outcome] and the precomputed
 * [snapTargets]; the UI can trigger the two named actions (request
 * permission, refresh) and nothing else.
 */
@Composable
fun DesktopTopologyCard(
    backendStatus: PrivilegedBackendStatus?,
    outcome: DesktopTopologyReader.Outcome?,
    snapTargets: Map<Int, SnapTargetAssessment>?,
    captureResult: TargetCaptureResult?,
    onRequestPermission: () -> Unit,
    onRefresh: () -> Unit,
    onStartCapture: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                DiagnosticsLabels.ACTIVITY_BACKEND_CONTEXT,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(DiagnosticsLabels.activityBackendLabel(backendStatus))
            if (backendStatus == PrivilegedBackendStatus.UNSUPPORTED_SERVER) {
                // No permission button in this state: the running server
                // generation has no supported permission flow to request.
                Text(
                    "The running Shizuku server version is unsupported (pre-v11). " +
                        "Update the Shizuku app and restart its service.",
                )
            }
            if (backendStatus == PrivilegedBackendStatus.CONNECTING) {
                // Connected but not yet protocol-verified; not usable until READY.
                Text("Verifying the privileged topology service…")
            }
            if (backendStatus == PrivilegedBackendStatus.USER_SERVICE_VERSION_MISMATCH) {
                // A stale privileged service survived an app update; topology is
                // refused (fail closed) until a matching service is bound.
                Text(
                    "A stale privileged topology service from a previous install is " +
                        "still running. Fully close and reopen 4Zones to load the " +
                        "current service.",
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (backendStatus == PrivilegedBackendStatus.PERMISSION_REQUIRED ||
                    backendStatus == PrivilegedBackendStatus.PERMISSION_DENIED
                ) {
                    Button(onClick = onRequestPermission) { Text("Request permission") }
                }
                if (backendStatus == PrivilegedBackendStatus.READY) {
                    Button(onClick = onRefresh) { Text("Refresh topology") }
                    Button(onClick = onStartCapture) { Text("Capture target in 5 s") }
                }
            }
            BackgroundCaptureResult(captureResult)
            when (outcome) {
                null -> Text("No topology read yet.")
                is DesktopTopologyReader.Outcome.BackendUnavailable ->
                    Text("Topology unavailable: backend is ${outcome.status}.")
                is DesktopTopologyReader.Outcome.Failed ->
                    Text("Topology read failed: ${outcome.message}")
                is DesktopTopologyReader.Outcome.Success ->
                    TopologySnapshotBody(outcome.snapshot, snapTargets)
            }
        }
    }
}

@Composable
private fun TopologySnapshotBody(
    snapshot: DesktopTopologySnapshot,
    snapTargets: Map<Int, SnapTargetAssessment>?,
) {
    val support = if (snapshot.roots.isEmpty()) "UNSUPPORTED/UNDETERMINED" else "detected"
    Text("Desk topology support: $support")
    Text("Desk root count: ${snapshot.roots.size}")
    if (snapshot.activeDesktopByDisplay.isEmpty()) {
        Text(
            text = "Active desktop: no display with desk topology",
            style = MaterialTheme.typography.bodyMedium,
        )
    } else {
        snapshot.activeDesktopByDisplay.forEach { (displayId, assessment) ->
            Text(
                text = "Active desktop on display $displayId: " +
                    describeAssessment(assessment),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    Text("Focused task (diagnostic only): ${snapshot.focusedTaskId ?: "none reported"}")
    // Active desktop != focused task. The active desktop is resolved first,
    // from desk-root type/hierarchy/visibility; focus is used only
    // afterwards, to identify the intended task WITHIN that hierarchy.
    Text(
        text = "Target task (active desktop resolved first; focus only identifies " +
            "the task within it):",
        style = MaterialTheme.typography.labelMedium,
    )
    if (snapTargets.isNullOrEmpty()) {
        Text("  No display with an assessable target.")
    } else {
        snapTargets.forEach { (displayId, target) ->
            Text("  Display $displayId: ${describeSnapTarget(target)}")
        }
    }

    if (snapshot.activatableRoots.isNotEmpty()) {
        Text(
            text = "Activatable desk roots:",
            style = MaterialTheme.typography.labelMedium,
        )
        snapshot.activatableRoots.forEach { DeskRootLines(it) }
    }
    if (snapshot.minimizedRoots.isNotEmpty()) {
        Text(
            text = "Minimized desk roots (never active):",
            style = MaterialTheme.typography.labelMedium,
        )
        snapshot.minimizedRoots.forEach { DeskRootLines(it) }
    }
    Text(
        text = "Evidence:",
        style = MaterialTheme.typography.labelMedium,
    )
    snapshot.evidence.forEach { line ->
        Text(text = "• $line", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DeskRootLines(root: DesktopRoot) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            "Root ${root.rootTaskId}: display=${root.displayId ?: "?"}, " +
                "visible=${root.visible ?: "?"}, " +
                "visibleRequested=${root.visibleRequested ?: "?"}, " +
                "organizer=${root.createdByOrganizer ?: "?"}, " +
                "forceHidden=${root.forceHidden ?: "?"}, " +
                "mode=${root.windowingMode ?: "?"}, " +
                "children=${root.childTasks.size}",
        )
        root.childTasks.forEach { task ->
            Text(
                text = "  task ${task.taskId}: ${task.componentName ?: task.packageName ?: "?"}" +
                    ", visible=${task.visible ?: "?"}" +
                    (if (task.focused == true) ", FOCUSED" else ""),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun BackgroundCaptureResult(result: TargetCaptureResult?) {
    Text(
        text = "Background target capture (one-shot):",
        style = MaterialTheme.typography.labelMedium,
    )
    when (result) {
        null -> Text("  No capture performed yet.")
        is TargetCaptureResult.Failed -> Text("  FAILED: ${result.reason}")
        is TargetCaptureResult.Captured ->
            if (result.targets.isEmpty()) {
                Text("  CAPTURED: no display with desk topology.")
            } else {
                result.targets.forEach { (displayId, target) ->
                    Text("  Display $displayId: ${describeSnapTarget(target)}")
                }
            }
    }
}

private fun describeSnapTarget(target: SnapTargetAssessment): String = when (target) {
    is SnapTargetAssessment.Found ->
        "FOUND(task ${target.targetTaskId}, " +
            "${target.componentName ?: target.packageName ?: "component unknown"}, " +
            "in active desk root ${target.activeDeskRootId})"
    is SnapTargetAssessment.NoTarget -> "NO TARGET: ${target.reason}"
    is SnapTargetAssessment.Ambiguous -> "AMBIGUOUS: ${target.reason}"
    is SnapTargetAssessment.Unsupported -> "UNSUPPORTED: ${target.reason}"
}

private fun describeAssessment(assessment: ActiveDesktopAssessment): String = when (assessment) {
    is ActiveDesktopAssessment.Found -> "FOUND (root ${assessment.rootTaskId})"
    is ActiveDesktopAssessment.Ambiguous ->
        "AMBIGUOUS (candidates ${assessment.candidateRootTaskIds})"
    ActiveDesktopAssessment.None -> "NONE"
    ActiveDesktopAssessment.Unsupported -> "UNSUPPORTED"
}
