package uk.mr_biz.fourzones.display

/**
 * Pure classification logic. Works only on [DisplayProperties]; never touches
 * Android framework objects, so it is fully unit-testable on the JVM.
 *
 * Classification is strictly capability-based: it never keys off display IDs,
 * resolutions, or device/manufacturer identity. Independent concepts are
 * derived separately, never conflated:
 *
 *  - origin (built-in / non-internal / undetermined), from Display.isInternal()
 *    where available — never inferred from presentation capability, because
 *    built-in displays may themselves be presentation-capable;
 *  - presentation capability, from FLAG_PRESENTATION / presentation category;
 *  - privacy, which limits usability but never erases presentation capability;
 *  - desktop-workspace capability (tri-state; presentation APIs are never
 *    treated as proof of desktop mode, and lack of evidence stays "unknown").
 */
object DisplayClassifier {

    fun classify(properties: DisplayProperties): DisplayClassification {
        val evidence = mutableListOf<String>()

        val origin = when (properties.isInternal) {
            true -> {
                evidence += "Display.isInternal() reports an internal (built-in) display."
                DisplayOrigin.BUILT_IN
            }
            false -> {
                evidence += "Display.isInternal() reports a non-internal display " +
                    "(wired, wireless, overlay, or virtual — public APIs do not say which)."
                DisplayOrigin.NON_INTERNAL
            }
            null -> {
                evidence += "Display.isInternal() unavailable on this API level " +
                    "(pre-36.1); origin undetermined."
                DisplayOrigin.UNDETERMINED
            }
        }

        evidence += if (properties.isDefaultDisplay) {
            "This is the default display."
        } else {
            "Not the default display."
        }

        val hasPresentationFlag = DisplayFlag.PRESENTATION in properties.flags
        val isPrivate = DisplayFlag.PRIVATE in properties.flags
        if (hasPresentationFlag) evidence += "FLAG_PRESENTATION is set."
        if (properties.inPresentationCategory) {
            evidence += "Listed under DISPLAY_CATEGORY_PRESENTATION."
        }

        // Presentation capability is independent of privacy: a private display
        // is still presentation-capable hardware/surface-wise, it is just not
        // usable by other apps.
        val isPresentationCapable = hasPresentationFlag || properties.inPresentationCategory
        if (isPresentationCapable && origin == DisplayOrigin.UNDETERMINED) {
            evidence += "Presentation capability does not establish origin: " +
                "built-in displays may also be presentation-capable."
        }
        if (isPrivate) evidence += "FLAG_PRIVATE is set; not usable by other apps."

        val hasUsableSize = properties.physicalWidthPx > 0 && properties.physicalHeightPx > 0
        if (!hasUsableSize) evidence += "No usable reported mode size."

        val desktopCapability = when {
            isPrivate -> {
                evidence += "Not a desktop candidate: a private display cannot host " +
                    "other apps' windows, regardless of its presentation capability."
                DesktopCapability.NOT_A_CANDIDATE
            }
            !hasUsableSize -> {
                evidence += "Not a desktop candidate: no usable reported size."
                DesktopCapability.NOT_A_CANDIDATE
            }
            origin == DisplayOrigin.BUILT_IN -> {
                evidence += "Desktop capability undetermined: public APIs in this " +
                    "milestone cannot show whether a built-in display hosts a " +
                    "desktop/freeform workspace (e.g. standalone desktop mode); " +
                    "not ruled out."
                DesktopCapability.UNDETERMINED
            }
            properties.isDefaultDisplay -> {
                evidence += "Desktop capability undetermined: default display with " +
                    "unknown origin; not ruled out."
                DesktopCapability.UNDETERMINED
            }
            isPresentationCapable -> {
                evidence += "Desktop candidate: non-default, presentation-capable, " +
                    "usable size, origin $origin (candidacy, not proof of desktop mode)."
                DesktopCapability.CANDIDATE
            }
            else -> {
                evidence += "Desktop capability undetermined: no public presentation " +
                    "evidence for this secondary display; not assumed desktop-capable."
                DesktopCapability.UNDETERMINED
            }
        }

        return DisplayClassification(
            origin = origin,
            isPresentationCapable = isPresentationCapable,
            isPrivate = isPrivate,
            desktopCapability = desktopCapability,
            evidence = evidence,
        )
    }
}
