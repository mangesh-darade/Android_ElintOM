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

    /**
     * WebView scroll/content height is in CSS pixels. Multiply by the WebView
     * scale so the bitmap matches the laid-out bill, not the device density.
     */
    fun documentHeight(scrollHeight: Int, contentHeight: Int, scale: Float): Int {
        val safeScale = if (scale.isFinite() && scale > 0.05f) scale else 1f
        val px = (maxOf(scrollHeight, contentHeight).coerceAtLeast(0) * safeScale).toInt()
        return px.coerceIn(0, MAX_HEIGHT_PX)
    }

    /**
     * Draw at the Android paper width. 80mm from the boot printer setup is
     * 576 dots, so one CSS pixel is one printer dot and the bill fills the roll.
     * The web bill width is only the on-screen preview.
     */
    fun layoutWidthForPaper(html: String, paperDots: Int): Int {
        if (html.isEmpty()) return paperDots.coerceIn(384, 832)
        return paperDots.coerceIn(384, 832)
    }

    /**
     * Web printer font is in points. On 203 dpi, 80mm paper, 12pt is about 26 dots.
     * 58mm uses a slightly smaller dot size so five columns still fit.
     */
    fun bodyPx(fontPt: Int, paperDots: Int): Int {
        val pt = fontPt.coerceIn(8, 18)
        val dotsPerPt = if (paperDots >= 500) 2.8f else 1.85f
        return (pt * dotsPerPt).toInt().coerceIn(18, 42)
    }

    fun readyToCapture(height: Int, stableCount: Int, attempt: Int, imagesPending: Boolean): Boolean {
        if (height < MIN_HEIGHT_PX) return false
        val imagesReady = !imagesPending || attempt >= 18
        if (!imagesReady) return false
        if (stableCount >= STABLE_READS && attempt >= 4) return true
        return attempt >= 40
    }
}
