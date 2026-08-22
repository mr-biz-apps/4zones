package uk.mr_biz.fourzones.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import uk.mr_biz.fourzones.capture.TargetCaptureResult
import uk.mr_biz.fourzones.desktop.DesktopTopologyReader
import uk.mr_biz.fourzones.desktop.SnapTargetAssessment
import uk.mr_biz.fourzones.display.DisplayInfo
import uk.mr_biz.fourzones.geometry.DesktopWorkAreaAssessment
import uk.mr_biz.fourzones.geometry.Quadrant
import uk.mr_biz.fourzones.shortcut.ShortcutObservation
import uk.mr_biz.fourzones.shortcut.ShortcutSnapResultDiagnostic
import uk.mr_biz.fourzones.snap.SnapExecutionState
import uk.mr_biz.fourzones.privileged.PrivilegedBackendStatus
import uk.mr_biz.fourzones.workspace.WorkspaceSnapshot

/**
 * Minimal diagnostic screen: lists every discovered display with its raw
 * properties and classification evidence, followed by separate sections for
 * the Activity's workspace/windowing state and privileged desktop topology.
 * Not a polished UI by design.
 */
@Composable
fun DisplayDiagnosticsScreen(
    displays: List<DisplayInfo>,
    workspace: WorkspaceSnapshot?,
    topologyBackendStatus: PrivilegedBackendStatus?,
    topologyOutcome: DesktopTopologyReader.Outcome?,
    snapTargets: Map<Int, SnapTargetAssessment>?,
    captureResult: TargetCaptureResult?,
    workspaceGeometry: List<DesktopWorkAreaAssessment>,
    snapExecutionState: SnapExecutionState,
    shortcutServiceEnabled: Boolean,
    shortcutBackendStatus: PrivilegedBackendStatus?,
    lastShortcut: ShortcutObservation?,
    lastSnapResult: ShortcutSnapResultDiagnostic?,
    onRequestTopologyPermission: () -> Unit,
    onRefreshTopology: () -> Unit,
    onStartTargetCapture: () -> Unit,
    onRequestSnap: (Quadrant) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = DiagnosticsLabels.SCREEN_HEADING,
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = DiagnosticsLabels.SCREEN_SUBTEXT,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        item {
            Text(
                text = "4Zones display diagnostics (${displays.size} display(s))",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        items(displays, key = { it.properties.displayId }) { info ->
            DisplayCard(info)
        }
        if (displays.isEmpty()) {
            item { Text("No displays reported yet.") }
        }
        item {
            Text(
                text = "Workspace diagnostics",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        if (workspace != null) {
            item {
                WorkspaceDiagnosticsCard(
                    snapshot = workspace,
                    // UI-only diagnostic correlation with Phase 1 discovery;
                    // the workspace subsystem never depends on this.
                    hostingDisplayCorrelation = displays
                        .firstOrNull { it.properties.displayId == workspace.state.hostingDisplayId }
                        ?.let { "discovered display: \"${it.properties.name}\"" },
                )
            }
        } else {
            item { Text("No workspace state captured yet.") }
        }
        item {
            Text(
                text = "Privileged desktop topology",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        item {
            DesktopTopologyCard(
                backendStatus = topologyBackendStatus,
                outcome = topologyOutcome,
                snapTargets = snapTargets,
                captureResult = captureResult,
                onRequestPermission = onRequestTopologyPermission,
                onRefresh = onRefreshTopology,
                onStartCapture = onStartTargetCapture,
            )
        }
        item {
            Text(
                text = "Workspace geometry",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        items(workspaceGeometry, key = { "geometry-${it.displayId}" }) { assessment ->
            WorkspaceGeometryCard(assessment)
        }
        if (workspaceGeometry.isEmpty()) {
            item { Text("No display geometry derived yet.") }
        }
        item {
            Text(
                text = "Snap execution (mutating)",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        item {
            SnapExecutionCard(
                state = snapExecutionState,
                onRequestSnap = onRequestSnap,
            )
        }
        item {
            Text(
                text = DiagnosticsLabels.SECTION_SHORTCUTS,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        item {
            ShortcutDiagnosticsCard(
                serviceEnabled = shortcutServiceEnabled,
                shortcutBackendStatus = shortcutBackendStatus,
                lastShortcut = lastShortcut,
                lastSnapResult = lastSnapResult,
                onOpenAccessibilitySettings = onOpenAccessibilitySettings,
            )
        }
    }
}

@Composable
private fun DisplayCard(info: DisplayInfo, modifier: Modifier = Modifier) {
    val p = info.properties
    val c = info.classification
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "Display ${p.displayId}: ${p.name}",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "Mode (physical px): ${p.physicalWidthPx} x ${p.physicalHeightPx}, " +
                    "rotation ${p.rotation.degrees}°",
            )
            Text("Refresh: ${p.refreshRateHz} Hz, density: ${p.densityDpi} dpi")
            Text("Flags: ${p.flags.ifEmpty { "none" }}")
            Text("Default display: ${p.isDefaultDisplay}")
            Text("Presentation category: ${p.inPresentationCategory}")
            Text("isInternal (API 36.1+): ${p.isInternal ?: "unavailable"}")
            Text(
                text = buildString {
                    append("Origin: ${c.origin}, ")
                    append("presentation-capable: ${c.isPresentationCapable}, ")
                    append("private: ${c.isPrivate}, ")
                    append("desktop capability: ${c.desktopCapability}")
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Evidence:",
                style = MaterialTheme.typography.labelMedium,
            )
            c.evidence.forEach { line ->
                Text(
                    text = "• $line",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
