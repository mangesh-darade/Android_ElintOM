package com.elintpos.wrapper

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.elintpos.wrapper.escpos.BluetoothEscPosPrinter
import com.elintpos.wrapper.escpos.UsbEscPosPrinter
import com.elintpos.wrapper.escpos.LanEscPosPrinter
import com.elintpos.wrapper.printer.PrinterConfigManager
import com.elintpos.wrapper.printer.PrinterTester
import com.elintpos.wrapper.UnifiedPrinterHandler

/**
 * LoginSettingsActivity - Login and Settings Screen with Printer Configuration
 * 
 * Purpose: Shows login page and settings (including printer configuration) when app boots.
 * This screen appears after splash screen and before MainActivity.
 * 
 * Flow:
 * 1. App launches -> SplashActivity -> LoginSettingsActivity
 * 2. User logs in and configures printer settings
 * 3. User clicks "Login" -> MainActivity (webview loads)
 */
class LoginSettingsActivity : ComponentActivity() {

    private lateinit var printerConfigManager: PrinterConfigManager
    private lateinit var printerTester: PrinterTester
    private lateinit var unifiedPrinterHandler: UnifiedPrinterHandler
    private lateinit var prefs: SharedPreferences
    
    // Printer discovery
    private lateinit var bluetoothPrinter: BluetoothEscPosPrinter
    private lateinit var usbPrinter: UsbEscPosPrinter
    private var lanPrinter: LanEscPosPrinter? = null
    
    // Permission launcher for Bluetooth permissions
    private lateinit var bluetoothPermissionLauncher: ActivityResultLauncher<Array<String>>
    
    // UI Components - Login
    private lateinit var usernameEditText: EditText
    private lateinit var passwordEditText: EditText
    private lateinit var loginButton: Button
    private lateinit var rememberMeCheckBox: CheckBox
    
    // UI Components - Settings/Printer
    private lateinit var settingsContainer: LinearLayout
    private lateinit var printerRecyclerView: RecyclerView
    private lateinit var printerModelSpinner: Spinner
    private lateinit var connectionTypeSpinner: Spinner
    private lateinit var refreshButton: Button
    private lateinit var testPrintButton: Button
    private lateinit var statusText: TextView
    private lateinit var settingsToggleButton: Button
    private lateinit var showPrintDialogCheckBox: CheckBox
    
    // Printer data
    private val availablePrinters = mutableListOf<PrinterInfo>()
    private var selectedPrinter: PrinterInfo? = null
    private var selectedModel: String = "XP-80"
    private var selectedConnectionType: String = "bluetooth"
    private var isSettingsExpanded = false
    
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
        setContentView(R.layout.activity_login_settings)
        
        // Initialize managers
        printerConfigManager = PrinterConfigManager(this)
        printerTester = PrinterTester(this)
        unifiedPrinterHandler = UnifiedPrinterHandler(this)
        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        bluetoothPrinter = BluetoothEscPosPrinter(this)
        usbPrinter = UsbEscPosPrinter(this)
        
