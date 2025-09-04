package com.elintpos.wrapper.escpos

import android.content.Context
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

class LanEscPosPrinter(private val context: Context) {
	private var socket: Socket? = null
	private var output: OutputStream? = null
	private var isConnected: Boolean = false

	@Throws(IOException::class)
	fun connect(ipAddress: String, port: Int = 9100, timeoutMs: Int = 5000) {
		close()
		socket = Socket()
		socket?.soTimeout = timeoutMs
		socket?.connect(java.net.InetSocketAddress(ipAddress, port), timeoutMs)
		output = socket?.getOutputStream()
		isConnected = true
	}

	fun isConnected(): Boolean = isConnected && socket?.isConnected == true

	@Throws(IOException::class)
	fun printText(
		text: String,
		charset: Charset = Charset.forName("UTF-8"),
		leftMarginDots: Int = 0,
		rightMarginDots: Int = 0,
		lineSpacing: Int = 30,
		widthMultiplier: Int = 0,
		heightMultiplier: Int = 0,
		pageWidthDots: Int? = null,
		linesPerPage: Int? = null
	) {
		val os = output ?: throw IOException("Not connected")

		// ESC/POS init
		os.write(byteArrayOf(0x1B, 0x40))
		// Line spacing
		os.write(byteArrayOf(0x1B, 0x33, lineSpacing.toByte()))
		// Font scale via GS ! n with independent multipliers (0..7 usually, commonly 0..3)
		val w = widthMultiplier.coerceIn(0, 7)
		val h = heightMultiplier.coerceIn(0, 7)
		val n = (h shl 4) or w
		os.write(byteArrayOf(0x1D, 0x21, n.toByte()))

		val left = leftMarginDots.coerceAtLeast(0).coerceAtMost(255)
		os.write(byteArrayOf(0x1D, 0x4C, (left and 0xFF).toByte(), ((left shr 8) and 0xFF).toByte()))

		val contentWidthDots = pageWidthDots?.let { it - leftMarginDots - rightMarginDots }?.coerceAtLeast(48)
		val baseCharWidthDots = 12 // approx for 12x24 font width at scale 0
		val charWidthDots = baseCharWidthDots * (w + 1)
		val maxCharsPerLine = contentWidthDots?.let { if (charWidthDots > 0) it / charWidthDots else null }

		fun wrapLines(src: String): List<String> {
			if (maxCharsPerLine == null || maxCharsPerLine <= 0) return src.replace("\r\n", "\n").split("\n")
			val out = mutableListOf<String>()
			src.replace("\r\n", "\n").split("\n").forEach { line ->
				var idx = 0
				while (idx < line.length) {
					val end = (idx + maxCharsPerLine).coerceAtMost(line.length)
					out.add(line.substring(idx, end))
					idx = end
				}
			}
			return out
		}

		val lines = wrapLines(text)
		var lineCounter = 0
		for (line in lines) {
			os.write(line.toByteArray(charset))
			os.write(byteArrayOf(0x0A))
			lineCounter++
			if (linesPerPage != null && linesPerPage > 0 && lineCounter >= linesPerPage) {
				// Cut between pages
				os.write(byteArrayOf(0x1B, 0x64, 0x02))
				os.write(byteArrayOf(0x1D, 0x56, 0x42, 0x03))
				lineCounter = 0
				// Re-apply settings post cut (some printers reset scale)
				os.write(byteArrayOf(0x1B, 0x40))
				os.write(byteArrayOf(0x1B, 0x33, lineSpacing.toByte()))
				os.write(byteArrayOf(0x1D, 0x21, n.toByte()))
				os.write(byteArrayOf(0x1D, 0x4C, (left and 0xFF).toByte(), ((left shr 8) and 0xFF).toByte()))
			}
		}
		// Final feed and cut
		os.write(byteArrayOf(0x1B, 0x64, 0x02))
		os.write(byteArrayOf(0x1D, 0x56, 0x42, 0x03))
		os.flush()
	}

