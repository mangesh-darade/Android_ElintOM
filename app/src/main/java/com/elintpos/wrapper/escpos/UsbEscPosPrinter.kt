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

	fun close() {
		try { connection?.releaseInterface(usbInterface) } catch (_: Exception) {}
		try { connection?.close() } catch (_: Exception) {}
		connection = null
		endpointOut = null
		usbInterface = null
	}
}
