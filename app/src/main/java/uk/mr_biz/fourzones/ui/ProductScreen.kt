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
import androidx.compose.ui.unit.dp
import uk.mr_biz.fourzones.product.ProductReadiness
import uk.mr_biz.fourzones.product.ProductSetupContent
import uk.mr_biz.fourzones.product.SetupActionKind
import uk.mr_biz.fourzones.product.productBanner
import uk.mr_biz.fourzones.product.productSetupContent
import uk.mr_biz.fourzones.product.shortcutZoneReference

/**
 * The 4Zones product home screen: readiness banner, setup/recovery card (only
 * when applicable), the zone shortcut reference, and a secondary entry to the
 * developer diagnostics. Purely presentational: readiness is derived by the
 * caller (via productReadiness over the shortcut-owned backend store), and
 * every action is a hoisted callback — recomposition starts/stops nothing.
 */
@Composable
fun ProductScreen(
    readiness: ProductReadiness,
    onEnableShortcuts: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenDiagnostics: () -> Unit,
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
                text = "Bring keyboard zone snapping to Samsung DeX.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        ReadinessBanner(readiness)
        productSetupContent(readiness)?.let { content ->
            SetupCard(
                content = content,
                onEnableShortcuts = onEnableShortcuts,
                onRequestPermission = onRequestPermission,
            )
        }
        Text(text = "Keyboard shortcuts", style = MaterialTheme.typography.titleMedium)
        ShortcutZoneGrid()
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
private fun ShortcutZoneGrid(modifier: Modifier = Modifier) {
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
                    Card(modifier = Modifier.weight(1f)) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(text = zone.zoneLabel, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = zone.positionLabel,
                                style = MaterialTheme.typography.bodyMedium,
                            )
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