	@Throws(IOException::class)
	fun printBarcode(data: String, barcodeType: Int = 73, height: Int = 50, width: Int = 2, position: Int = 0) {
		val os = output ?: throw IOException("Not connected")
		
		// Set barcode parameters
		os.write(byteArrayOf(0x1D, 0x68, height.toByte())) // Height
		os.write(byteArrayOf(0x1D, 0x77, width.toByte()))  // Width
		os.write(byteArrayOf(0x1D, 0x48, position.toByte())) // Position (0=no text, 1=above, 2=below, 3=both)
		
		// Print barcode
		os.write(byteArrayOf(0x1D, 0x6B, barcodeType.toByte(), data.length.toByte()))
		os.write(data.toByteArray())
		os.write(byteArrayOf(0x0A)) // Feed
		os.flush()
	}

	@Throws(IOException::class)
	fun printQRCode(data: String, size: Int = 3, errorCorrection: Int = 0) {
		val os = output ?: throw IOException("Not connected")
		
		// QR Code settings
		os.write(byteArrayOf(0x1D, 0x28, 0x6B, 0x04, 0x00, 0x31, 0x41, 0x32, 0x00)) // QR Code model
		os.write(byteArrayOf(0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x43, size.toByte())) // QR Code size
		os.write(byteArrayOf(0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x45, errorCorrection.toByte())) // Error correction
		
		// Store QR Code data
		val dataBytes = data.toByteArray()
		val p1 = (dataBytes.size + 3) % 256
		val p2 = (dataBytes.size + 3) / 256
		os.write(byteArrayOf(0x1D, 0x28, 0x6B, p1.toByte(), p2.toByte(), 0x31, 0x50, 0x30))
		os.write(dataBytes)
		
		// Print QR Code
		os.write(byteArrayOf(0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x51, 0x30))
		os.write(byteArrayOf(0x0A)) // Feed
		os.flush()
	}

	@Throws(IOException::class)
	fun printImage(bitmap: android.graphics.Bitmap, width: Int = 384) {
		val os = output ?: throw IOException("Not connected")
		
		// Scale bitmap to target width
		val aspectRatio = bitmap.height.toFloat() / bitmap.width.toFloat()
		val height = (width * aspectRatio).toInt()
		val scaledBitmap = android.graphics.Bitmap.createScaledBitmap(bitmap, width, height, true)
		
		// Convert to 1-bit bitmap
		val widthBytes = (width + 7) / 8
		val imageData = ByteArray(widthBytes * height)
		
		for (y in 0 until height) {
			for (x in 0 until width) {
				val pixel = scaledBitmap.getPixel(x, y)
				val gray = (android.graphics.Color.red(pixel) + android.graphics.Color.green(pixel) + android.graphics.Color.blue(pixel)) / 3
				if (gray < 128) { // Black pixel
					val byteIndex = y * widthBytes + x / 8
					val bitIndex = 7 - (x % 8)
					imageData[byteIndex] = (imageData[byteIndex].toInt() or (1 shl bitIndex)).toByte()
				}
			}
		}
		
		// Print image using ESC * command
		os.write(byteArrayOf(0x1B, 0x33, 0x18)) // Set line spacing
		for (y in 0 until height) {
			os.write(byteArrayOf(0x1B, 0x2A, 0x00, widthBytes.toByte(), (widthBytes and 0xFF).toByte()))
			os.write(imageData, y * widthBytes, widthBytes)
			os.write(byteArrayOf(0x0A))
		}
		os.write(byteArrayOf(0x1B, 0x33, 0x30)) // Reset line spacing
		os.flush()
	}

	fun close() {
		try { output?.flush() } catch (_: Exception) {}
		try { output?.close() } catch (_: Exception) {}
		try { socket?.close() } catch (_: Exception) {}
		output = null
		socket = null
		isConnected = false
	}
}
