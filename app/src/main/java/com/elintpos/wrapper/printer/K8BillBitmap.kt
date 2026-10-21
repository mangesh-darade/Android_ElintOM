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
import com.elintpos.wrapper.utils.PreferencesManager
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
    private const val POLL_MS = 100L
    private const val SEED_HEIGHT = 1600
    private const val HEIGHT_JS = """
        (function(){
            try {
                var el = document.getElementById('wrapper') || document.getElementById('receiptData') || document.body;
                var h = el ? Math.max(el.scrollHeight || 0, el.offsetHeight || 0) : 0;
                var imgs = document.images || [];
                var pending = 0;
                for (var i = 0; i < imgs.length; i++) {
                    if (!imgs[i].complete) pending++;
                }
                return String(Math.round(h)) + ',' + pending;
            } catch (err) {
                return '0,1';
            }
        })()
    """

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
                    javaScriptEnabled = true
                    defaultTextEncodingName = "utf-8"
                    useWideViewPort = false
                    loadWithOverviewMode = false
                    builtInZoomControls = false
                    displayZoomControls = false
                    loadsImagesAutomatically = true
                    textZoom = 100
                }
                webView.setInitialScale(100)
                holder.setBackgroundColor(Color.WHITE)
                holder.alpha = 0f
                val seed = FrameLayout.LayoutParams(width, SEED_HEIGHT)
                holder.addView(webView, FrameLayout.LayoutParams(width, SEED_HEIGHT))
                root.addView(holder, seed)
                val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
                val heightSpec = View.MeasureSpec.makeMeasureSpec(SEED_HEIGHT, View.MeasureSpec.EXACTLY)
                holder.measure(widthSpec, heightSpec)
                holder.layout(0, 0, width, SEED_HEIGHT)
                webView.measure(widthSpec, heightSpec)
                webView.layout(0, 0, width, SEED_HEIGHT)

                val pageStarted = AtomicBoolean(false)
                fun startPolling() {
                    if (pageStarted.compareAndSet(false, true)) {
                        handler.post { pollHeight(webView, width, holder, root, done, bitmapRef, errorRef, latch, handler) }
                    }
                }
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        startPolling()
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
                // Watchdog: If onPageFinished is delayed by network or subresources, start polling anyway
                handler.postDelayed({
                    startPolling()
                }, 400)
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

    private fun cropBlank(source: Bitmap, topPad: Int, bottomPad: Int): Bitmap {
        fun rowHasInk(y: Int): Boolean {
            var x = 0
            while (x < source.width) {
                val pixel = source.getPixel(x, y)
                if (Color.red(pixel) < 245 || Color.green(pixel) < 245 || Color.blue(pixel) < 245) return true
                x += 4
            }
            return false
        }
        var top = 0
        while (top < source.height && !rowHasInk(top)) top++
        var bottom = source.height - 1
        while (bottom > top && !rowHasInk(bottom)) bottom--
        if (top >= bottom) return source
        val y = (top - topPad).coerceAtLeast(0)
        val end = (bottom + bottomPad).coerceAtMost(source.height - 1)
        val height = end - y + 1
        if (y == 0 && height == source.height) return source
        return Bitmap.createBitmap(source, 0, y, source.width, height)
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
        width: Int,
        holder: FrameLayout,
        root: ViewGroup,
        done: AtomicBoolean,
        bitmapRef: AtomicReference<Bitmap>,
        errorRef: AtomicReference<Throwable>,
        latch: CountDownLatch,
        handler: Handler
    ) {
        val polls = AtomicInteger(0)
        val previous = AtomicInteger(-1)
        val stable = AtomicInteger(0)
        val task = object : Runnable {
            override fun run() {
                if (done.get()) return
                val attempt = polls.incrementAndGet()
                webView.evaluateJavascript(HEIGHT_JS) { raw ->
                    if (done.get()) return@evaluateJavascript
                    val (scroll, pending) = parseMeasurement(raw)
                    @Suppress("DEPRECATION")
                    val scale = webView.scale
                    val content = webView.contentHeight
                    val current = when {
                        scroll >= K8BillLayout.MIN_HEIGHT_PX ->
                            K8BillLayout.documentHeight(scroll, 0, scale)
                        content in K8BillLayout.MIN_HEIGHT_PX until SEED_HEIGHT - 20 ->
                            K8BillLayout.documentHeight(0, content, scale)
                        attempt >= 15 && content >= K8BillLayout.MIN_HEIGHT_PX ->
                            K8BillLayout.documentHeight(0, content, scale)
                        else -> 0
                    }
                    val stableNow = K8BillLayout.nextStableCount(previous.get(), current, stable.get())
                    previous.set(current)
                    stable.set(stableNow)
                    if (attempt == 1 || attempt % 10 == 0) {
                        Log.d(TAG, "Bill measure attempt=$attempt height=$current scroll=$scroll content=${webView.contentHeight} scale=$scale pending=$pending")
                    }
                    if (K8BillLayout.readyToCapture(current, stableNow, attempt, pending > 0)) {
                        captureNow(webView, width, current, holder, root, done, bitmapRef, errorRef, latch)
                    } else {
                        handler.postDelayed(this, POLL_MS)
                    }
                }
            }
        }
        handler.post(task)
    }

    private fun parseMeasurement(raw: String?): Pair<Int, Int> {
        if (raw.isNullOrBlank() || raw == "null") return 0 to 1
        val text = raw.trim().removeSurrounding("\"").replace("\\\"", "")
        val parts = text.split(',')
        val height = parts.getOrNull(0)?.toFloatOrNull()?.toInt() ?: 0
        val pending = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return height to pending
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
            val height = reportedHeight.coerceAtMost(K8BillLayout.MAX_HEIGHT_PX)
            val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            webView.measure(widthSpec, heightSpec)
            holder.measure(widthSpec, heightSpec)
            webView.layout(0, 0, width, height)
            holder.layout(0, 0, width, height)
            val raw = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(raw)
            canvas.drawColor(Color.WHITE)
            webView.draw(canvas)
            val gaps = PreferencesManager(webView.context)
            val bitmap = cropBlank(raw, gaps.printTopGapDots, 6)
            if (bitmap !== raw && !raw.isRecycled) raw.recycle()
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
        val fontPt = Regex("""name=["']elint-font-pt["'][^>]*content=["'](\d+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""font-size\s*:\s*(\d+)pt""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)?.toIntOrNull()
            ?: 12
        val body = K8BillLayout.bodyPx(fontPt, width)
        val table = (body * 0.92f).toInt()
        val padY = (body * 0.28f).toInt().coerceAtLeast(5)
        val padX = (body * 0.22f).toInt().coerceAtLeast(4)
        val logo = (width * 0.38f).toInt().coerceIn(120, 240)
        val showLines = Regex(
            """name=["']elint-invoice-lines["'][^>]*content=["'](\d+)["']""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.get(1) != "0"
        val billColumns = Regex(
            """name=["']elint-bill-columns["'][^>]*content=["'](\d+)["']""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(2, 12) ?: 5
        val billHasSr = Regex(
            """name=["']elint-bill-sr["'][^>]*content=["'](\d+)["']""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.get(1) == "1"
        val nameShare = when {
            billColumns <= 4 -> 40
            billColumns <= 6 -> 32
            else -> 24
        }
        val nameColumnCss = if (billHasSr) {
            """
            table.table thead th:first-child { width: 8% !important; text-align: center !important; }
            table.table thead th:nth-child(2) { width: ${nameShare}% !important; text-align: left !important; }
            table.table tbody td:nth-child(2) { text-align: left !important; }
            """
        } else {
            """
            table.table thead th:first-child { width: ${nameShare}% !important; text-align: left !important; }
            """
        }
        val crowded = billColumns >= 7
        val tableFont = if (crowded) (body * 0.78f).toInt().coerceAtLeast(16) else table
        val cellPadX = if (crowded) 2 else padX
        val lineCss = if (showLines) """
            table.table thead th {
                border-top: 2px solid #000000 !important;
                border-bottom: 2px solid #000000 !important;
            }
            table.table tfoot tr:first-child th {
                border-top: 2px solid #000000 !important;
                border-bottom: 2px solid #000000 !important;
            }
            table.table tfoot tr:last-child th {
                border-bottom: 2px solid #000000 !important;
            }
            .k8-thanks { border-top: 2px dashed #000000 !important; }
        """ else """
            table.table th, table.table td, table.table tr, .k8-thanks {
                border: none !important;
            }
        """
        val viewport = """<meta name="viewport" content="width=$width, initial-scale=1">"""
        val fit = """<style id="k8-fit">
            html, body {
                margin: 0 !important;
                padding: 0 !important;
                background: #ffffff !important;
                color: #000000 !important;
                width: ${width}px !important;
                height: auto !important;
                min-height: 0 !important;
                overflow: visible !important;
                font-size: ${body}px !important;
                line-height: 1.4 !important;
                font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif, "Nirmala UI", "Mangal" !important;
                -webkit-font-smoothing: antialiased;
            }
            #wrapper {
                width: 100% !important;
                max-width: ${width}px !important;
                min-width: 0 !important;
                margin: 0 !important;
                padding: 0 6px 0 !important;
                box-sizing: border-box !important;
            }
            #receiptData, #receipt-data {
                margin: 0 !important;
                padding: 0 !important;
                height: auto !important;
                font-size: ${body}px !important;
                line-height: 1.4 !important;
            }
            .row, [class*="col-"] {
                width: 100% !important;
                max-width: 100% !important;
                display: block !important;
                box-sizing: border-box !important;
                position: relative !important;
            }
            .no-print, .alert, .btn, #buttons {
                display: none !important;
            }
            img {
                display: block !important;
                margin: 0 auto 4px !important;
                max-width: ${logo}px !important;
                max-height: 78px !important;
                height: auto !important;
            }
            h3 {
                margin: 2px 0 4px !important;
                font-size: ${(body * 1.55f).toInt()}px !important;
                line-height: 1.2 !important;
                font-weight: 800 !important;
                text-align: center !important;
            }
            p, #receipt-data, #receipt-data div {
                font-size: ${body}px !important;
                line-height: 1.4 !important;
            }
            p {
                margin: 4px 0 !important;
            }
            table.table {
                width: 100% !important;
                max-width: 100% !important;
                border-collapse: collapse !important;
                table-layout: fixed !important;
                margin-top: 8px !important;
            }
            table.table th, table.table td {
                min-width: 0 !important;
                padding: ${padY}px ${cellPadX}px !important;
                color: #000000 !important;
                vertical-align: middle !important;
                font-size: ${tableFont}px !important;
                line-height: 1.35 !important;
                white-space: normal !important;
                word-wrap: break-word !important;
                overflow-wrap: anywhere !important;
                border: none !important;
                background: #ffffff !important;
            }
            table.table thead th {
                font-weight: 700 !important;
                text-align: center !important;
            }
            table.table tbody td:first-child { text-align: left !important; }
            $nameColumnCss
            $lineCss
            table.table tfoot th {
                vertical-align: top !important;
            }
            table.table tfoot th[colspan] {
                text-align: left !important;
            }
            table.table tfoot tr:first-child th {
                font-weight: 700 !important;
            }
            table.table tfoot th span {
                float: none !important;
                display: block !important;
                text-align: left !important;
                padding: 2px 0 0 !important;
                font-size: 0.82em !important;
                font-weight: 600 !important;
            }
            .k8-pay table, .k8-pay td {
                border: none !important;
                margin-top: 6px !important;
            }
            .k8-pay table { width: 100% !important; table-layout: fixed !important; }
            .k8-pay td:nth-child(1) { text-align: left !important; width: 34% !important; }
            .k8-pay td:nth-child(2) { text-align: center !important; width: 38% !important; }
            .k8-pay td:nth-child(3) { text-align: right !important; width: 28% !important; }
            .k8-save { text-align: center !important; font-weight: 800 !important; margin: 8px 0 !important; }
            .k8-thanks {
                clear: both !important;
                float: none !important;
                width: 100% !important;
                margin-top: 6px !important;
                padding-top: 8px !important;
                text-align: center !important;
            }
            table.table th.mrp, table.table td.mrp,
            table.table th.item_discount, table.table td.item_discount,
            table.table th.discount, table.table td.discount,
            table.table th.quantity, table.table td.quantity,
            table.table th.unit_quantity, table.table td.unit_quantity {
                text-align: center !important;
            }
            table.table th.subtotal, table.table td.subtotal,
            table.table th.invoice_unit_price, table.table td.invoice_unit_price,
            table.table th.invoice_net_unit_price, table.table td.invoice_net_unit_price,
            table.table th.unit_price, table.table td.unit_price,
            table.table th.real_unit_price, table.table td.real_unit_price,
            table.table th.net_unit_price, table.table td.net_unit_price,
            table.table th.net_price, table.table td.net_price,
            table.table th.item_tax, table.table td.item_tax {
                text-align: right !important;
            }
            table:not(.table) td, table:not(.table) th {
                border: none !important;
                padding: 0 2px !important;
                vertical-align: top !important;
            }
            .text-center > table {
                width: 100% !important;
            }
            .pull-left {
                float: left !important;
            }
            .pull-right {
                float: right !important;
            }
            .text-center, .text-center h3 {
                text-align: center !important;
            }
            .text-right, th.text-right, td.text-right {
                text-align: right !important;
            }
            table.table th.mrp, table.table td.mrp,
            table.table th.item_discount, table.table td.item_discount,
            table.table th.discount, table.table td.discount,
            table.table th.quantity, table.table td.quantity,
            table.table th.unit_quantity, table.table td.unit_quantity {
                text-align: center !important;
            }
            .text-left {
                text-align: left !important;
            }
            .row:after, [class*="col-"]:after, .clearfix:after {
                content: "" !important;
                display: table !important;
                clear: both !important;
            }
        </style>"""
        val stripped = html
            .replace(Regex("""<script\b[^>]*>[\s\S]*?</script>""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""<noscript\b[^>]*>[\s\S]*?</noscript>""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""<link\b[^>]*>""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""margin-top\s*:\s*-\d+px""", RegexOption.IGNORE_CASE), "margin-top:0")
            .replace(Regex("""font-size\s*:\s*[\d.]+pt\s*!important""", RegexOption.IGNORE_CASE), "font-size:inherit")
            .replace(
                Regex("""<meta[^>]*name\s*=\s*["']viewport["'][^>]*>""", RegexOption.IGNORE_CASE),
                ""
            )
        val closeHead = Regex("""</head>""", RegexOption.IGNORE_CASE)
        if (closeHead.containsMatchIn(stripped)) {
            return stripped.replace(closeHead, "$viewport\n$fit\n</head>")
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
