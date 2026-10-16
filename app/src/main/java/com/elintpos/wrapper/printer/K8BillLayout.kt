package com.elintpos.wrapper.printer

/**
 * Pure layout decisions for a K8 bill bitmap.
 * The WebView capture uses these so a zero or one-line height is not treated as a finished bill.
 */
object K8BillLayout {
    const val MAX_HEIGHT_PX = 16000
    const val MIN_HEIGHT_PX = 24
    const val STABLE_READS = 3

    fun heightPx(measuredHeight: Int, contentHeight: Int, density: Float): Int {
        val safeDensity = if (density > 0f) density else 1f
        val fromCss = (contentHeight.coerceAtLeast(0) * safeDensity).toInt()
        return maxOf(measuredHeight, fromCss, contentHeight, 0).coerceAtMost(MAX_HEIGHT_PX)
    }

    fun nextStableCount(previousHeight: Int, currentHeight: Int, stableCount: Int): Int {
        if (currentHeight >= MIN_HEIGHT_PX && currentHeight == previousHeight) {
            return stableCount + 1
        }
        return 0
    }
}
