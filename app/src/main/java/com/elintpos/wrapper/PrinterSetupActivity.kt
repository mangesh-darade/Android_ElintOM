package com.elintpos.wrapper

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.elintpos.wrapper.escpos.BluetoothEscPosPrinter
import com.elintpos.wrapper.escpos.UsbEscPosPrinter
import com.elintpos.wrapper.printer.PrinterConfigManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * PrinterSetupActivity - Printer Configuration Screen
 *
 * Purpose: Shows available printers and allows configuration before loading the main webview.
 * This screen appears after splash screen and before MainActivity.
 *
 * Flow:
 * 1. App launches -> SplashActivity -> PrinterSetupActivity
 * 2. User configures printer settings
 * 3. User clicks "Continue" -> MainActivity (webview loads)
 */
class PrinterSetupActivity : ComponentActivity() {

    private lateinit var printerConfigManager: PrinterConfigManager
    private lateinit var prefs: SharedPreferences

    // Printer discovery
    private lateinit var bluetoothPrinter: BluetoothEscPosPrinter
    private lateinit var usbPrinter: UsbEscPosPrinter

    // Permission launcher for Bluetooth permissions
    private lateinit var bluetoothPermissionLauncher: ActivityResultLauncher<Array<String>>

    // UI Components
    private lateinit var printerRecyclerView: RecyclerView
    private lateinit var printerModelSpinner: Spinner
    private lateinit var connectionTypeSpinner: Spinner
    private lateinit var continueButton: Button
    private lateinit var skipButton: Button
    private lateinit var refreshButton: Button
    private lateinit var statusText: TextView
    // Advanced Options Checkboxes
    // These checkboxes control app-wide behavior preferences
    private lateinit var enabledPrinterDialogCheckBox: CheckBox
    private lateinit var kioskAutoBootCheckBox: CheckBox

    // Printer data
    private val availablePrinters = mutableListOf<PrinterInfo>()
    private var selectedPrinter: PrinterInfo? = null
    private var selectedModel: String = "XP-80"
    private var selectedConnectionType: String = "bluetooth"

    // Printer models mapping
    private val printerModels = listOf(
        "XP-58C", "XP-80", "XP-80C", "XP-90", "XP-76", "XP-76C", "XP-F800"
    )

    // Connection types
    private val connectionTypes = listOf(
        "Bluetooth", "USB", "LAN"
    )

    data class PrinterInfo(
        val name: String,
        val address: String,
        val type: String, // "bluetooth", "usb", "lan"
        val model: String? = null,
        val device: Any? = null // BluetoothDevice or UsbDevice
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_printer_setup)

        // Initialize managers
        printerConfigManager = PrinterConfigManager(this)
        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        bluetoothPrinter = BluetoothEscPosPrinter(this)
        usbPrinter = UsbEscPosPrinter(this)

