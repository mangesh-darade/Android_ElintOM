package com.elintpos.wrapper.printer.vendor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.util.Log
import com.elintpos.wrapper.printer.CashSale
import com.elintpos.wrapper.printer.K8BillBitmap
import com.elintpos.wrapper.printer.K8SerialPorts
import com.elintpos.wrapper.printer.ReceiptLayout
import com.elintpos.wrapper.printer.ReceiptText
import com.icod.serial.SerialPortFinder
import com.szsicod.print.escpos.PrinterAPI
import com.szsicod.print.io.InterfaceAPI
import com.szsicod.print.io.SerialAPI
import com.szsicod.print.io.USBAPI
import java.io.File
import java.nio.charset.Charset

/**
 * Built-in printer on the TecMI K8 kiosk.
 *
 * Uses ICOD SDK 3.1.8 (icod_3.1.8.jar) the same way as the vendor demo:
 * load usb1.0 + serial_icod + image_icod, then PrinterAPI.connect(USBAPI).
 * If no USB printer answers, a visible serial port is tried with SerialAPI.
 *
 * printString() encodes as GBK, so bills with ₹ or Devanagari go out as a raster.
 */
class TecMiK8Printer(private val context: Context) {

    data class Result(val success: Boolean, val message: String)

    fun isAvailable(): Boolean = libsLoaded

    fun isConnected(): Boolean {
        if (!libsLoaded) return false
        return try {
            PrinterAPI.getInstance().isConnect
        } catch (e: Throwable) {
            Log.w(TAG, "isConnected failed", e)
            false
        }
    }

