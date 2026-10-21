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
		linesPerPage: Int? = null,
		cutMode: String = "partial_cut"
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
				cutBytes(cutMode)?.let { write(it) }
				lineCounter = 0
				write(byteArrayOf(0x1B, 0x40))
				write(byteArrayOf(0x1B, 0x33, lineSpacing.toByte()))
				write(byteArrayOf(0x1D, 0x21, n.toByte()))
				write(byteArrayOf(0x1D, 0x4C, (left and 0xFF).toByte(), ((left shr 8) and 0xFF).toByte()))
			}
		}
		write(byteArrayOf(0x1B, 0x64, 0x02))
		cutBytes(cutMode)?.let { write(it) }
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
		
		val target = if (bitmap.width == width) {
			bitmap
		} else {
			val aspectRatio = bitmap.height.toFloat() / bitmap.width.toFloat()
			val height = (width * aspectRatio).toInt().coerceAtLeast(1)
			android.graphics.Bitmap.createScaledBitmap(bitmap, width, height, true)
		}
		val widthBytes = (width + 7) / 8
		write(byteArrayOf(0x1B, 0x40))
		var top = 0
		while (top < target.height) {
			val band = minOf(256, target.height - top)
			val imageData = ByteArray(widthBytes * band)
			for (y in 0 until band) {
				for (x in 0 until width) {
					val pixel = target.getPixel(x, top + y)
					val gray = (
						android.graphics.Color.red(pixel) +
							android.graphics.Color.green(pixel) +
							android.graphics.Color.blue(pixel)
						) / 3
					if (gray < 160) {
						val byteIndex = y * widthBytes + x / 8
						val bitIndex = 7 - (x % 8)
						imageData[byteIndex] = (imageData[byteIndex].toInt() or (1 shl bitIndex)).toByte()
					}
				}
			}
			write(
				byteArrayOf(
					0x1D, 0x76, 0x30, 0x00,
					(widthBytes and 0xFF).toByte(),
					((widthBytes shr 8) and 0xFF).toByte(),
					(band and 0xFF).toByte(),
					((band shr 8) and 0xFF).toByte()
				)
			)
			var offset = 0
			while (offset < imageData.size) {
				val end = minOf(offset + 4096, imageData.size)
				write(imageData.copyOfRange(offset, end))
				offset = end
			}
			top += band
		}
		write(byteArrayOf(0x1B, 0x64, 0x03))
		write(byteArrayOf(0x1D, 0x56, 0x42, 0x00))
		if (target !== bitmap && !target.isRecycled) target.recycle()
	}

	fun close() {
		try { connection?.releaseInterface(usbInterface) } catch (_: Exception) {}
		try { connection?.close() } catch (_: Exception) {}
		connection = null
		endpointOut = null
		usbInterface = null
	}

	private fun cutBytes(cutMode: String): ByteArray? = when (cutMode.lowercase()) {
		"no_cut", "none", "continue" -> null
		"full_cut", "full" -> byteArrayOf(0x1D, 0x56, 0x00)
		else -> byteArrayOf(0x1D, 0x56, 0x42, 0x03)
	}
}
