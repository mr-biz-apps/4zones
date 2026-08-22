package uk.mr_biz.fourzones.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import uk.mr_biz.fourzones.R

/**
 * Phase 4D — the accessibility prominent-disclosure + consent dialog. It is shown IN-APP, BEFORE the
 * app ever deep-links the user to system Accessibility settings, on EVERY enable path.
 *
 * The copy is bound VERBATIM from string resources — do NOT reword here. [onAccept] is the affirmative
 * consent
 * gesture (→ record consent + deep-link); [onDecline] dismisses (service stays off; the rest of the
 * app keeps working).
 */
@Composable
fun AccessibilityDisclosureDialog(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text(text = stringResource(R.string.accessibility_disclosure_title)) },
        text = {
            Text(
                text = stringResource(R.string.accessibility_disclosure_body),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text(text = stringResource(R.string.accessibility_disclosure_accept))
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline) {
                Text(text = stringResource(R.string.accessibility_disclosure_decline))
            }
        },
    )
}
