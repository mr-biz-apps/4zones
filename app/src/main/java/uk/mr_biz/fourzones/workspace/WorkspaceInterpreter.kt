package uk.mr_biz.fourzones.workspace

/**
 * Pure, conservative interpretation of a [WorkspaceState]. No framework types,
 * so it is unit-testable on the JVM.
 *
 * Ground rules, matching the empirical Phase 1 findings:
 *  - Nothing here ever claims desktop/DeX is ACTIVE; the strongest possible
 *    verdict is "evidence present".
 *  - isInMultiWindowMode alone is never desktop evidence beyond
 *    "consistent with": split-screen is also multi-window.
 *  - FEATURE_FREEFORM_WINDOW_MANAGEMENT is device capability, never
 *    current-mode evidence.
 *  - current != maximum bounds is evidence of a restricted window, not of
 *    desktop mode.
 *  - UI_MODE_TYPE_DESK is recorded as itself, not translated into "DeX".
 *  - The hosting display ID is data only and must not influence the verdict.
 */
object WorkspaceInterpreter {

    fun interpret(state: WorkspaceState): WorkspaceInterpretation {
        val evidence = mutableListOf<String>()
        var signalsPresent = 0

        if (state.uiModeType == UiModeType.DESK) {
            signalsPresent++
            evidence += "Android reports UI_MODE_TYPE_DESK: an explicit desk-mode signal, " +
                "recorded as-is and not equated with Samsung DeX or any vendor desktop mode."
        } else {
            evidence += "UI mode is ${state.uiModeType}, not DESK."
        }

        if (state.isInPictureInPictureMode) {
            // PiP is itself a multi-window state with shrunken bounds, so it
            // fully explains those signals; they carry no desktop weight here.
            evidence += "Activity is in picture-in-picture mode; multi-window and " +
                "reduced-bounds signals are explained by PiP and are discounted."
        } else {
            if (state.isInMultiWindowMode) {
                signalsPresent++
                evidence += "Activity is in multi-window mode: consistent with a desktop-style " +
                    "workspace but NOT proof of one — split-screen is also multi-window."
            }
            if (state.currentDiffersFromMaximum) {
                signalsPresent++
                evidence += "Current window bounds ${state.currentBounds} differ from maximum " +
                    "${state.maximumBounds}: evidence of a non-maximized/restricted window, " +
                    "not proof of desktop mode."
            }
        }

        evidence += if (state.supportsFreeformWindowManagement) {
            "Device declares FEATURE_FREEFORM_WINDOW_MANAGEMENT: a device capability only; " +
                "it says nothing about the currently active windowing mode."
        } else {
            "Device does not declare FEATURE_FREEFORM_WINDOW_MANAGEMENT."
        }

        val assessment = if (signalsPresent > 0) {
            WorkspaceAssessment.EVIDENCE_PRESENT
        } else {
            evidence += "No observed public signal distinguishes this environment from a " +
                "plain fullscreen Activity."
            WorkspaceAssessment.UNDETERMINED
        }

        return WorkspaceInterpretation(assessment = assessment, evidence = evidence)
    }
}
