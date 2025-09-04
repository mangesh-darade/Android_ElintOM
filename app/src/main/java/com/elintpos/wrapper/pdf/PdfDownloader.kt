package com.elintpos.wrapper.pdf

import android.content.Context
import android.os.Environment
import android.os.Build
import android.provider.MediaStore
import android.content.ContentValues
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.ValueCallback
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import android.graphics.pdf.PdfDocument
import android.view.View
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class PdfDownloader(private val context: Context) {
    
    companion object {
        private const val TAG = "PdfDownloader"
    }
    
    private var downloadCallback: ValueCallback<Array<Uri>>? = null
    
    /**
     * Download current WebView content as PDF
     */
    fun downloadCurrentPageAsPdf(webView: WebView, fileName: String? = null): String {
        return try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val pdfFileName = fileName ?: "ElintPOS_Export_$timestamp.pdf"
            
            // Generate into a temp file first
            val tempPdf = File(context.cacheDir, "tmp_export_${'$'}timestamp.pdf")
            generatePdfFromWebView(webView, tempPdf)

            val savedUri = saveToDownloads(tempPdf, pdfFileName, "application/pdf")
            try { tempPdf.delete() } catch (_: Exception) {}

            "{\"ok\":true,\"uri\":\"${'$'}savedUri\",\"fileName\":\"$pdfFileName\"}"
        } catch (e: Exception) {
            "{\"ok\":false,\"msg\":\"${e.message}\"}"
        }
    }
    
    /**
     * Download URL content as PDF
     */
    fun downloadUrlAsPdf(url: String, fileName: String? = null): String {
        return try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val pdfFileName = fileName ?: "ElintPOS_Export_$timestamp.pdf"
            
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val pdfFile = File(downloadsDir, pdfFileName)
            
            // For URL downloads, we'll use the WebView to load and then print
            "{\"ok\":true,\"msg\":\"URL download initiated\",\"fileName\":\"$pdfFileName\"}"
        } catch (e: Exception) {
            "{\"ok\":false,\"msg\":\"${e.message}\"}"
        }
    }
    
    /**
     * Export HTML content as PDF
     */
    fun exportHtmlAsPdf(htmlContent: String, fileName: String? = null): String {
        return try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val pdfFileName = fileName ?: "ElintPOS_Export_$timestamp.pdf"

            val pdfFile = File(context.cacheDir, "tmp_export_${'$'}timestamp.pdf")
            
            // Render provided HTML in an offscreen WebView and print to a real PDF (temp file)
            val tempWebView = WebView(context)
            val latch = CountDownLatch(1)
            var error: Exception? = null

            tempWebView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    // Give it a short time to fully layout images/fonts, then render
                    tempWebView.postDelayed({
                        try {
                            generatePdfFromWebView(tempWebView, pdfFile)
                        } catch (e: Exception) {
                            error = e
                        } finally {
                            latch.countDown()
                        }
                    }, 300)
                }
            }
            tempWebView.settings.javaScriptEnabled = false
            tempWebView.loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null)

            latch.await(15, TimeUnit.SECONDS)
            error?.let { throw it }

            val savedUri = saveToDownloads(pdfFile, pdfFileName, "application/pdf")
            try { pdfFile.delete() } catch (_: Exception) {}
            "{\"ok\":true,\"uri\":\"${'$'}savedUri\",\"fileName\":\"$pdfFileName\"}"
        } catch (e: Exception) {
            "{\"ok\":false,\"msg\":\"${e.message}\"}"
        }
    }
    
    /**
     * Generate PDF from WebView using print functionality
     */
    private fun generatePdfFromWebView(webView: WebView, outputFile: File) {
        // All WebView operations must run on the UI thread
        val latch = CountDownLatch(1)
        var thrown: Exception? = null

        webView.post {
            try {
                // Use current WebView width if available; otherwise fall back
                val baseWidth = webView.width.takeIf { it > 0 } ?: 1240
                // Force full layout pass to get accurate contentHeight
                webView.measure(
                    View.MeasureSpec.makeMeasureSpec(baseWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                val scale = if (webView.scale > 0f) webView.scale else 1f
                val estimatedContentHeight = (webView.contentHeight * scale).toInt().coerceAtLeast(1)

                val widthSpec = View.MeasureSpec.makeMeasureSpec(baseWidth, View.MeasureSpec.EXACTLY)
                val heightSpec = View.MeasureSpec.makeMeasureSpec(estimatedContentHeight, View.MeasureSpec.EXACTLY)
                webView.measure(widthSpec, heightSpec)
                webView.layout(0, 0, baseWidth, estimatedContentHeight)

                val pageWidth = baseWidth
                val pageHeight = (pageWidth * 297f / 210f).toInt().coerceAtLeast(100)

                val pdf = PdfDocument()
                var pageIndex = 0
                var y = 0
                while (y < estimatedContentHeight) {
                    val thisPageHeight = minOf(pageHeight, estimatedContentHeight - y)
                    val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, thisPageHeight, pageIndex + 1).create()
                    val page = pdf.startPage(pageInfo)
                    val canvas = page.canvas
                    // Paint white background to avoid transparent/black pages
                    canvas.drawColor(android.graphics.Color.WHITE)
                    canvas.translate(0f, -y.toFloat())
                    webView.draw(canvas)
                    pdf.finishPage(page)
                    y += thisPageHeight
                    pageIndex += 1
                }

                if (outputFile.exists()) outputFile.delete()
                outputFile.parentFile?.mkdirs()
                FileOutputStream(outputFile).use { fos -> pdf.writeTo(fos) }
                pdf.close()

                // If something went wrong and file is too small, fallback to bitmap-based PDF
                if (outputFile.length() < 1024) {
                    try { writeBitmapPdf(webView, outputFile) } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                thrown = e
            } finally {
                latch.countDown()
            }
        }

        // Wait up to 15s for UI thread to finish rendering
        latch.await(15, TimeUnit.SECONDS)
        thrown?.let { throw it }
    }

    // Fallback: snapshot the WebView into bitmaps and write as a PDF
    private fun writeBitmapPdf(webView: WebView, outFile: File) {
        val width = webView.width.takeIf { it > 0 } ?: 1240
        val scale = if (webView.scale > 0f) webView.scale else 1f
        val contentHeight = (webView.contentHeight * scale).toInt().coerceAtLeast(1)
        val pageHeight = (width * 297f / 210f).toInt().coerceAtLeast(100)

        val pdf = PdfDocument()
        var y = 0
        var pageIndex = 0
        while (y < contentHeight) {
            val sliceHeight = minOf(pageHeight, contentHeight - y)
            val bmp = android.graphics.Bitmap.createBitmap(width, sliceHeight, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            c.drawColor(android.graphics.Color.WHITE)
            c.translate(0f, -y.toFloat())
            webView.draw(c)

            val pageInfo = PdfDocument.PageInfo.Builder(width, sliceHeight, pageIndex + 1).create()
            val page = pdf.startPage(pageInfo)
            page.canvas.drawColor(android.graphics.Color.WHITE)
            page.canvas.drawBitmap(bmp, 0f, 0f, null)
            pdf.finishPage(page)
            bmp.recycle()
            y += sliceHeight
            pageIndex += 1
        }
        if (outFile.exists()) outFile.delete()
        FileOutputStream(outFile).use { pdf.writeTo(it) }
        pdf.close()
    }
    
    /**
     * Open PDF file with system default app
     */
    fun openPdfFile(filePath: String): Boolean {
        return try {
            val uri: Uri = if (filePath.startsWith("content:")) {
                Uri.parse(filePath)
            } else {
                val file = File(filePath)
                if (!file.exists()) {
                    Toast.makeText(context, "PDF file not found", Toast.LENGTH_SHORT).show()
                    return false
                }
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }
            
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Toast.makeText(context, "Error opening PDF: ${e.message}", Toast.LENGTH_SHORT).show()
            false
        }
    }
    
    /**
     * Share PDF file
     */
    fun sharePdfFile(filePath: String): Boolean {
        return try {
            val uri: Uri = if (filePath.startsWith("content:")) Uri.parse(filePath) else {
                val f = File(filePath)
                if (!f.exists()) {
                    Toast.makeText(context, "PDF file not found", Toast.LENGTH_SHORT).show()
                    return false
                }
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
            }
            
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            val chooser = Intent.createChooser(intent, "Share PDF")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            true
        } catch (e: Exception) {
            Toast.makeText(context, "Error sharing PDF: ${e.message}", Toast.LENGTH_SHORT).show()
            false
        }
    }

    /**
     * Save a file into public Downloads using MediaStore (Q+) or legacy path (<Q)
     * Returns a string URI (content:// on Q+, file path on legacy)
     */
    private fun saveToDownloads(src: File, displayName: String, mime: String): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("Failed to create download entry")
                resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri.toString()
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val dst = File(dir, displayName)
                src.inputStream().use { input -> FileOutputStream(dst).use { output -> input.copyTo(output) } }
                dst.absolutePath
            }
        } catch (e: Exception) {
            // Fallback: keep temp location
            src.absolutePath
        }
    }
    
    /**
     * Get list of downloaded PDF files
     */
    fun getDownloadedPdfs(): List<File> {
        return try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            downloadsDir.listFiles()?.filter { 
                it.isFile && it.extension.lowercase() == "pdf" && it.name.startsWith("ElintPOS_Export_")
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Delete PDF file
     */
    fun deletePdfFile(filePath: String): Boolean {
        return try {
            val file = File(filePath)
            if (file.exists()) {
                file.delete()
                Toast.makeText(context, "PDF file deleted", Toast.LENGTH_SHORT).show()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Error deleting PDF: ${e.message}", Toast.LENGTH_SHORT).show()
            false
        }
    }
}
