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
import uk.mr_biz.fourzones.geometry.DesktopWorkAreaAssessment
import uk.mr_biz.fourzones.geometry.Quadrant
import uk.mr_biz.fourzones.geometry.SnapGridPolicy

/**
 * Diagnostic card for per-display workspace geometry. Presentational only;
 * all values arrive precomputed from the geometry layer.
 */
@Composable
fun WorkspaceGeometryCard(
    assessment: DesktopWorkAreaAssessment,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "Workspace geometry — display ${assessment.displayId}",
                style = MaterialTheme.typography.titleSmall,
            )
            when (assessment) {
                is DesktopWorkAreaAssessment.Found -> FoundBody(assessment)
                is DesktopWorkAreaAssessment.Invalid ->
                    Text("Assessment: INVALID — ${assessment.reason}")
                is DesktopWorkAreaAssessment.Unsupported ->
                    Text("Assessment: UNSUPPORTED — ${assessment.reason}")
            }
            Text(
                text = "Coordinates are dynamically derived; display/task IDs are " +
                    "not geometry constants.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun FoundBody(found: DesktopWorkAreaAssessment.Found) {
    val r = found.reading
    Text("Maximum bounds: ${r.maximumBounds}")
    Text("Status bars: ${r.statusBarInsets}")
    Text("Navigation bars: ${r.navigationBarInsets}")
    Text("Caption bar (task-local evidence, not in mask): ${r.captionBarInsets}")
    Text("System bars (raw combined, diagnostic only): ${r.systemBarInsets}")
    Text("System overlays: ${r.systemOverlayInsets}")
    Text("Display cutout: ${r.displayCutoutInsets}")
    Text("Snap workspace insets: ${found.snapWorkspaceInsets}")
    Text("Snap workspace: ${found.snapWorkspace}")
    Text("Density: ${r.densityDpi} dpi / scale ${r.densityScale}")
    Text("Grid gap policy: ${SnapGridPolicy.GRID_GAP_DP} dp -> ${found.resolvedGapPx} px")
    Text(
        text = "Destination quadrants:",
        style = MaterialTheme.typography.labelMedium,
    )
    Text("  TL: ${found.destinationQuadrants[Quadrant.TOP_LEFT]}")
    Text("  TR: ${found.destinationQuadrants[Quadrant.TOP_RIGHT]}")
    Text("  BL: ${found.destinationQuadrants[Quadrant.BOTTOM_LEFT]}")
    Text("  BR: ${found.destinationQuadrants[Quadrant.BOTTOM_RIGHT]}")
    Text(
        text = "Assessment: FOUND",
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        text = "Applied mask = status ∪ navigation ∪ overlays ∪ cutout; raw " +
            "systemBars is combined diagnostic evidence, not the applied mask. " +
            "Horizontal 8 dp spacing was validated against Samsung native DeX " +
            "half-snap on A9 standalone DeX (12 px @ 240 dpi) and S25 external DeX " +
            "(8 px @ 160 dpi). Vertical use of the same 8 dp gap is a 4Zones " +
            "symmetric-grid policy, not a directly observed Samsung top/bottom " +
            "snap rule. Caption treatment (task-local, outside the mask) is " +
            "correlated against those tested configurations only.",
        style = MaterialTheme.typography.bodySmall,
    )
}
