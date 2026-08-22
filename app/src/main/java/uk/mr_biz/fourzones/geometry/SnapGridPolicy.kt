package uk.mr_biz.fourzones.geometry

/**
 * The DexZones snap-grid spacing policy.
 */
object SnapGridPolicy {

    /**
     * Grid gap between snap destinations, in dp.
     *
     * Empirical provenance — NOT a magic number:
     *  - A9 standalone DeX: Samsung native half-snap left/right tasks were
     *    separated by 12 px at 240 dpi (density scale 1.5) → 12 / 1.5 = 8 dp;
     *  - S25 + Qreator27 external DeX: 8 px at 160 dpi (density scale 1.0)
     *    → 8 / 1.0 = 8 dp.
     *
     * The HORIZONTAL use of this value is hardware-validated against Samsung
     * native DeX half-snap on those tested configurations (two independent
     * densities agreeing on 8 dp); it is not claimed universal beyond that
     * evidence. The VERTICAL use of the same value is a DexZones
     * symmetric-grid product policy inferred from the validated horizontal
     * spacing — it is NOT a directly observed Samsung top/bottom snap rule,
     * because no native vertical snap measurement exists.
     *
     * No per-device pixel value (12, 8, ...) is ever hard-coded; pixels are
     * always resolved from this dp value at the target display's density.
     */
    const val GRID_GAP_DP = 8f

    /**
     * Resolves the gap to pixels for a display's logical density scale using
     * standard Android dp→px semantics: px = round(dp × density), rounding
     * half-up (java.lang.Math.round on the widened Double product, matching
     * TypedValue.applyDimension followed by rounding).
     *
     * Fail-closed conversion: the multiplication is performed in Double
     * BEFORE any rounding, and the scaled and rounded results are each
     * range-checked, so a ridiculous-but-finite density can never saturate
     * or wrap into a usable gap. Returns null — never a clamped value —
     * when the density is non-finite/non-positive or the resolved gap is
     * not representable in 0..Int.MAX_VALUE; callers propagate that as an
     * explicit Invalid.
     */
    fun gapPixels(densityScale: Float): Int? {
        if (!densityScale.isFinite() || densityScale <= 0f) return null
        val scaled = GRID_GAP_DP.toDouble() * densityScale.toDouble()
        if (!scaled.isFinite() || scaled < 0.0 || scaled > Int.MAX_VALUE.toDouble()) return null
        val rounded = Math.round(scaled)
        if (rounded < 0L || rounded > Int.MAX_VALUE.toLong()) return null
        return rounded.toInt()
    }
}