        // Initialize permission launcher
        bluetoothPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val allGranted = permissions.values.all { it }
            if (allGranted) {
                // Permissions granted, discover printers
                discoverPrinters()
            } else {
                updateStatus("Bluetooth permissions denied. Please grant permissions in settings.")
            }
        }

        // Initialize UI
        initializeViews()
        setupSpinners()
        setupRecyclerView()
        setupButtons()

        // STEP 1: Check if already configured
        if (isPrinterConfigured()) {
            // Already configured - allow reconfiguration or skip
            // Show skip button for reconfiguration
            skipButton.visibility = View.VISIBLE
            discoverPrinters()
        } else {
            // STEP 1: First time setup - MANDATORY
            // Hide skip button - printer setup is mandatory
            skipButton.visibility = View.GONE
            discoverPrinters()
        }
    }

    private fun initializeViews() {
        printerRecyclerView = findViewById(R.id.printerRecyclerView)
        printerModelSpinner = findViewById(R.id.printerModelSpinner)
        connectionTypeSpinner = findViewById(R.id.connectionTypeSpinner)
        continueButton = findViewById(R.id.continueButton)
        skipButton = findViewById(R.id.skipButton)
        refreshButton = findViewById(R.id.refreshButton)
        statusText = findViewById(R.id.statusText)
        enabledPrinterDialogCheckBox = findViewById(R.id.enabledPrinterDialogCheckBox)
        kioskAutoBootCheckBox = findViewById(R.id.kioskAutoBootCheckBox)
        
        // Load saved preferences
        loadPreferences()
        
        // Setup checkbox listeners for immediate actions
        setupCheckboxListeners()
    }
    
    /**
     * Load saved preferences for checkboxes
     * 
     * Backend Integration:
     * - These preferences are used throughout the app for print and auto-start functionality
     * - See PRINTER_SETUP_CHECKBOXES_BACKEND.md for complete documentation
     */
    private fun loadPreferences() {
        // Load "Show Print Dialog" preference (enabled printer dialog)
        // Preference Key: "show_print_dialog"
        // Default: false (unchecked)
        // 
        // Backend Usage:
        // - JavaScriptBridge.kt: Controls whether to show Android print dialog or direct print
        // - When checked (true): Shows Android system print dialog - user can select printer
        // - When unchecked (false): Direct print to configured printer - no dialog, immediate printing
        val showPrintDialog = prefs.getBoolean("show_print_dialog", false)
        enabledPrinterDialogCheckBox.isChecked = showPrintDialog
        
        // Load "Auto Start Enabled" preference (kiosk auto boot)
        // Preference Key: "auto_start_enabled"
        // Default: true (checked - auto-start enabled by default)
        // 
        // Backend Usage:
        // - BootReceiver.kt: Checks this preference on device boot/reboot
        // - StartupForegroundService.kt: Launches app if enabled
        // - When checked (true): App automatically starts on device boot/reboot
        // - When unchecked (false): App requires manual start - no auto-launch
        // 
        // Also check kiosk_enabled preference for lock task mode
        // If either is enabled, show checkbox as checked
        val autoStartEnabled = prefs.getBoolean("auto_start_enabled", true)
        val kioskEnabled = prefs.getBoolean("kiosk_enabled", false)
        // Checkbox is checked if either auto-start or kiosk mode is enabled
        kioskAutoBootCheckBox.isChecked = autoStartEnabled || kioskEnabled
    }
    
    /**
     * Setup checkbox listeners to handle immediate actions when checkboxes are selected
     * 
     * Advanced Options Behavior:
     * 1. "Enabled Printer Dialog" checkbox:
     *    - When checked: Opens printer selection dialog immediately
     *    - When unchecked: No action (preference saved for future use)
     * 
     * 2. "Kiosk Model Auto Boot App Enabled" checkbox:
     *    - When checked: Enables kiosk mode (lock task mode) immediately
     *    - When unchecked: Disables kiosk mode (exits lock task mode) immediately
     */
    private fun setupCheckboxListeners() {
        // Listener for "Enabled Printer Dialog" checkbox
        enabledPrinterDialogCheckBox.setOnCheckedChangeListener { _, isChecked ->
            // Save preference immediately
            prefs.edit().putBoolean("show_print_dialog", isChecked).commit()
            
            if (isChecked) {
                // Checkbox is checked - open printer selection dialog
                showPrinterSelectionDialog()
            }
            // If unchecked, just save preference (no action needed)
        }
        
        // Listener for "Kiosk Model Auto Boot App Enabled" checkbox
        kioskAutoBootCheckBox.setOnCheckedChangeListener { _, isChecked ->
            // Save both preferences immediately
            // 1. auto_start_enabled: Controls auto-start on boot
            // 2. kiosk_enabled: Controls lock task mode (kiosk mode)
            prefs.edit()
                .putBoolean("auto_start_enabled", isChecked)
                .putBoolean("kiosk_enabled", isChecked)
                .commit()
            
            // Enable/disable kiosk mode immediately
            if (isChecked) {
                // Enable kiosk mode (lock task mode)
                enableKioskMode()
            } else {
                // Disable kiosk mode (exit lock task mode)
                disableKioskMode()
            }
        }
    }
    
    /**
     * Show printer selection dialog when "Enabled Printer Dialog" checkbox is checked
     * 
     * This dialog allows users to:
     * - View available printers
     * - Select a printer for testing
     * - Configure printer settings
     */
    private fun showPrinterSelectionDialog() {
        val profiles = printerConfigManager.getAllProfiles().filter { it.enabled }
        
        if (profiles.isEmpty()) {
            // No printers configured - show message
            android.app.AlertDialog.Builder(this)
                .setTitle("No Printers Configured")
                .setMessage("Please configure a printer first before enabling the print dialog.")
                .setPositiveButton("Configure Printer") { _, _ ->
                    // User can configure printer from this screen
                    // Just show a toast
                    Toast.makeText(this, "Please select and configure a printer below", Toast.LENGTH_LONG).show()
                }
                .setNegativeButton("Cancel") { dialog, _ ->
                    // Uncheck the checkbox since no printer is available
                    enabledPrinterDialogCheckBox.isChecked = false
                    dialog.dismiss()
                }
                .show()
            return
        }
        
        // Create list of printer names
        val printerNames = profiles.map { it.name }.toTypedArray()
        
        // Show dialog with printer list
        android.app.AlertDialog.Builder(this)
            .setTitle("Select Printer")
            .setMessage("Print dialog is now enabled. When printing, you'll be able to select from available printers.\n\nCurrently configured printers:")
            .setItems(printerNames) { _, which ->
                // User selected a printer - show info
                val selectedProfile = profiles[which]
                android.app.AlertDialog.Builder(this)
                    .setTitle("Printer Selected")
                    .setMessage("""
                        Printer: ${selectedProfile.name}
                        Type: ${selectedProfile.type.uppercase()}
                        Paper Width: ${selectedProfile.paperWidth} dots
                        
                        The print dialog will now show this printer as an option when printing.
                    """.trimIndent())
                    .setPositiveButton("OK", null)
                    .show()
            }
            .setPositiveButton("OK") { _, _ ->
                Toast.makeText(this, "Print dialog enabled. You can select printers when printing.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                // Uncheck the checkbox if user cancels
                enabledPrinterDialogCheckBox.isChecked = false
                dialog.dismiss()
            }
            .show()
    }
    
    /**
     * Enable kiosk mode (lock task mode) immediately
     * 
     * Kiosk mode features:
     * - App runs in lock task mode (pinned mode)
     * - User cannot exit app easily
     * - App automatically starts on boot (if enabled)
     * - Suitable for dedicated POS terminals
     */
    private fun enableKioskMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                // Start lock task mode (kiosk mode)
                startLockTask()
                
                // Show confirmation
                Toast.makeText(this, "Kiosk mode enabled. App is now locked to this screen.", Toast.LENGTH_SHORT).show()
                
                android.util.Log.d("PrinterSetupActivity", "Kiosk mode enabled - lock task started")
            } catch (e: Exception) {
                android.util.Log.e("PrinterSetupActivity", "Failed to enable kiosk mode", e)
                Toast.makeText(this, "Failed to enable kiosk mode: ${e.message}", Toast.LENGTH_LONG).show()
                
                // Uncheck checkbox if enabling failed
                kioskAutoBootCheckBox.isChecked = false
            }
        } else {
            // Android version too old for lock task mode
            Toast.makeText(this, "Kiosk mode requires Android 5.0 (Lollipop) or higher", Toast.LENGTH_LONG).show()
            kioskAutoBootCheckBox.isChecked = false
        }
    }
    
    /**
     * Disable kiosk mode (exit lock task mode) immediately
     * 
     * This allows the user to exit the app normally
     */
    private fun disableKioskMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                // Stop lock task mode (exit kiosk mode)
                stopLockTask()
                
                // Show confirmation
                Toast.makeText(this, "Kiosk mode disabled. You can now exit the app normally.", Toast.LENGTH_SHORT).show()
                
                android.util.Log.d("PrinterSetupActivity", "Kiosk mode disabled - lock task stopped")
            } catch (e: Exception) {
                android.util.Log.e("PrinterSetupActivity", "Failed to disable kiosk mode", e)
                Toast.makeText(this, "Failed to disable kiosk mode: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    /**
     * Save preferences when user clicks Continue or Skip
     * 
     * Backend Integration:
     * - Preferences are saved to SharedPreferences and persist across app restarts
     * - These preferences control app-wide behavior:
     *   1. Print dialog behavior (JavaScriptBridge.kt)
     *   2. Auto-start on boot behavior (BootReceiver.kt)
     * 
     * Called from:
     * - testPrintAndSave() when Continue button is clicked (line 336)
     * - skipButton.setOnClickListener when Skip is clicked (line 241)
     * 
     * IMPORTANT: Using commit() instead of apply() to ensure preferences are saved immediately
     * before navigation, so they're available when MainActivity loads.
     */
    private fun savePreferences() {
        // Save "Show Print Dialog" preference
        // Used by: JavaScriptBridge.printWebContent() to determine print dialog behavior
        // Logic: 
        //   - Checked (true) = showPrintDialog = true → Shows Android print dialog
        //   - Unchecked (false) = showPrintDialog = false → Direct print (no dialog)
        val showPrintDialog = enabledPrinterDialogCheckBox.isChecked
        prefs.edit().putBoolean("show_print_dialog", showPrintDialog).commit()
        
        // Save "Auto Start Enabled" and "Kiosk Enabled" preferences
        // Used by: 
        //   - BootReceiver.onReceive() to determine if app should auto-start on boot
        //   - MainActivity to determine if kiosk mode (lock task) should be enabled
        // Logic:
        //   - Checked (true) = autoStartEnabled = true, kioskEnabled = true → App auto-starts on boot AND kiosk mode enabled
        //   - Unchecked (false) = autoStartEnabled = false, kioskEnabled = false → Manual start required AND kiosk mode disabled
        val autoStartEnabled = kioskAutoBootCheckBox.isChecked
        prefs.edit()
            .putBoolean("auto_start_enabled", autoStartEnabled)
            .putBoolean("kiosk_enabled", autoStartEnabled)
            .commit()
        
        // Log for debugging
        android.util.Log.d("PrinterSetupActivity", "Preferences saved - showPrintDialog: $showPrintDialog, autoStartEnabled: $autoStartEnabled")
        
        // Note: Changes take effect immediately for print dialog
        // For auto-start: Changes take effect on next device boot/reboot
    }

    private fun setupSpinners() {
        // Printer Model Spinner
        val modelAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, printerModels)
        modelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        printerModelSpinner.adapter = modelAdapter
        printerModelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedModel = printerModels[position]
                updateStatus("Selected model: $selectedModel")
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Connection Type Spinner
        val connectionAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, connectionTypes)
        connectionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        connectionTypeSpinner.adapter = connectionAdapter
        connectionTypeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedConnectionType = when (connectionTypes[position].lowercase()) {
                    "bluetooth" -> "bluetooth"
                    "usb" -> "usb"
                    "lan" -> "lan"
                    else -> "bluetooth"
                }
                discoverPrinters()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupRecyclerView() {
        printerRecyclerView.layoutManager = LinearLayoutManager(this)
        printerRecyclerView.adapter = PrinterAdapter(availablePrinters) { printer ->
            val previousSelected = (printerRecyclerView.adapter as? PrinterAdapter)?.selectedPosition ?: -1
            selectedPrinter = printer
            updateStatus("Selected: ${printer.name}")
            // Highlight selected item
            val newSelection = availablePrinters.indexOf(printer)
            (printerRecyclerView.adapter as? PrinterAdapter)?.selectedPosition = newSelection
            if (previousSelected != -1) {
                printerRecyclerView.adapter?.notifyItemChanged(previousSelected)
            }
            if (newSelection != -1) {
                printerRecyclerView.adapter?.notifyItemChanged(newSelection)
            }
        }
    }

    private fun setupButtons() {
        // STEP 2: Test Print before Save (MANDATORY)
        continueButton.setOnClickListener {
            if (selectedPrinter != null) {
                // First test print, then save
                testPrintAndSave()
            } else {
                Toast.makeText(this, "Please select a printer", Toast.LENGTH_SHORT).show()
            }
        }

        // STEP 1: Skip button should be hidden or disabled for first-time setup
        // Only allow skip if printer is already configured (for reconfiguration scenarios)
        skipButton.setOnClickListener {
            if (isPrinterConfigured()) {
                // Already configured, allow skip (for reconfiguration scenarios)
                // Save preferences even when skipping
                savePreferences()
                navigateToMainActivity()
            } else {
                // First time setup - don't allow skip
                Toast.makeText(this, "Please configure a printer to continue", Toast.LENGTH_LONG).show()
            }
        }

        refreshButton.setOnClickListener {
            discoverPrinters()
        }
    }
    
    /**
     * STEP 2: Test Print before saving configuration
     * This ensures printer works before proceeding
     */
    private fun testPrintAndSave() {
        selectedPrinter?.let { printer ->
            // Show progress
            updateStatus("Testing printer...")
            continueButton.isEnabled = false
            
            // Create temporary config for testing
            val connectionParams = when (printer.type) {
                "bluetooth" -> mapOf("mac" to printer.address)
                "usb" -> mapOf(
                    "deviceName" to printer.name,
                    "address" to printer.address
                )
                "lan" -> {
                    val parts = printer.address.split(":")
                    mapOf(
                        "ip" to (parts.getOrNull(0) ?: "192.168.1.100"),
                        "port" to (parts.getOrNull(1) ?: "9100")
                    )
                }
                else -> emptyMap()
            }
            
            val paperWidth = when {
                selectedModel.contains("58") -> PrinterConfigManager.PAPER_58MM
                selectedModel.contains("90") -> PrinterConfigManager.PAPER_112MM
                else -> PrinterConfigManager.PAPER_80MM
            }
            
            val testConfig = PrinterConfigManager.PrinterConfig(
                type = printer.type,
                name = "${printer.name} (${selectedModel})",
                enabled = true,
                paperWidth = paperWidth,
                connectionParams = connectionParams,
                isDefault = true
            )
            
            // Test print in background
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val unifiedPrinterHandler = com.elintpos.wrapper.UnifiedPrinterHandler(this@PrinterSetupActivity)
                    
                    // Create test print content
                    val testText = """
                        ================================
                        PRINTER TEST
                        ================================
                        
                        Printer: ${printer.name}
                        Type: ${printer.type.uppercase()}
                        Model: $selectedModel
                        Paper: ${paperWidth} dots
                        
                        If you can read this,
                        your printer is working correctly!
                        
                        Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}
                        
                        ================================
                        END OF TEST
                        ================================
                    """.trimIndent()
                    
                    // Temporarily save config for testing
                    printerConfigManager.saveProfile(testConfig)
                    
                    // Test print
                    val result = unifiedPrinterHandler.print(testText, printer.type)
                    
                    // Return to main thread for UI update
                    withContext(Dispatchers.Main) {
                        continueButton.isEnabled = true
                        
                        if (result.success) {
                            // Test successful - save and continue
                            updateStatus("Test print successful! Saving configuration...")
                            savePrinterConfiguration()
                            savePreferences() // Save checkbox preferences
                            Toast.makeText(this@PrinterSetupActivity, "Printer test successful!", Toast.LENGTH_SHORT).show()
                            navigateToMainActivity()
                        } else {
                            // Test failed - show error
                            updateStatus("Test print failed: ${result.message}")
                            android.app.AlertDialog.Builder(this@PrinterSetupActivity)
                                .setTitle("Test Print Failed")
                                .setMessage("Printer test failed: ${result.message}\n\nPlease check:\n• Printer is powered on\n• Printer is connected\n• Paper is loaded")
                                .setPositiveButton("Retry") { _, _ -> testPrintAndSave() }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        continueButton.isEnabled = true
                        updateStatus("Test print error: ${e.message}")
                        android.app.AlertDialog.Builder(this@PrinterSetupActivity)
                            .setTitle("Test Print Error")
                            .setMessage("Error testing printer: ${e.message}")
                            .setPositiveButton("Retry") { _, _ -> testPrintAndSave() }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            }
        } ?: run {
            Toast.makeText(this, "Please select a printer", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun discoverPrinters() {
        val previousSize = availablePrinters.size
        availablePrinters.clear()
        printerRecyclerView.adapter?.notifyItemRangeRemoved(0, previousSize)
        updateStatus("Discovering printers...")

        when (selectedConnectionType) {
            "bluetooth" -> discoverBluetoothPrinters()
            "usb" -> discoverUsbPrinters()
            "lan" -> showLanConfiguration()
        }
    }

    @SuppressLint("MissingPermission")
    private fun discoverBluetoothPrinters() {
        if (!checkBluetoothPermissions()) {
            updateStatus("Requesting Bluetooth permissions...")
            requestBluetoothPermissions()
            return
        }

        val adapter: BluetoothAdapter? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val bluetoothManager = getSystemService(BluetoothManager::class.java)
            bluetoothManager.adapter
        } else {
            @Suppress("DEPRECATION")
            BluetoothAdapter.getDefaultAdapter()
        }

        if (adapter == null) {
            updateStatus("Bluetooth not available on this device")
            return
        }

        if (!adapter.isEnabled) {
            updateStatus("Please enable Bluetooth")
            return
        }

        try {
            val pairedDevices = bluetoothPrinter.getPairedPrinters()
            if (pairedDevices.isEmpty()) {
                updateStatus("No paired Bluetooth printers found")
            } else {
                pairedDevices.forEach { device ->
                    val printerInfo = PrinterInfo(
                        name = device.name ?: "Unknown Device",
                        address = device.address,
                        type = "bluetooth",
                        model = detectPrinterModel(device.name ?: ""),
                        device = device
                    )
                    availablePrinters.add(printerInfo)
                }
                updateStatus("Found ${pairedDevices.size} Bluetooth printer(s)")
                printerRecyclerView.adapter?.notifyItemRangeInserted(0, pairedDevices.size)
            }
        } catch (e: Exception) {
            updateStatus("Error discovering Bluetooth printers: ${e.message}")
        }
    }

    private fun discoverUsbPrinters() {
        try {
            val devices = usbPrinter.listPrinters()
            if (devices.isEmpty()) {
                updateStatus("No USB printers found")
            } else {
                devices.forEach { device ->
                    val printerInfo = PrinterInfo(
                        name = device.deviceName,
                        address = "${device.vendorId}:${device.productId}",
                        type = "usb",
                        model = detectPrinterModel(device.deviceName),
                        device = device
                    )
                    availablePrinters.add(printerInfo)
                }
                updateStatus("Found ${devices.size} USB printer(s)")
                printerRecyclerView.adapter?.notifyItemRangeInserted(0, devices.size)
            }
        } catch (e: Exception) {
            updateStatus("Error discovering USB printers: ${e.message}")
        }
    }

    private fun showLanConfiguration() {
        // For LAN, show input dialog or add manual entry option
        availablePrinters.add(PrinterInfo(
            name = "LAN Printer (Manual Configuration)",
            address = "192.168.1.100:9100",
            type = "lan",
            model = selectedModel
        ))
        updateStatus("LAN printer configuration available")
        printerRecyclerView.adapter?.notifyItemInserted(0)
    }

    private fun detectPrinterModel(deviceName: String): String {
        val name = deviceName.uppercase()
        return when {
            name.contains("XP-58C") || name.contains("58C") -> "XP-58C"
            name.contains("XP-80C") || name.contains("80C") -> "XP-80C"
            name.contains("XP-80") || name.contains("80") -> "XP-80"
            name.contains("XP-90") || name.contains("90") -> "XP-90"
            name.contains("XP-76C") || name.contains("76C") -> "XP-76C"
            name.contains("XP-76") || name.contains("76") -> "XP-76"
            name.contains("XP-F800") || name.contains("F800") -> "XP-F800"
            name.contains("TM-T20") -> "Epson TM-T20"
            name.contains("TM-T88") -> "Epson TM-T88"
            name.contains("TM-T90") -> "Epson TM-T90"
            name.contains("TM-T76") -> "Epson TM-T76"
            else -> selectedModel // Default to selected model
        }
    }

    private fun checkBluetoothPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                   PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bluetoothPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
                )
            )
        } else {
            // For older Android versions, permissions are granted at install time
            discoverPrinters()
        }
    }

    private fun savePrinterConfiguration() {
        selectedPrinter?.let { printer ->
            val connectionParams = when (printer.type) {
                "bluetooth" -> mapOf("mac" to printer.address)
                "usb" -> mapOf(
                    "deviceName" to printer.name,
                    "address" to printer.address
                )
                "lan" -> {
                    val parts = printer.address.split(":")
                    mapOf(
                        "ip" to (parts.getOrNull(0) ?: "192.168.1.100"),
                        "port" to (parts.getOrNull(1) ?: "9100")
                    )
                }
                else -> emptyMap()
            }

            val paperWidth = when {
                selectedModel.contains("58") -> PrinterConfigManager.PAPER_58MM
                selectedModel.contains("90") -> PrinterConfigManager.PAPER_112MM
                else -> PrinterConfigManager.PAPER_80MM
            }

            val config = PrinterConfigManager.PrinterConfig(
                type = printer.type,
                name = "${printer.name} (${selectedModel})",
                enabled = true,
                paperWidth = paperWidth,
                connectionParams = connectionParams,
                isDefault = true
            )

            val saved = printerConfigManager.saveProfile(config)

            if (saved) {
                // Mark as configured - this flag ensures setup screen is skipped forever
                // Use commit() instead of apply() to ensure it's saved before navigation
                prefs.edit().putBoolean("printer_configured", true).commit()

                Toast.makeText(this, "Printer configuration saved", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Failed to save configuration", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveDefaultConfiguration() {
        val config = PrinterConfigManager.PrinterConfig(
            type = "bluetooth",
            name = "Default Printer",
            enabled = true,
            paperWidth = PrinterConfigManager.PAPER_80MM,
            isDefault = true
        )

        val saved = printerConfigManager.saveProfile(config)

        if (saved) {
            // Mark as configured - this flag ensures setup screen is skipped forever
            // Use commit() instead of apply() to ensure it's saved immediately
            prefs.edit().putBoolean("printer_configured", true).commit()
        }
    }

    private fun isPrinterConfigured(): Boolean {
        // Check if printer_configured flag is set (set after first successful setup)
        val configured = prefs.getBoolean("printer_configured", false)

        // Also verify that at least one printer profile exists
        val hasProfiles = printerConfigManager.getAllProfiles().isNotEmpty()

        // Return true if either flag is set OR profiles exist (for backward compatibility)
        return configured || hasProfiles
    }

    private fun navigateToMainActivity() {
        try {
            val intent = Intent(this, MainActivity::class.java)
            // Add flags to ensure clean navigation
            // FLAG_ACTIVITY_NEW_TASK: Start in new task  
            // FLAG_ACTIVITY_CLEAR_TOP: Clear activities above MainActivity
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
            finish()
        } catch (e: Exception) {
            android.util.Log.e("PrinterSetupActivity", "Error navigating to MainActivity", e)
            // If navigation fails, try without flags as fallback
            try {
                val intent = Intent(this, MainActivity::class.java)
                startActivity(intent)
                finish()
            } catch (e2: Exception) {
                android.util.Log.e("PrinterSetupActivity", "Error in fallback navigation", e2)
                Toast.makeText(this, "Error starting app: ${e2.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateStatus(message: String) {
        statusText.text = message
    }

    // RecyclerView Adapter for Printers
    private class PrinterAdapter(
        private val printers: List<PrinterInfo>,
        private val onItemClick: (PrinterInfo) -> Unit
    ) : RecyclerView.Adapter<PrinterAdapter.PrinterViewHolder>() {

        var selectedPosition = -1

        class PrinterViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val nameText: TextView = itemView.findViewById(R.id.printerNameText)
            val addressText: TextView = itemView.findViewById(R.id.printerAddressText)
            val modelText: TextView = itemView.findViewById(R.id.printerModelText)
            val typeText: TextView = itemView.findViewById(R.id.printerTypeText)
            val container: LinearLayout = itemView.findViewById(R.id.printerItemContainer)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PrinterViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_printer, parent, false)
            return PrinterViewHolder(view)
        }

        override fun onBindViewHolder(holder: PrinterViewHolder, position: Int) {
            val printer = printers[position]
            holder.nameText.text = printer.name
            // STEP 2: Hide technical details - show user-friendly info only
            // Don't show MAC address, Vendor ID, etc. to user
            when (printer.type) {
                "bluetooth" -> {
                    // For Bluetooth, show connection type only (not MAC)
                    holder.addressText.text = "Bluetooth Connection"
                }
                "usb" -> {
                    // For USB, show connection type only (not Vendor ID)
                    holder.addressText.text = "USB Connection"
                }
                "lan" -> {
                    // For LAN, show IP only (user-friendly)
                    val ip = printer.address.split(":").firstOrNull() ?: printer.address
                    holder.addressText.text = "Network: $ip"
                }
                else -> {
                    holder.addressText.text = printer.type.uppercase()
                }
            }
            holder.modelText.text = printer.model ?: "Unknown Model"
            holder.typeText.text = printer.type.uppercase()

            // Highlight selected item
            if (position == selectedPosition) {
                holder.container.setBackgroundColor(
                    ContextCompat.getColor(holder.itemView.context, android.R.color.holo_blue_light)
                )
            } else {
                holder.container.setBackgroundColor(
                    ContextCompat.getColor(holder.itemView.context, android.R.color.transparent)
                )
            }

            holder.itemView.setOnClickListener {
                val previousSelected = selectedPosition
                selectedPosition = holder.adapterPosition
                if(previousSelected != -1) {
                    notifyItemChanged(previousSelected)
                }
                notifyItemChanged(selectedPosition)
                onItemClick(printer)
            }
        }

        override fun getItemCount() = printers.size
    }
}
