package com.elintpos.wrapper.escpos

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.IOException
import java.nio.charset.Charset

class UsbEscPosPrinter(private val context: Context) {
	private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
	private var connection: UsbDeviceConnection? = null
	private var endpointOut: UsbEndpoint? = null
	private var usbInterface: UsbInterface? = null

	fun listPrinters(): List<UsbDevice> =
		usbManager.deviceList.values.filter { dev ->
			(0x07 == dev.deviceClass /*Printer*/ || (0 until dev.interfaceCount).map { dev.getInterface(it) }.any { it.interfaceClass == UsbConstants.USB_CLASS_PRINTER })
		}

	@Throws(IOException::class)
	fun connect(device: UsbDevice) {
		close()
		if (!usbManager.hasPermission(device)) {
			throw IOException("No USB permission for device")
		}
		val iface = (0 until device.interfaceCount)
			.map { device.getInterface(it) }
			.firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_PRINTER }
			?: device.getInterface(0)
		usbInterface = iface
		val epOut = (0 until iface.endpointCount)
			.map { iface.getEndpoint(it) }
			.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT }
			?: throw IOException("No OUT endpoint")
		endpointOut = epOut
		val conn = usbManager.openDevice(device) ?: throw IOException("Failed to open USB device")
		connection = conn
		if (!conn.claimInterface(iface, true)) throw IOException("Failed to claim interface")
	}

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
		val conn = connection ?: throw IOException("Not connected")
		val out = endpointOut ?: throw IOException("No OUT endpoint")

		fun write(bytes: ByteArray) {
			val sent = conn.bulkTransfer(out, bytes, bytes.size, 3000)
			if (sent <= 0) throw IOException("USB write failed")
		}

		write(byteArrayOf(0x1B, 0x40))
		write(byteArrayOf(0x1B, 0x33, lineSpacing.toByte()))
		val w = widthMultiplier.coerceIn(0, 7)
		val h = heightMultiplier.coerceIn(0, 7)
		val n = (h shl 4) or w
		write(byteArrayOf(0x1D, 0x21, n.toByte()))
		val left = leftMarginDots.coerceAtLeast(0).coerceAtMost(255)
		write(byteArrayOf(0x1D, 0x4C, (left and 0xFF).toByte(), ((left shr 8) and 0xFF).toByte()))

		val contentWidthDots = pageWidthDots?.let { it - leftMarginDots - rightMarginDots }?.coerceAtLeast(48)
		val baseCharWidthDots = 12
		val charWidthDots = baseCharWidthDots * (w + 1)
		val maxCharsPerLine = contentWidthDots?.let { if (charWidthDots > 0) it / charWidthDots else null }

		fun wrapLines(src: String): List<String> {
			if (maxCharsPerLine == null || maxCharsPerLine <= 0) return src.replace("\r\n", "\n").split("\n")
			val outLines = mutableListOf<String>()
			src.replace("\r\n", "\n").split("\n").forEach { line ->
				var idx = 0
				while (idx < line.length) {
					val end = (idx + maxCharsPerLine).coerceAtMost(line.length)
					outLines.add(line.substring(idx, end))
					idx = end
				}
			}
			return outLines
		}

		var lineCounter = 0
		wrapLines(text).forEach { line ->
			write(line.toByteArray(charset))
			write(byteArrayOf(0x0A))
			lineCounter++
			if (linesPerPage != null && linesPerPage > 0 && lineCounter >= linesPerPage) {
				write(byteArrayOf(0x1B, 0x64, 0x02))
				write(byteArrayOf(0x1D, 0x56, 0x42, 0x03))
				lineCounter = 0
				write(byteArrayOf(0x1B, 0x40))
				write(byteArrayOf(0x1B, 0x33, lineSpacing.toByte()))
				write(byteArrayOf(0x1D, 0x21, n.toByte()))
				write(byteArrayOf(0x1D, 0x4C, (left and 0xFF).toByte(), ((left shr 8) and 0xFF).toByte()))
			}
		}
		write(byteArrayOf(0x1B, 0x64, 0x02))
		write(byteArrayOf(0x1D, 0x56, 0x42, 0x03))
	}

	/**
	 * Print bitmap image using ESC/POS commands
	 * 
	 * @param bitmap The bitmap to print
	 * @param width Target width in dots (e.g., 384 for 58mm, 576 for 80mm)
	 */
	@Throws(IOException::class)
	fun printImage(bitmap: android.graphics.Bitmap, width: Int = 384) {
		val conn = connection ?: throw IOException("Not connected")
		val out = endpointOut ?: throw IOException("No OUT endpoint")

		fun write(bytes: ByteArray) {
			val sent = conn.bulkTransfer(out, bytes, bytes.size, 3000)
			if (sent <= 0) throw IOException("USB write failed")
		}
		
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
		write(byteArrayOf(0x1B, 0x40)) // Initialize
		write(byteArrayOf(0x1B, 0x33, 0x18)) // Set line spacing
		for (y in 0 until height) {
			write(byteArrayOf(0x1B, 0x2A, 0x00, widthBytes.toByte(), (widthBytes and 0xFF).toByte()))
			write(imageData.sliceArray(y * widthBytes until (y + 1) * widthBytes))
			write(byteArrayOf(0x0A)) // Line feed
		}
		write(byteArrayOf(0x1B, 0x33, 0x30)) // Reset line spacing
		write(byteArrayOf(0x1B, 0x64, 0x02)) // Feed 2 lines
		write(byteArrayOf(0x1D, 0x56, 0x42, 0x03)) // Cut paper
		
		scaledBitmap.recycle()
	}

	fun close() {
		try { connection?.releaseInterface(usbInterface) } catch (_: Exception) {}
		try { connection?.close() } catch (_: Exception) {}
		connection = null
		endpointOut = null
		usbInterface = null
	}
}