        // Initialize permission launcher
        bluetoothPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val allGranted = permissions.values.all { it }
            if (allGranted) {
                discoverPrinters()
            } else {
                updateStatus("Bluetooth permissions denied. Please grant permissions in settings.")
            }
        }
        
        // Initialize UI
        initializeViews()
        setupLoginSection()
        setupPrinterSection()
        
        // Check if already logged in and configured
        if (isLoggedIn() && isPrinterConfigured()) {
            // Auto-navigate to MainActivity immediately
            navigateToMainActivity()
        } else {
            // Load saved username if remember me was checked
            loadSavedCredentials()
            // Discover printers
            discoverPrinters()
        }
    }
    
    private fun initializeViews() {
        // Login views
        usernameEditText = findViewById(R.id.usernameEditText)
        passwordEditText = findViewById(R.id.passwordEditText)
        loginButton = findViewById(R.id.loginButton)
        rememberMeCheckBox = findViewById(R.id.rememberMeCheckBox)
        
        // Settings/Printer views
        settingsContainer = findViewById(R.id.settingsContainer)
        printerRecyclerView = findViewById(R.id.printerRecyclerView)
        printerModelSpinner = findViewById(R.id.printerModelSpinner)
        connectionTypeSpinner = findViewById(R.id.connectionTypeSpinner)
        refreshButton = findViewById(R.id.refreshButton)
        testPrintButton = findViewById(R.id.testPrintButton)
        statusText = findViewById(R.id.statusText)
        settingsToggleButton = findViewById(R.id.settingsToggleButton)
        showPrintDialogCheckBox = findViewById(R.id.showPrintDialogCheckBox)
    }
    
    private fun setupLoginSection() {
        loginButton.setOnClickListener {
            val username = usernameEditText.text.toString().trim()
            val password = passwordEditText.text.toString().trim()
            
            if (username.isEmpty()) {
                Toast.makeText(this, "Please enter username", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            if (password.isEmpty()) {
                Toast.makeText(this, "Please enter password", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            // Save credentials if remember me is checked
            if (rememberMeCheckBox.isChecked) {
                saveCredentials(username, password)
            } else {
                clearSavedCredentials()
            }
            
            // Mark as logged in
            prefs.edit().putBoolean("is_logged_in", true).putString("username", username).commit()
            
            // Check if printer is configured
            if (selectedPrinter != null || isPrinterConfigured()) {
                if (selectedPrinter != null) {
                    savePrinterConfiguration()
                }
                navigateToMainActivity()
            } else {
                Toast.makeText(this, "Please configure printer settings", Toast.LENGTH_SHORT).show()
                // Expand settings section
                if (!isSettingsExpanded) {
                    toggleSettings()
                }
            }
        }
    }
    
    private fun setupPrinterSection() {
        // Settings toggle button
        settingsToggleButton.setOnClickListener {
            toggleSettings()
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
        
        // RecyclerView
        printerRecyclerView.layoutManager = LinearLayoutManager(this)
        printerRecyclerView.adapter = PrinterAdapter(availablePrinters) { printer ->
            selectedPrinter = printer
            updateStatus("Selected: ${printer.name}")
            (printerRecyclerView.adapter as? PrinterAdapter)?.selectedPosition = 
                availablePrinters.indexOf(printer)
            printerRecyclerView.adapter?.notifyDataSetChanged()
        }
        
        // Refresh button
        refreshButton.setOnClickListener {
            discoverPrinters()
        }
        
        // Test Print button
        testPrintButton.setOnClickListener {
            testPrint()
        }
        
        // Load print dialog setting
        val showPrintDialog = prefs.getBoolean("show_print_dialog", false)
        showPrintDialogCheckBox.isChecked = showPrintDialog
        
        // Save print dialog setting when changed
        showPrintDialogCheckBox.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("show_print_dialog", isChecked).apply()
            updateStatus(if (isChecked) "Print dialog enabled" else "Direct print enabled")
        }
        
        // Initially hide settings
        settingsContainer.visibility = View.GONE
    }
    
    private fun toggleSettings() {
        isSettingsExpanded = !isSettingsExpanded
        if (isSettingsExpanded) {
            settingsContainer.visibility = View.VISIBLE
            settingsToggleButton.text = "Hide Settings"
        } else {
            settingsContainer.visibility = View.GONE
            settingsToggleButton.text = "Show Settings"
        }
    }
    
    @SuppressLint("MissingPermission")
    private fun discoverPrinters() {
        availablePrinters.clear()
        updateStatus("Discovering printers...")
        
        when (selectedConnectionType) {
            "bluetooth" -> discoverBluetoothPrinters()
            "usb" -> discoverUsbPrinters()
            "lan" -> showLanConfiguration()
        }
        
        printerRecyclerView.adapter?.notifyDataSetChanged()
    }
    
    @SuppressLint("MissingPermission")
    private fun discoverBluetoothPrinters() {
        if (!checkBluetoothPermissions()) {
            updateStatus("Requesting Bluetooth permissions...")
            requestBluetoothPermissions()
            return
        }
        
        val adapter = BluetoothAdapter.getDefaultAdapter()
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
                    val deviceName = device.deviceName ?: "USB Printer ${device.vendorId}:${device.productId}"
                    val printerInfo = PrinterInfo(
                        name = deviceName,
                        address = "${device.vendorId}:${device.productId}",
                        type = "usb",
                        model = detectPrinterModel(deviceName),
                        device = device
                    )
                    availablePrinters.add(printerInfo)
                }
                updateStatus("Found ${devices.size} USB printer(s)")
            }
        } catch (e: Exception) {
            updateStatus("Error discovering USB printers: ${e.message}")
        }
    }
    
    private fun showLanConfiguration() {
        availablePrinters.add(PrinterInfo(
            name = "LAN Printer (Manual Configuration)",
            address = "192.168.1.100:9100",
            type = "lan",
            model = selectedModel
        ))
        updateStatus("LAN printer configuration available")
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
            else -> selectedModel
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
                prefs.edit().putBoolean("printer_configured", true).commit()
            }
        }
    }
    
    private fun isLoggedIn(): Boolean {
        return prefs.getBoolean("is_logged_in", false)
    }
    
    private fun isPrinterConfigured(): Boolean {
        val configured = prefs.getBoolean("printer_configured", false)
        val hasProfiles = printerConfigManager.getAllProfiles().isNotEmpty()
        return configured || hasProfiles
    }
    
    private fun saveCredentials(username: String, password: String) {
        prefs.edit()
            .putString("saved_username", username)
            .putString("saved_password", password) // Note: In production, encrypt this
            .putBoolean("remember_me", true)
            .apply()
    }
    
    private fun loadSavedCredentials() {
        if (prefs.getBoolean("remember_me", false)) {
            val username = prefs.getString("saved_username", "")
            val password = prefs.getString("saved_password", "")
            usernameEditText.setText(username)
            passwordEditText.setText(password)
            rememberMeCheckBox.isChecked = true
        }
    }
    
    private fun clearSavedCredentials() {
        prefs.edit()
            .remove("saved_username")
            .remove("saved_password")
            .putBoolean("remember_me", false)
            .apply()
    }
    
    private fun navigateToMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }
    
    private fun updateStatus(message: String) {
        statusText.text = message
    }
    
    private fun testPrint() {
        if (selectedPrinter == null) {
            Toast.makeText(this, "Please select a printer first", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Create a temporary profile for testing and save it
        val connectionParams = when (selectedPrinter!!.type) {
            "bluetooth" -> mapOf("mac" to selectedPrinter!!.address)
            "usb" -> {
                // For USB, try to get deviceName from the actual UsbDevice object first
                val usbDevice = selectedPrinter!!.device as? android.hardware.usb.UsbDevice
                val deviceName = usbDevice?.deviceName 
                    ?: selectedPrinter!!.name.takeIf { it.isNotBlank() }
                    ?: selectedPrinter!!.address
                
                // Extract vendorId and productId from address (format: "vendorId:productId")
                val addressParts = selectedPrinter!!.address.split(":")
                val vendorId = addressParts.getOrNull(0) ?: (usbDevice?.vendorId?.toString() ?: "")
                val productId = addressParts.getOrNull(1) ?: (usbDevice?.productId?.toString() ?: "")
                
                mapOf(
                    "deviceName" to deviceName,
                    "address" to selectedPrinter!!.address,
                    "vendorId" to vendorId,
                    "productId" to productId
                )
            }
            "lan" -> {
                val parts = selectedPrinter!!.address.split(":")
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
        
        // Normalize printer type to match PrinterConfigManager constants
        val normalizedType = when (selectedPrinter!!.type.lowercase()) {
            "bluetooth" -> PrinterConfigManager.TYPE_BLUETOOTH
            "usb" -> PrinterConfigManager.TYPE_USB
            "lan" -> PrinterConfigManager.TYPE_LAN
            else -> selectedPrinter!!.type.lowercase()
        }
        
        val testProfile = PrinterConfigManager.PrinterConfig(
            type = normalizedType,
            name = "Test Profile",
            enabled = true,
            paperWidth = paperWidth,
            connectionParams = connectionParams,
            isDefault = true
        )
        
        // Save the test profile temporarily
        printerConfigManager.saveProfile(testProfile)
        printerConfigManager.setLastUsedProfile(testProfile.id)
        
        // Create test content
        val testText = """
================================
TEST PRINT
================================

Printer: ${selectedPrinter!!.name}
Model: $selectedModel
Type: ${selectedPrinter!!.type.uppercase()}

This is a test print to verify
that your printer is working
correctly.

Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}

If you can read this clearly,
your printer is configured
correctly!

================================
END OF TEST
================================


        """.trimIndent()
        
        // Show loading
        updateStatus("Connecting to printer...")
        testPrintButton.isEnabled = false
        
        // Run test in background thread
        Thread {
            try {
                // Map connection type to UnifiedPrinterHandler format
                val preferType = when (selectedPrinter!!.type.lowercase()) {
                    "bluetooth" -> "bluetooth"
                    "usb" -> "usb"
                    "lan" -> "lan"
                    else -> null
                }
                
                // Wait a bit to ensure profile is saved
                Thread.sleep(100)
                
                // Use UnifiedPrinterHandler for direct printing
                runOnUiThread {
                    updateStatus("Connecting to printer...")
                }
                val printResult = unifiedPrinterHandler.print(testText, preferType)
                
                runOnUiThread {
                    testPrintButton.isEnabled = true
                    if (printResult.success) {
                        updateStatus("✅ Test print sent successfully!")
                        Toast.makeText(this, "Test print sent successfully!", Toast.LENGTH_SHORT).show()
                    } else {
                        updateStatus("❌ Test print failed: ${printResult.message}")
                        Toast.makeText(this, "Test print failed: ${printResult.message}", Toast.LENGTH_LONG).show()
                        android.util.Log.e("LoginSettingsActivity", "Test print failed: ${printResult.message}")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    testPrintButton.isEnabled = true
                    updateStatus("❌ Error: ${e.message}")
                    Toast.makeText(this, "Test print error: ${e.message}", Toast.LENGTH_SHORT).show()
                    android.util.Log.e("LoginSettingsActivity", "Test print error", e)
                }
            }
        }.start()
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
        
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): PrinterViewHolder {
            val view = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_printer, parent, false)
            return PrinterViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: PrinterViewHolder, position: Int) {
            val printer = printers[position]
            holder.nameText.text = printer.name
            holder.addressText.text = printer.address
            holder.modelText.text = printer.model ?: "Unknown Model"
            holder.typeText.text = printer.type.uppercase()
            
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
                selectedPosition = position
                notifyDataSetChanged()
                onItemClick(printer)
            }
        }
        
        override fun getItemCount() = printers.size
    }
}

