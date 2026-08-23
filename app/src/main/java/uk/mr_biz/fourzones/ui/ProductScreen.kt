package uk.mr_biz.fourzones.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import uk.mr_biz.fourzones.geometry.Quadrant
import uk.mr_biz.fourzones.product.ProductCopy
import uk.mr_biz.fourzones.product.ProductReadiness
import uk.mr_biz.fourzones.product.ProductSetupContent
import uk.mr_biz.fourzones.product.SetupActionKind
import uk.mr_biz.fourzones.product.productBanner
import uk.mr_biz.fourzones.product.productSetupContent
import uk.mr_biz.fourzones.product.shortcutZoneReference

/**
 * The 4Zones product home screen: the tappable zone grid FIRST, then the
 * optional keyboard section (readiness banner + setup/recovery card), then a
 * secondary entry to the developer diagnostics. Purely presentational:
 * readiness is derived by the caller (via productReadiness over the
 * shortcut-owned backend store), and every action is a hoisted callback —
 * recomposition starts/stops nothing.
 *
 * [snapEnabled], [snapDisabledReason], [snapFeedback] and [onRequestSnap] are
 * the tap-to-snap path. It is INDEPENDENT of [readiness] on purpose: tapping a
 * zone works with the accessibility service switched off, so its enablement is
 * derived by the caller from the privileged backend alone.
 */
@Composable
fun ProductScreen(
    readiness: ProductReadiness,
    onEnableShortcuts: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    snapEnabled: Boolean,
    snapDisabledReason: String?,
    snapFeedback: String?,
    onRequestSnap: (Quadrant) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = "4Zones", style = MaterialTheme.typography.headlineMedium)
            Text(
                text = ProductCopy.SUBTITLE,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        // ── Tapping: the PRIMARY interface, so it leads the screen. ──
        Text(text = ProductCopy.ZONES_HEADING, style = MaterialTheme.typography.titleMedium)
        Text(
            text = ProductCopy.TAP_INSTRUCTION,
            style = MaterialTheme.typography.bodyMedium,
        )
        ShortcutZoneGrid(
            snapEnabled = snapEnabled,
            onRequestSnap = onRequestSnap,
        )
        // A disabled grid always says WHY; a tap that silently does nothing
        // reads as a broken app.
        if (!snapEnabled && snapDisabledReason != null) {
            Text(
                text = snapDisabledReason,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (snapFeedback != null) {
            Text(text = snapFeedback, style = MaterialTheme.typography.bodyMedium)
        }
        // ── The keyboard: optional, and therefore LAST. ──
        //
        // ReadinessBanner belongs to this section, not to the top of the screen.
        // productReadiness is derived from the SHORTCUT-owned backend and its
        // READY string reads "Keyboard shortcuts ready" — it has never described
        // the tap path, so leading with it would head the screen with a status
        // line about the demoted interface. Composition order only: no logic
        // changes, no new state, productReadiness untouched.
        ReadinessBanner(readiness)
        productSetupContent(readiness)?.let { content ->
            SetupCard(
                content = content,
                onEnableShortcuts = onEnableShortcuts,
                onRequestPermission = onRequestPermission,
            )
        }
        TextButton(onClick = onOpenDiagnostics) {
            Text("Developer diagnostics ›")
        }
    }
}

@Composable
private fun ReadinessBanner(readiness: ProductReadiness, modifier: Modifier = Modifier) {
    val prefix = if (readiness == ProductReadiness.READY) "✓ " else ""
    Card(modifier = modifier.fillMaxWidth()) {
        Text(
            text = prefix + productBanner(readiness),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun SetupCard(
    content: ProductSetupContent,
    onEnableShortcuts: () -> Unit,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = content.headline, style = MaterialTheme.typography.titleSmall)
            Text(text = content.body, style = MaterialTheme.typography.bodyMedium)
            val actionKind = content.actionKind
            val actionLabel = content.actionLabel
            if (actionKind != null && actionLabel != null) {
                Button(
                    onClick = when (actionKind) {
                        SetupActionKind.OPEN_ACCESSIBILITY_SETTINGS -> onEnableShortcuts
                        SetupActionKind.REQUEST_SHIZUKU_PERMISSION -> onRequestPermission
                    },
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun ShortcutZoneGrid(
    snapEnabled: Boolean,
    onRequestSnap: (Quadrant) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        shortcutZoneReference.chunked(2).forEach { rowZones ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowZones.forEach { zone ->
                    Card(
                        // The zone's own quadrant — the single source shared with the
                        // keyboard path; the grid holds no digit→quadrant table.
                        onClick = { onRequestSnap(zone.quadrant) },
                        enabled = snapEnabled,
                        modifier = Modifier
                            .weight(1f)
                            // Describes the ACTION, not the heading: "Zone 1" tells a
                            // screen-reader user nothing about what a tap does.
                            .semantics { contentDescription = zone.snapContentDescription },
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(text = zone.zoneLabel, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = zone.positionLabel,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            // Secondary: still true, and it teaches the chord to
                            // anyone who later enables the shortcut service.
                            Text(
                                text = zone.chordLabel,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}
