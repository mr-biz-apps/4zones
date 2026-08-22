package uk.mr_biz.fourzones.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import uk.mr_biz.fourzones.workspace.WorkspaceSnapshot

/**
 * Diagnostic card for the Activity's workspace/windowing state. Purely
 * presentational and deliberately unpolished, mirroring the display cards.
 *
 * @param hostingDisplayCorrelation optional UI-layer correlation of the
 * hosting display ID against Phase 1 display discovery, e.g. the matched
 * display's name. Computed by the caller so the workspace subsystem itself
 * stays decoupled from display classification.
 */
@Composable
fun WorkspaceDiagnosticsCard(
    snapshot: WorkspaceSnapshot,
    hostingDisplayCorrelation: String?,
    modifier: Modifier = Modifier,
) {
    val s = snapshot.state
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                "Hosting display: ${s.hostingDisplayId ?: "not attached"}" +
                    (hostingDisplayCorrelation?.let { " ($it)" } ?: ""),
            )
            Text("Multi-window: ${s.isInMultiWindowMode}")
            Text("PiP: ${s.isInPictureInPictureMode}")
            Text("Freeform feature (device capability): ${s.supportsFreeformWindowManagement}")
            Text("UI mode: ${s.uiModeType}")
            Text("Current window bounds: ${s.currentBounds}")
            Text("Maximum window bounds: ${s.maximumBounds}")
            Text("Current != maximum: ${s.currentDiffersFromMaximum}")
            Text("Status bar insets: ${s.insets.statusBars}")
            Text("Navigation bar insets: ${s.insets.navigationBars}")
            Text("System bar insets: ${s.insets.systemBars}")
            Text("Display cutout insets: ${s.insets.displayCutout}")
            Text(
                "Configuration: ${s.configuration.screenWidthDp} x " +
                    "${s.configuration.screenHeightDp} dp, " +
                    "smallest ${s.configuration.smallestScreenWidthDp} dp, " +
                    "${s.configuration.orientation}",
            )
            Text(
                text = "Assessment: ${snapshot.interpretation.assessment}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Evidence:",
                style = MaterialTheme.typography.labelMedium,
            )
            snapshot.interpretation.evidence.forEach { line ->
                Text(
                    text = "• $line",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