    fun connect(): Result {
        if (!libsLoaded) {
            return Result(false, loadError ?: "TecMI K8 native libraries failed to load")
        }
        return synchronized(LOCK) {
            try {
                val printer = PrinterAPI.getInstance()
                if (printer.isConnect) {
                    Result(true, "TecMI K8 printer already connected")
                } else {
                    connectTransport(printer)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Connect failed", e)
                Result(false, "TecMI K8 connect error: ${e.message}")
            }
        }
    }

    fun disconnect(): Result {
        if (!libsLoaded) return Result(false, "TecMI K8 SDK not available")
        return synchronized(LOCK) {
            try {
                val printer = PrinterAPI.getInstance()
                if (!printer.isConnect) {
                    connectedTransport = null
                    Result(true, "TecMI K8 printer already disconnected")
                } else {
                    val code = printer.disconnect()
                    connectedTransport = null
                    if (code == PrinterAPI.SUCCESS) {
                        Result(true, "Disconnected TecMI K8 printer")
                    } else {
                        Result(false, "TecMI K8 disconnect failed (code $code)")
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Disconnect failed", e)
                Result(false, "TecMI K8 disconnect error: ${e.message}")
            }
        }
    }

    fun printText(
        text: String,
        lineSpacing: Int = 0,
        cutMode: String = "full_cut",
        paperWidth: Int = DEFAULT_PAPER_WIDTH
    ): Result {
        val prepared = ReceiptText.prepare(text)
        if (prepared.isBlank() || prepared.contains("<?php") || prepared.contains("function(")) {
            return Result(false, "Invoice text was not found")
        }
        val bill = prepared.contains("TAX INVOICE", ignoreCase = true) ||
            prepared.contains("Item Name", ignoreCase = true) ||
            prepared.contains("Invoice No", ignoreCase = true) ||
            prepared.contains("Grand Total", ignoreCase = true)
        if (bill || ReceiptLayout.hasTable(prepared)) {
            return try {
                val bitmap = K8BillBitmap.renderRows(ReceiptLayout.build(prepared), paperWidth)
                try {
                    withCashDrawer(text, printBitmap(bitmap, cutMode))
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Aligned receipt failed", e)
                Result(false, "TecMI K8 print error: ${e.message}")
            }
        }
        val plain = ReceiptText.flatten(prepared)
        if (!fitsGbk(plain)) {
            Log.i(TAG, "Text is outside GBK, printing as raster")
            val bitmap = K8BillBitmap.renderText(plain, paperWidth)
            return try {
                withCashDrawer(plain, printBitmap(bitmap, cutMode))
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
        val ready = connect()
        if (!ready.success) return ready
        return synchronized(LOCK) {
            try {
                val printer = PrinterAPI.getInstance()
                if (lineSpacing > 0) {
                    printer.setLineSpace(lineSpacing)
                }
                printer.setAlignMode(0)
                val code = printer.printString(plain)
                if (code != PrinterAPI.SUCCESS) {
                    return@synchronized Result(false, "TecMI K8 print failed (code $code)")
                }
                finishJob(printer, cutMode)
                Result(true, "Printed via TecMI K8")
            } catch (e: Throwable) {
                Log.e(TAG, "printText failed", e)
                Result(false, "TecMI K8 print error: ${e.message}")
            }
        }.let { withCashDrawer(text, it) }
    }

    fun printBitmap(bitmap: Bitmap, cutMode: String = "full_cut"): Result {
        val ready = connect()
        if (!ready.success) return ready
        return synchronized(LOCK) {
            printBitmapLocked(bitmap, cutMode)
        }
    }

    fun printBarcode(
        data: String,
        barcodeType: Int = 73,
        height: Int = 120,
        width: Int = 2,
        cutMode: String = "full_cut"
    ): Result {
        val ready = connect()
        if (!ready.success) return ready
        return synchronized(LOCK) {
            try {
                val printer = PrinterAPI.getInstance()
                printer.setBarCodeWidth(width)
                printer.setBarCodeHeight(height)
                val code = printer.printBarCode(barcodeType, data.length, data)
                if (code != PrinterAPI.SUCCESS) {
                    return@synchronized Result(false, "TecMI K8 barcode print failed (code $code)")
                }
                finishJob(printer, cutMode)
                Result(true, "Barcode printed via TecMI K8")
            } catch (e: Throwable) {
                Log.e(TAG, "printBarcode failed", e)
                Result(false, "TecMI K8 barcode print error: ${e.message}")
            }
        }
    }

    fun printQRCode(data: String, size: Int = 6, cutMode: String = "full_cut"): Result {
        val ready = connect()
        if (!ready.success) return ready
        return synchronized(LOCK) {
            try {
                val printer = PrinterAPI.getInstance()
                val code = printer.printQRCode(data, size.coerceIn(1, 16), false)
                if (code != PrinterAPI.SUCCESS) {
                    return@synchronized Result(false, "TecMI K8 QR print failed (code $code)")
                }
                finishJob(printer, cutMode)
                Result(true, "QR code printed via TecMI K8")
            } catch (e: Throwable) {
                Log.e(TAG, "printQRCode failed", e)
                Result(false, "TecMI K8 QR print error: ${e.message}")
            }
        }
    }

    /**
     * Pulse the cash drawer. ICOD pin 0, on/off times are the SDK units.
     */
    fun openCashDrawer(pin: Int = 0, onTime: Int = 100, offTime: Int = 100): Result {
        val ready = connect()
        if (!ready.success) return ready
        return synchronized(LOCK) {
            try {
                val code = PrinterAPI.getInstance().openCashDrawer(
                    pin.coerceIn(0, 1),
                    onTime.coerceIn(1, 255),
                    offTime.coerceIn(1, 255)
                )
                if (code == PrinterAPI.SUCCESS) {
                    Result(true, "Cash drawer opened")
                } else {
                    Result(false, "TecMI K8 cash drawer failed (code $code)")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "openCashDrawer failed", e)
                Result(false, "TecMI K8 cash drawer error: ${e.message}")
            }
        }
    }

    fun selfTest(): Result {
        val ready = connect()
        if (!ready.success) return ready
        return synchronized(LOCK) {
            try {
                val code = PrinterAPI.getInstance().selfTestPage()
                if (code == PrinterAPI.SUCCESS) {
                    Result(true, "TecMI K8 self-test sent")
                } else {
                    Result(false, "TecMI K8 self-test failed (code $code)")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "selfTest failed", e)
                Result(false, "TecMI K8 self-test error: ${e.message}")
            }
        }
    }

    fun statusText(): Result {
        val ready = connect()
        if (!ready.success) return ready
        return synchronized(LOCK) {
            try {
                val printer = PrinterAPI.getInstance()
                // Demo bit flags belong to getStatus(). getPrinterStatus() uses a
                // different code and can report "error" while the printer is idle.
                val code = printer.getStatus()
                val type = printer.printerType ?: ""
                val version = printer.printerVersion ?: ""
                val detail = describeStatus(code)
                Result(
                    !isHardFault(code),
                    "status=$code ($detail), type=$type, version=$version, transport=${connectedTransport ?: "unknown"}"
                )
            } catch (e: Throwable) {
                Log.e(TAG, "status failed", e)
                Result(false, "TecMI K8 status error: ${e.message}")
            }
        }
    }

    private fun connectTransport(printer: PrinterAPI): Result {
        val usbCode = connectUsbWithPermissionRetry(printer)
        if (usbCode == PrinterAPI.SUCCESS || printer.isConnect) {
            connectedTransport = "usb"
            return Result(true, "Connected to TecMI K8 printer via USB")
        }
        releaseQuietly(printer)
        val serial = connectSerial(printer)
        if (serial != null) return serial
        return Result(false, "TecMI K8 connect failed (USB code $usbCode, no serial printer port)")
    }

    /**
     * USBAPI asks for com.szsicod.print.USB_PERMISSION and then checks at once.
     * If a device still has no permission, wait until the user answers the dialog
     * (or the wait expires) and connect once more.
     */
    private fun connectUsbWithPermissionRetry(printer: PrinterAPI): Int {
        val devices = usbDevices()
        if (devices.isEmpty()) {
            val code = connectIo(printer, USBAPI(context))
            return if (printer.isConnect) PrinterAPI.SUCCESS else code
        }
        val permittedBefore = devices.filter { hasUsbPermission(it) }.map { it.deviceName }.toSet()
        val waitingOnDialog = devices.any { !hasUsbPermission(it) }
        var code = connectIo(printer, USBAPI(context))
        if (code == PrinterAPI.SUCCESS || printer.isConnect) return PrinterAPI.SUCCESS
        if (!waitingOnDialog) {
            releaseQuietly(printer)
            code = connectIo(printer, USBAPI(context))
            return if (printer.isConnect) PrinterAPI.SUCCESS else code
        }
        Log.i(TAG, "USB permission dialog is open, waiting up to ${USB_PERMISSION_WAIT_MS}ms")
        val deadline = SystemClock.elapsedRealtime() + USB_PERMISSION_WAIT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (printer.isConnect) return PrinterAPI.SUCCESS
            val permittedNow = usbDevices().filter { hasUsbPermission(it) }.map { it.deviceName }.toSet()
            if (permittedNow != permittedBefore) {
                Log.i(TAG, "USB permission changed, connecting again")
                releaseQuietly(printer)
                code = connectIo(printer, USBAPI(context))
                return if (printer.isConnect) PrinterAPI.SUCCESS else code
            }
            try {
                Thread.sleep(USB_PERMISSION_POLL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        if (printer.isConnect) return PrinterAPI.SUCCESS
        releaseQuietly(printer)
        code = connectIo(printer, USBAPI(context))
        return if (printer.isConnect) PrinterAPI.SUCCESS else code
    }

    private fun connectSerial(printer: PrinterAPI): Result? {
        val ports = serialCandidates()
        if (ports.isEmpty()) {
            Log.i(TAG, "No serial printer port on this device")
            return null
        }
        printer.setWaitTime(SERIAL_PROBE_WAIT_MS)
        val deadline = SystemClock.elapsedRealtime() + SERIAL_PROBE_BUDGET_MS
        var lastCode = PrinterAPI.FAIL
        for (path in ports) {
            val file = File(path)
            if (!file.exists()) continue
            for (baud in K8SerialPorts.BAUDS) {
                if (SystemClock.elapsedRealtime() > deadline) {
                    printer.setWaitTime(NORMAL_WAIT_MS)
                    Log.w(TAG, "Serial probe stopped at time budget")
                    return null
                }
                Log.i(TAG, "Trying serial printer $path @ $baud")
                releaseQuietly(printer)
                lastCode = try {
                    connectIo(printer, SerialAPI(file, baud, 0))
                } catch (e: Throwable) {
                    Log.w(TAG, "Serial connect failed for $path @ $baud", e)
                    PrinterAPI.FAIL
                }
                if (lastCode != PrinterAPI.SUCCESS && !printer.isConnect) continue
                val status = try {
                    printer.getStatus()
                } catch (e: Throwable) {
                    Log.w(TAG, "Serial status failed for $path @ $baud", e)
                    -1
                }
                if (status >= 0) {
                    printer.setWaitTime(NORMAL_WAIT_MS)
                    connectedTransport = path
                    return Result(true, "Connected to TecMI K8 printer via $path @ $baud")
                }
                Log.w(TAG, "Serial $path @ $baud opened but did not answer (status $status)")
                releaseQuietly(printer)
            }
        }
        printer.setWaitTime(NORMAL_WAIT_MS)
        Log.w(TAG, "Serial fallback failed (last code $lastCode) ports=$ports")
        return null
    }

    private fun connectIo(printer: PrinterAPI, io: InterfaceAPI): Int {
        return try {
            printer.connect(io)
        } catch (e: Throwable) {
            Log.w(TAG, "printer.connect failed", e)
            PrinterAPI.FAIL
        }
    }

    private fun releaseQuietly(printer: PrinterAPI) {
        if (!printer.isConnect) return
        try {
            printer.disconnect()
        } catch (e: Throwable) {
            Log.w(TAG, "disconnect before next transport failed", e)
        }
    }

    private fun usbDevices(): List<UsbDevice> {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return emptyList()
        return manager.deviceList.values.toList()
    }

    private fun hasUsbPermission(device: UsbDevice): Boolean {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        return manager.hasPermission(device)
    }

    private fun serialCandidates(): List<String> {
        val found = try {
            SerialPortFinder.getAllDevicesPath()?.filterNotNull().orEmpty()
        } catch (e: Throwable) {
            Log.w(TAG, "Serial port scan failed", e)
            emptyList()
        }
        return K8SerialPorts.candidates(found, connectedTransport?.takeIf { it.startsWith("/") })
    }

    private fun withCashDrawer(text: String, result: Result): Result {
        if (!result.success || !CashSale.shouldOpenDrawer(text)) return result
        val drawer = openCashDrawer()
        val message = if (drawer.success) {
            "${result.message}. Cash drawer opened"
        } else {
            "${result.message}. Cash drawer: ${drawer.message}"
        }
        return Result(true, message)
    }

    private fun printBitmapLocked(bitmap: Bitmap, cutMode: String): Result {
        return try {
            val printer = PrinterAPI.getInstance()
            val aligned = alignWidth(bitmap)
            val slices = sliceBitmap(aligned)
            try {
                for (slice in slices) {
                    val code = printer.printRasterBitmap(slice)
                    if (code != PrinterAPI.SUCCESS) {
                        return Result(false, "TecMI K8 bitmap print failed (code $code)")
                    }
                }
            } finally {
                slices.forEach { slice ->
                    if (slice !== aligned && slice !== bitmap && !slice.isRecycled) slice.recycle()
                }
                if (aligned !== bitmap && !aligned.isRecycled) aligned.recycle()
            }
            finishJob(printer, cutMode)
            Result(true, "Bitmap printed via TecMI K8")
        } catch (e: Throwable) {
            Log.e(TAG, "printBitmap failed", e)
            Result(false, "TecMI K8 bitmap print error: ${e.message}")
        }
    }

    private fun alignWidth(source: Bitmap): Bitmap {
        val alignedWidth = (source.width + 7) and 7.inv()
        if (alignedWidth == source.width) return source
        val padded = Bitmap.createBitmap(alignedWidth, source.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(padded)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(source, 0f, 0f, null)
        return padded
    }

    private fun sliceBitmap(source: Bitmap): List<Bitmap> {
        if (source.height <= RASTER_SLICE_HEIGHT) return listOf(source)
        val slices = mutableListOf<Bitmap>()
        var y = 0
        while (y < source.height) {
            val height = minOf(RASTER_SLICE_HEIGHT, source.height - y)
            slices.add(Bitmap.createBitmap(source, 0, y, source.width, height))
            y += height
        }
        return slices
    }

    private fun finishJob(printer: PrinterAPI, cutMode: String) {
        printer.printFeed()
        when (cutMode.lowercase()) {
            "no_cut", "none" -> Unit
            "partial_cut", "partial" -> printer.halfCut()
            else -> printer.cutPaper(66, 0)
        }
    }

    private fun fitsGbk(text: String): Boolean {
        return try {
            Charset.forName("GBK").newEncoder().canEncode(text)
        } catch (e: Throwable) {
            Log.w(TAG, "GBK check failed", e)
            false
        }
    }

    private fun isHardFault(status: Int): Boolean {
        if (status < 0) return true
        val fault = PrinterAPI.OFF_LINE or
            PrinterAPI.HAPPENERROR or
            PrinterAPI.OUTOFPAPER or
            PrinterAPI.OUTOFPAPER_8 or
            PrinterAPI.BOXOPEN or
            PrinterAPI.MACHINEERROR or
            PrinterAPI.NOTCORRECTERROR or
            PrinterAPI.CORRECTINGERROR
        return status and fault != 0
    }

    private fun describeStatus(status: Int): String {
        if (status < 0) return "transmission error"
        val parts = mutableListOf<String>()
        if (status and PrinterAPI.PAPERFEW != 0) parts.add("low paper")
        if (status and PrinterAPI.OUTOFPAPER != 0 || status and PrinterAPI.OUTOFPAPER_8 != 0) parts.add("out of paper")
        if (status and PrinterAPI.HAPPENERROR != 0) parts.add("error")
        if (status and PrinterAPI.BOXOPEN != 0) parts.add("cover open")
        if (status and PrinterAPI.OFF_LINE != 0) parts.add("offline")
        if (status and PrinterAPI.FEEDING != 0 || status and PrinterAPI.FEEDING_10 != 0) parts.add("feeding")
        if (status and PrinterAPI.MACHINEERROR != 0) parts.add("mechanical error")
        if (status and PrinterAPI.CORRECTINGERROR != 0) parts.add("recoverable error")
        if (status and PrinterAPI.NOTCORRECTERROR != 0) parts.add("unrecoverable error")
        return if (parts.isEmpty()) "Normal" else parts.joinToString(", ")
    }

    companion object {
        private const val TAG = "TecMiK8Printer"
        private const val USB_PERMISSION_WAIT_MS = 8000L
        private const val USB_PERMISSION_POLL_MS = 400L
        private const val SERIAL_PROBE_WAIT_MS = 1000
        private const val SERIAL_PROBE_BUDGET_MS = 8000L
        private const val NORMAL_WAIT_MS = 3000
        private const val RASTER_SLICE_HEIGHT = 1024
        private const val DEFAULT_PAPER_WIDTH = 576
        private val LOCK = Any()

        @Volatile
        private var libsLoaded: Boolean = false

        @Volatile
        private var loadError: String? = null

        @Volatile
        private var connectedTransport: String? = null

        init {
            loadNativeLibraries()
        }

        private fun loadNativeLibraries() {
            if (libsLoaded) return
            synchronized(LOCK) {
                if (libsLoaded) return
                try {
                    System.loadLibrary("usb1.0")
                    System.loadLibrary("serial_icod")
                    System.loadLibrary("image_icod")
                    Class.forName("com.szsicod.print.escpos.PrinterAPI")
                    libsLoaded = true
                    Log.i(TAG, "ICOD TecMI K8 SDK loaded")
                } catch (e: Throwable) {
                    libsLoaded = false
                    loadError = e.message ?: e.javaClass.simpleName
                    Log.e(TAG, "Failed to load TecMI K8 SDK", e)
                }
            }
        }
    }
}
