package com.elintpos.wrapper.printer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Renders a TecMI K8 bill as a bitmap so the printer does not have to
 * encode the text as GBK. ₹ and Devanagari survive this path.
 *
 * The WebView is attached off-screen. An unattached WebView often reports
 * content height 0 and the printer would feed a blank slip.
 */
object K8BillBitmap {

    private const val TAG = "K8BillBitmap"
    private const val RENDER_TIMEOUT_SEC = 12L
    private const val POLL_MS = 200L
    private const val MAX_POLLS = 30

    fun renderText(text: String, widthPx: Int): Bitmap {
        val width = widthPx.coerceAtLeast(8)
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 24f
            typeface = Typeface.DEFAULT
        }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(4f, 1f)
            .build()
        val height = (layout.height + 24).coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.translate(0f, 8f)
        layout.draw(canvas)
        if (!hasInk(bitmap)) {
            bitmap.recycle()
            throw IllegalStateException("K8 text bitmap was blank")
        }
        return bitmap
    }

    fun renderRows(rows: List<ReceiptLayout.Row>, widthPx: Int): Bitmap {
        val width = widthPx.coerceAtLeast(8)
        val margin = (width * 0.025f).toInt().coerceAtLeast(8)
        val content = width - margin * 2
        val scale = width / 576f
        val measured = measureRows(rows, content, scale)
        val bitmap = Bitmap.createBitmap(width, measured.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        drawRows(canvas, rows, margin, content, scale)
        if (!hasInk(bitmap)) {
            bitmap.recycle()
            throw IllegalStateException("K8 receipt bitmap was blank")
        }
        return bitmap
    }

    private data class Measured(val height: Int)

    private fun measureRows(rows: List<ReceiptLayout.Row>, content: Int, scale: Float): Measured {
        var y = (12 * scale).toInt().coerceAtLeast(8)
        for (row in rows) {
            y += rowHeight(row, content, scale) + (8 * scale).toInt()
        }
        return Measured((y + 24).coerceAtLeast(1))
    }

    private fun drawRows(
        canvas: Canvas,
        rows: List<ReceiptLayout.Row>,
        margin: Int,
        content: Int,
        scale: Float
    ) {
        var y = (12 * scale).toInt().coerceAtLeast(8).toFloat()
        for (row in rows) {
            y += drawRow(canvas, row, margin.toFloat(), y, content, scale)
            y += 8 * scale
        }
    }

    private fun rowHeight(row: ReceiptLayout.Row, content: Int, scale: Float): Int {
        return when (row) {
            is ReceiptLayout.Row.Center -> {
                val paint = textPaint(if (row.large) 28f else 20f, scale, row.bold)
                wrap(row.text, paint, content.toFloat()).size * lineHeight(paint)
            }
            is ReceiptLayout.Row.Left -> {
                val paint = textPaint(18f, scale, row.bold)
                wrap(row.text, paint, content.toFloat()).size * lineHeight(paint)
            }
            is ReceiptLayout.Row.Right -> lineHeight(textPaint(18f, scale, false))
            is ReceiptLayout.Row.Pair -> lineHeight(textPaint(18f, scale, row.bold))
            is ReceiptLayout.Row.Triple -> lineHeight(textPaint(17f, scale, false))
            is ReceiptLayout.Row.Table -> tableHeight(row.rows, content, scale)
        }
    }

    private fun drawRow(
        canvas: Canvas,
        row: ReceiptLayout.Row,
        left: Float,
        top: Float,
        content: Int,
        scale: Float
    ): Int {
        return when (row) {
            is ReceiptLayout.Row.Center -> {
                val paint = textPaint(if (row.large) 28f else 20f, scale, row.bold)
                drawWrapped(canvas, row.text, paint, left, top, content.toFloat(), center = true)
            }
            is ReceiptLayout.Row.Left -> {
                val paint = textPaint(18f, scale, row.bold)
                drawWrapped(canvas, row.text, paint, left, top, content.toFloat(), center = false)
            }
            is ReceiptLayout.Row.Right -> {
                val paint = textPaint(18f, scale, false)
                val width = paint.measureText(row.text)
                drawBaseline(canvas, row.text, paint, left + content - width, top)
                lineHeight(paint)
            }
            is ReceiptLayout.Row.Pair -> {
                val paint = textPaint(18f, scale, row.bold)
                drawEnds(canvas, row.left, row.right, paint, left, top, content.toFloat())
            }
            is ReceiptLayout.Row.Triple -> {
                val paint = textPaint(17f, scale, false)
                val mid = paint.measureText(row.mid)
                drawBaseline(canvas, row.left, paint, left, top)
                drawBaseline(canvas, row.mid, paint, left + (content - mid) / 2f, top)
                val right = paint.measureText(row.right)
                drawBaseline(canvas, row.right, paint, left + content - right, top)
                lineHeight(paint)
            }
            is ReceiptLayout.Row.Table -> drawTable(canvas, row.rows, left, top, content, scale)
        }
    }

    private fun tableHeight(rows: List<List<String>>, content: Int, scale: Float): Int {
        val paint = tablePaint(rows, content, scale)
        val widths = columnWidths(rows, paint, content)
        var height = 0
        rows.forEachIndexed { index, row ->
            height += tableRowHeight(row, widths, paint, index == 0) + (4 * scale).toInt()
        }
        return height
    }

    private fun drawTable(
        canvas: Canvas,
        rows: List<List<String>>,
        left: Float,
        top: Float,
        content: Int,
        scale: Float
    ): Int {
        val paint = tablePaint(rows, content, scale)
        val bold = TextPaint(paint).apply { typeface = Typeface.DEFAULT_BOLD }
        val widths = columnWidths(rows, paint, content)
        val numeric = numericColumns(rows)
        var y = top
        rows.forEachIndexed { index, row ->
            val rowPaint = if (index == 0) bold else paint
            val height = tableRowHeight(row, widths, rowPaint, index == 0)
            var x = left
            row.forEachIndexed { column, cell ->
                val colWidth = widths.getOrElse(column) { 0 }
                val lines = if (index == 0) {
                    wrap(cell, rowPaint, colWidth.toFloat() - 4f)
                } else {
                    cell.split('\n').flatMap { wrap(it, rowPaint, colWidth.toFloat() - 4f) }
                }
                val alignRight = column != 0 && numeric.getOrElse(column) { false }
                var lineTop = y + 2f
                for (line in lines) {
                    val measured = rowPaint.measureText(line)
                    val dx = if (alignRight) x + colWidth - measured - 2f else x + 2f
                    drawBaseline(canvas, line, rowPaint, dx, lineTop)
                    lineTop += lineHeight(rowPaint)
                }
                x += colWidth
            }
            y += height + 4 * scale
        }
        return (y - top).toInt()
    }

    private fun tableRowHeight(
        row: List<String>,
        widths: IntArray,
        paint: TextPaint,
        header: Boolean
    ): Int {
        var lines = 1
        row.forEachIndexed { column, cell ->
            val width = (widths.getOrElse(column) { 0 } - 4).coerceAtLeast(8).toFloat()
            val count = if (header) {
                wrap(cell, paint, width).size
            } else {
                cell.split('\n').sumOf { wrap(it, paint, width).size }
            }
            if (count > lines) lines = count
        }
        return lines * lineHeight(paint) + 4
    }

    private fun tablePaint(rows: List<List<String>>, content: Int, scale: Float): TextPaint {
        for (size in floatArrayOf(17f, 15f, 14f, 13f, 12f, 11f)) {
            val paint = textPaint(size, scale, false)
            val widths = naturalWidths(rows, paint)
            val gaps = 0
            if (widths.sum() + gaps <= content) return paint
        }
        return textPaint(11f, scale, false)
    }

    private fun columnWidths(rows: List<List<String>>, paint: TextPaint, content: Int): IntArray {
        val natural = naturalWidths(rows, paint)
        if (natural.isEmpty()) return IntArray(0)
        val sum = natural.sum().coerceAtLeast(1)
        if (sum <= content) {
            val extra = content - sum
            val share = extra / natural.size
            for (index in natural.indices) natural[index] += share
            natural[natural.lastIndex] += extra - share * natural.size
            return natural
        }
        val scaled = IntArray(natural.size) { ((natural[it].toFloat() / sum) * content).toInt().coerceAtLeast(8) }
        val drift = content - scaled.sum()
        scaled[0] = (scaled[0] + drift).coerceAtLeast(8)
        return scaled
    }

    private fun naturalWidths(rows: List<List<String>>, paint: TextPaint): IntArray {
        val count = rows.maxOfOrNull { it.size } ?: 0
        val widths = IntArray(count)
        rows.forEachIndexed { rowIndex, row ->
            row.forEachIndexed { column, cell ->
                val pieces = if (rowIndex == 0 && cell.contains(' ')) headerPieces(cell) else cell.split('\n')
                val widest = pieces.maxOf { line ->
                    paint.measureText(line).toInt().coerceAtLeast(8)
                }
                if (widest + 8 > widths[column]) widths[column] = widest + 8
            }
        }
        return widths
    }

    private fun headerPieces(text: String): List<String> {
        val words = text.split(' ').filter { it.isNotBlank() }
        if (words.size < 2) return listOf(text)
        val mid = (words.size + 1) / 2
        return listOf(
            words.take(mid).joinToString(" "),
            words.drop(mid).joinToString(" ")
        ).filter { it.isNotBlank() }
    }

    private fun numericColumns(rows: List<List<String>>): BooleanArray {
        val count = rows.maxOfOrNull { it.size } ?: 0
        val numeric = BooleanArray(count)
        val number = Regex("""[0-9][0-9.,]*\s*%?|[0-9.]+\s*%""")
        for (column in 1 until count) {
            val values = rows.drop(1).map { it.getOrElse(column) { "" }.trim() }.filter { it.isNotEmpty() && !it.contains('\n') }
            numeric[column] = values.isNotEmpty() && values.all { number.matches(it) }
        }
        return numeric
    }

    private fun drawEnds(
        canvas: Canvas,
        leftText: String,
        rightText: String,
        paint: TextPaint,
        left: Float,
        top: Float,
        content: Float
    ): Int {
        val rightWidth = paint.measureText(rightText)
        val leftMax = (content - rightWidth - 12f).coerceAtLeast(40f)
        val lines = wrap(leftText, paint, leftMax)
        lines.forEachIndexed { index, line ->
            drawBaseline(canvas, line, paint, left, top + index * lineHeight(paint))
        }
        if (rightText.isNotBlank()) {
            drawBaseline(canvas, rightText, paint, left + content - rightWidth, top)
        }
        return lines.size.coerceAtLeast(1) * lineHeight(paint)
    }

    private fun drawWrapped(
        canvas: Canvas,
        text: String,
        paint: TextPaint,
        left: Float,
        top: Float,
        content: Float,
        center: Boolean
    ): Int {
        val lines = wrap(text, paint, content)
        lines.forEachIndexed { index, line ->
            val dx = if (center) left + (content - paint.measureText(line)) / 2f else left
            drawBaseline(canvas, line, paint, dx, top + index * lineHeight(paint))
        }
        return lines.size.coerceAtLeast(1) * lineHeight(paint)
    }

    private fun drawBaseline(canvas: Canvas, text: String, paint: TextPaint, x: Float, top: Float) {
        if (text.isEmpty()) return
        canvas.drawText(text, x, top - paint.fontMetrics.ascent, paint)
    }

    private fun lineHeight(paint: TextPaint): Int {
        val metrics = paint.fontMetrics
        return (metrics.descent - metrics.ascent + 2f).toInt().coerceAtLeast(1)
    }

    private fun wrap(text: String, paint: TextPaint, maxWidth: Float): List<String> {
        if (text.isBlank()) return listOf("")
        val lines = mutableListOf<String>()
        for (piece in text.split('\n')) {
            if (piece.isEmpty()) {
                lines.add("")
                continue
            }
            val words = piece.split(' ')
            var current = ""
            for (word in words) {
                val trial = if (current.isEmpty()) word else "$current $word"
                if (current.isNotEmpty() && paint.measureText(trial) > maxWidth) {
                    lines.add(current)
                    current = word
                } else {
                    current = trial
                }
            }
            if (current.isNotEmpty()) lines.add(current)
        }
        return lines.ifEmpty { listOf("") }
    }

    private fun textPaint(size: Float, scale: Float, bold: Boolean): TextPaint {
        return TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = (size * scale).coerceAtLeast(11f)
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    fun renderHtml(context: Context, html: String, widthPx: Int): Bitmap {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw IllegalStateException("K8 bill raster must run off the main thread")
        }
        val activity = findActivity(context)
            ?: throw IllegalStateException("K8 bill raster needs an Activity")
        val width = widthPx.coerceAtLeast(8)
        val document = tightenViewport(html, width)
        val latch = CountDownLatch(1)
        val bitmapRef = AtomicReference<Bitmap>()
        val errorRef = AtomicReference<Throwable>()
        val done = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())

        handler.post {
            val root = activity.window?.decorView as? ViewGroup
            if (root == null) {
                finish(done, errorRef, bitmapRef, latch, IllegalStateException("K8 bill window is not ready"), null)
                return@post
            }
            val webView = WebView(activity)
            val holder = FrameLayout(activity)
            try {
                webView.setBackgroundColor(Color.WHITE)
                webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                webView.isVerticalScrollBarEnabled = false
                webView.isHorizontalScrollBarEnabled = false
                webView.settings.apply {
                    javaScriptEnabled = false
                    defaultTextEncodingName = "utf-8"
                    useWideViewPort = false
                    loadWithOverviewMode = false
                    builtInZoomControls = false
                    displayZoomControls = false
                    loadsImagesAutomatically = true
                    textZoom = 100
                }
                holder.setBackgroundColor(Color.WHITE)
                holder.addView(
                    webView,
                    FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT)
                )
                // Keep it in the window so layout height is real, but off the visible screen.
                holder.translationX = 100000f
                root.addView(
                    holder,
                    FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT)
                )

                val pageStarted = AtomicBoolean(false)
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (!pageStarted.compareAndSet(false, true)) return
                        view?.post { pollHeight(view, activity, width, holder, root, done, bitmapRef, errorRef, latch) }
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        if (request?.isForMainFrame == true) {
                            Log.w(TAG, "Bill WebView error: ${error?.description}")
                        }
                    }
                }
                val base = Regex("""(?i)<base[^>]+href=["']([^"']+)["']""").find(document)?.groupValues?.getOrNull(1)
                webView.loadDataWithBaseURL(base, document, "text/html", "UTF-8", null)
                handler.postDelayed({
                    finish(
                        done,
                        errorRef,
                        bitmapRef,
                        latch,
                        IllegalStateException("K8 bill render timed out"),
                        null,
                        holder,
                        root
                    )
                }, RENDER_TIMEOUT_SEC * 1000)
            } catch (t: Throwable) {
                finish(done, errorRef, bitmapRef, latch, t, null, holder, root)
            }
        }

        if (!latch.await(RENDER_TIMEOUT_SEC + 2, TimeUnit.SECONDS)) {
            throw IllegalStateException("K8 bill render timed out")
        }
        errorRef.get()?.let { throw it }
        return bitmapRef.get() ?: throw IllegalStateException("K8 bill render produced no bitmap")
    }

    fun hasInk(bitmap: Bitmap): Boolean {
        if (bitmap.width <= 0 || bitmap.height <= 0) return false
        val stepX = (bitmap.width / 48).coerceAtLeast(1)
        val stepY = (bitmap.height / 96).coerceAtLeast(1)
        var ink = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.red(pixel) < 250 || Color.green(pixel) < 250 || Color.blue(pixel) < 250) {
                    ink++
                    if (ink >= 12) return true
                }
                x += stepX
            }
            y += stepY
        }
        return false
    }

    private fun pollHeight(
        webView: WebView,
        activity: Activity,
        width: Int,
        holder: FrameLayout,
        root: ViewGroup,
        done: AtomicBoolean,
        bitmapRef: AtomicReference<Bitmap>,
        errorRef: AtomicReference<Throwable>,
        latch: CountDownLatch
    ) {
        val density = activity.resources.displayMetrics.density
        val polls = AtomicInteger(0)
        val previous = AtomicInteger(-1)
        val stable = AtomicInteger(0)
        val task = object : Runnable {
            override fun run() {
                if (done.get()) return
                val attempt = polls.incrementAndGet()
                val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
                val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                webView.measure(widthSpec, heightSpec)
                val current = K8BillLayout.heightPx(webView.measuredHeight, webView.contentHeight, density)
                val stableNow = K8BillLayout.nextStableCount(previous.get(), current, stable.get())
                previous.set(current)
                stable.set(stableNow)
                val settled = stableNow >= K8BillLayout.STABLE_READS && attempt >= 8
                if (settled || attempt >= MAX_POLLS) {
                    captureNow(webView, width, current, holder, root, done, bitmapRef, errorRef, latch)
                } else {
                    webView.postDelayed(this, POLL_MS)
                }
            }
        }
        webView.post(task)
    }

    private fun captureNow(
        webView: WebView,
        width: Int,
        reportedHeight: Int,
        holder: FrameLayout,
        root: ViewGroup,
        done: AtomicBoolean,
        bitmapRef: AtomicReference<Bitmap>,
        errorRef: AtomicReference<Throwable>,
        latch: CountDownLatch
    ) {
        try {
            if (reportedHeight < K8BillLayout.MIN_HEIGHT_PX) {
                throw IllegalStateException("K8 bill height was $reportedHeight")
            }
            val height = reportedHeight
            webView.layout(0, 0, width, height)
            holder.layout(0, 0, width, height)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            webView.draw(canvas)
            if (!hasInk(bitmap)) {
                bitmap.recycle()
                throw IllegalStateException("K8 bill bitmap was blank")
            }
            finish(done, errorRef, bitmapRef, latch, null, bitmap, holder, root)
        } catch (t: Throwable) {
            finish(done, errorRef, bitmapRef, latch, t, null, holder, root)
        }
    }

    private fun finish(
        done: AtomicBoolean,
        errorRef: AtomicReference<Throwable>,
        bitmapRef: AtomicReference<Bitmap>,
        latch: CountDownLatch,
        error: Throwable?,
        bitmap: Bitmap?,
        holder: View? = null,
        root: ViewGroup? = null
    ) {
        if (!done.compareAndSet(false, true)) {
            if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
            return
        }
        val release = Runnable {
            detach(holder, root)
            if (error != null) errorRef.set(error) else bitmapRef.set(bitmap)
            latch.countDown()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            release.run()
        } else {
            Handler(Looper.getMainLooper()).post(release)
        }
    }

    private fun detach(holder: View?, root: ViewGroup?) {
        try {
            val parent = holder?.parent as? ViewGroup ?: root
            if (holder != null) parent?.removeView(holder)
            val webView = (holder as? FrameLayout)?.getChildAt(0) as? WebView
            webView?.stopLoading()
            webView?.destroy()
        } catch (t: Throwable) {
            Log.w(TAG, "WebView detach failed", t)
        }
    }

    private fun findActivity(context: Context): Activity? {
        var current: Context = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    private fun tightenViewport(html: String, width: Int): String {
        val viewport = """<meta name="viewport" content="width=$width, initial-scale=1">"""
        val fit = """<style id="k8-fit">html,body{margin:0;background:#fff;color:#000;width:${width}px;} img,table{max-width:100%;height:auto;} table,th,td,tr{border:none !important;outline:none !important;}</style>"""
        val stripped = html
            .replace(Regex("""<script\b[^>]*>[\s\S]*?</script>""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""<noscript\b[^>]*>[\s\S]*?</noscript>""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""<style\b[^>]*>[\s\S]*?</style>""", RegexOption.IGNORE_CASE), "")
            .replace(
                Regex("""<meta[^>]*name\s*=\s*["']viewport["'][^>]*>""", RegexOption.IGNORE_CASE),
                ""
            )
        val head = Regex("""<head[^>]*>""", RegexOption.IGNORE_CASE)
        if (head.containsMatchIn(stripped)) {
            return stripped.replace(head, "$0\n$viewport\n$fit")
        }
        return """
            <!DOCTYPE html>
            <html>
            <head>
            <meta charset="UTF-8">
            $viewport
            $fit
            </head>
            <body>$stripped</body>
            </html>
        """.trimIndent()
    }
}
