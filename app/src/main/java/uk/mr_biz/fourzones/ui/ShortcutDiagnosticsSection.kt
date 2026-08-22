package uk.mr_biz.fourzones.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import uk.mr_biz.fourzones.privileged.PrivilegedBackendStatus
import uk.mr_biz.fourzones.shortcut.ShortcutObservation
import uk.mr_biz.fourzones.shortcut.ShortcutSnapResultDiagnostic

/**
 * Diagnostic card for the global shortcut path. Shows whether the shortcut
 * AccessibilityService is enabled, the AccessibilityService-owned backend's own
 * live status (the one that drives product readiness — distinct from the
 * Activity backend), a button to open system Accessibility settings (never
 * enables it programmatically), the most recent matched chord, and the most
 * recent snap outcome. IDs are never exposed as product policy.
 */
@Composable
fun ShortcutDiagnosticsCard(
    serviceEnabled: Boolean,
    shortcutBackendStatus: PrivilegedBackendStatus?,
    lastShortcut: ShortcutObservation?,
    lastSnapResult: ShortcutSnapResultDiagnostic?,
    onOpenAccessibilitySettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Shortcut service: ${if (serviceEnabled) "ENABLED" else "NOT ENABLED"}")
            Text(
                DiagnosticsLabels.SHORTCUT_BACKEND_CONTEXT,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(DiagnosticsLabels.shortcutBackendLabel(shortcutBackendStatus))
            Text(
                "Alt + Windows + 1–4 → snap the focused DeX window to a quadrant " +
                    "(TL/TR/BL/BR) via the validated snap transaction.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!serviceEnabled) {
                Button(onClick = onOpenAccessibilitySettings) {
                    Text("Open Accessibility settings")
                }
            }
            val last = lastShortcut
            if (last != null) {
                Text(
                    "Last shortcut: Alt+Windows+${last.candidate.name} → " +
                        "${last.candidate.intendedMeaning} (monotonic ${last.monotonicMillis} ms)",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text("No shortcut observed yet.", style = MaterialTheme.typography.bodySmall)
            }
            val snap = lastSnapResult
            if (snap != null) {
                Text(
                    "Last snap result: ${snap.quadrant} → ${snap.label}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
