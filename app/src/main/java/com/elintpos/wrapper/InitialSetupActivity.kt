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
import com.elintpos.wrapper.api.DomainApiService
import com.elintpos.wrapper.escpos.BluetoothEscPosPrinter
import com.elintpos.wrapper.escpos.UsbEscPosPrinter
import com.elintpos.wrapper.printer.PrinterConfigManager
import com.elintpos.wrapper.printer.vendor.AutoReplyPrint
import com.elintpos.wrapper.utils.NetworkUtils
import com.elintpos.wrapper.UnifiedPrinterHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * InitialSetupActivity - One-Time Initial Setup Screen
 * 
 * Purpose: Handles the complete initial setup flow for POS devices.
 * This screen appears only once when the app is first launched.
 * 
 * Flow:
 * 1. Customer Name & Mobile Number
 * 2. Fetch Domains via API
 * 3. Select Domain/Outlet
 * 4. Configure Printer Settings
 * 5. Test Print
 * 6. Save & Continue -> Navigate to MainActivity
 * 
 * Features:
 * - Internet connectivity check
 * - Domain fetching via API
 * - Comprehensive printer configuration
 * - Test print functionality
 * - Settings saved to SharedPreferences (JSON format)
 */
class InitialSetupActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var printerConfigManager: PrinterConfigManager
    private lateinit var unifiedPrinterHandler: UnifiedPrinterHandler
    private lateinit var domainApiService: DomainApiService

    // UI Components - Customer Info
    private lateinit var customerNameEditText: EditText
    private lateinit var mobileNumberEditText: EditText
    private lateinit var fetchDomainButton: Button
    private lateinit var domainStatusText: TextView

    // UI Components - Domain Selection
    private lateinit var domainSelectionContainer: LinearLayout
    private lateinit var domainSpinner: Spinner

    // UI Components - Printer Settings
    private lateinit var printerSettingsContainer: LinearLayout
    private lateinit var connectionTypeSpinner: Spinner
    private lateinit var printerModelSpinner: Spinner
    private lateinit var paperSizeSpinner: Spinner
    private lateinit var cutModeSpinner: Spinner
    private lateinit var printModeSpinner: Spinner
    private lateinit var footerTextEditText: EditText
    private lateinit var printerRecyclerView: RecyclerView
    private lateinit var refreshPrinterButton: Button
    private lateinit var testPrintButton: Button
    private lateinit var printerStatusText: TextView

    // UI Components - Status & Actions
    private lateinit var internetStatusText: TextView
    private lateinit var saveAndContinueButton: Button

    // Printer discovery
    private lateinit var bluetoothPrinter: BluetoothEscPosPrinter
    private lateinit var usbPrinter: UsbEscPosPrinter
    private lateinit var bluetoothPermissionLauncher: ActivityResultLauncher<Array<String>>

    // Data
    private val availablePrinters = mutableListOf<PrinterInfo>()
    private var selectedPrinter: PrinterInfo? = null
    private var selectedConnectionType: String = "bluetooth"
    private var selectedModel: String = "XP-80"
    private var selectedPaperSize: String = "80mm"
    private var selectedCutMode: String = "full"
    private var selectedPrintMode: String = "normal"
    
    private var domains: List<DomainApiService.DomainInfo> = emptyList()
    private var selectedDomain: DomainApiService.DomainInfo? = null

    // Options
    private val connectionTypes = listOf("Bluetooth", "USB", "LAN", "AutoReplyPrint")
    private val printerModels = listOf("XP-58C", "XP-80", "XP-80C", "XP-90", "XP-76", "XP-76C", "XP-F800")
    
    // AutoReplyPrint SDK
    private val autoReplyPrint: AutoReplyPrint by lazy {
        try {
            (applicationContext as? ElintApp)?.autoReplyPrint ?: AutoReplyPrint(this)
        } catch (e: Exception) {
            android.util.Log.e("InitialSetupActivity", "Error accessing AutoReplyPrint from ElintApp", e)
            AutoReplyPrint(this)
        }
    }
    private val paperSizes = listOf("58mm", "80mm", "112mm")
    private val cutModes = listOf("Full Cut", "Partial Cut", "No Cut")
    private val printModes = listOf("Normal", "High Quality", "Draft")

    data class PrinterInfo(
        val name: String,
        val address: String,
        val type: String,
        val model: String? = null,
        val device: Any? = null
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_initial_setup)

        // Initialize managers
        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        printerConfigManager = PrinterConfigManager(this)
        unifiedPrinterHandler = UnifiedPrinterHandler(this)
        domainApiService = DomainApiService()
        bluetoothPrinter = BluetoothEscPosPrinter(this)
        usbPrinter = UsbEscPosPrinter(this)
        // AutoReplyPrint is initialized lazily - access it when needed

        // Initialize permission launcher
        bluetoothPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val allGranted = permissions.values.all { it }
            if (allGranted) {
                discoverPrinters()
            } else {
                updatePrinterStatus("Bluetooth permissions denied")
            }
        }

        // Initialize UI
        initializeViews()
        setupSpinners()
        setupRecyclerView()
        setupButtons()
        checkInternetStatus()

        // Check if setup is already completed
        if (isSetupCompleted()) {
            navigateToMainActivity()
        }
    }

    private fun initializeViews() {
        // Customer Info
        customerNameEditText = findViewById(R.id.customerNameEditText)
        mobileNumberEditText = findViewById(R.id.mobileNumberEditText)
        fetchDomainButton = findViewById(R.id.fetchDomainButton)
        domainStatusText = findViewById(R.id.domainStatusText)

        // Domain Selection
        domainSelectionContainer = findViewById(R.id.domainSelectionContainer)
        domainSpinner = findViewById(R.id.domainSpinner)

        // Printer Settings
        printerSettingsContainer = findViewById(R.id.printerSettingsContainer)
        connectionTypeSpinner = findViewById(R.id.connectionTypeSpinner)
        printerModelSpinner = findViewById(R.id.printerModelSpinner)
        paperSizeSpinner = findViewById(R.id.paperSizeSpinner)
        cutModeSpinner = findViewById(R.id.cutModeSpinner)
        printModeSpinner = findViewById(R.id.printModeSpinner)
        footerTextEditText = findViewById(R.id.footerTextEditText)
        printerRecyclerView = findViewById(R.id.printerRecyclerView)
        refreshPrinterButton = findViewById(R.id.refreshPrinterButton)
        testPrintButton = findViewById(R.id.testPrintButton)
        printerStatusText = findViewById(R.id.printerStatusText)

        // Status & Actions
        internetStatusText = findViewById(R.id.internetStatusText)
        saveAndContinueButton = findViewById(R.id.saveAndContinueButton)
    }

    private fun setupSpinners() {
        // Connection Type
        val connectionAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, connectionTypes)
        connectionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        connectionTypeSpinner.adapter = connectionAdapter
        connectionTypeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedConnectionType = connectionTypes[position].lowercase()
                discoverPrinters()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Printer Model
        val modelAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, printerModels)
        modelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        printerModelSpinner.adapter = modelAdapter
        printerModelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedModel = printerModels[position]
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Paper Size
        val paperAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, paperSizes)
        paperAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        paperSizeSpinner.adapter = paperAdapter
        paperSizeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedPaperSize = paperSizes[position]
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Cut Mode
        val cutAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, cutModes)
        cutAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        cutModeSpinner.adapter = cutAdapter
        cutModeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedCutMode = cutModes[position].lowercase().replace(" ", "_")
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Print Mode
        val printAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, printModes)
        printAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        printModeSpinner.adapter = printAdapter
        printModeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedPrintMode = printModes[position].lowercase().replace(" ", "_")
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupRecyclerView() {
        printerRecyclerView.layoutManager = LinearLayoutManager(this)
        printerRecyclerView.adapter = PrinterAdapter(availablePrinters) { printer ->
            selectedPrinter = printer
            updatePrinterStatus("Selected: ${printer.name}")
            (printerRecyclerView.adapter as? PrinterAdapter)?.selectedPosition = 
                availablePrinters.indexOf(printer)
            printerRecyclerView.adapter?.notifyDataSetChanged()
        }
    }

    private fun setupButtons() {
        // Fetch Domain Button
        fetchDomainButton.setOnClickListener {
            val mobileNumber = mobileNumberEditText.text.toString().trim()
            if (mobileNumber.isEmpty()) {
                Toast.makeText(this, "Please enter mobile number", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!NetworkUtils.isInternetAvailable(this)) {
                Toast.makeText(this, "No internet connection. Please check your network.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            fetchDomains(mobileNumber)
        }

        // Refresh Printer Button
        refreshPrinterButton.setOnClickListener {
            discoverPrinters()
        }

        // Test Print Button
        testPrintButton.setOnClickListener {
            testPrint()
        }

        // Save & Continue Button
        saveAndContinueButton.setOnClickListener {
            if (validateSetup()) {
                saveSetupAndContinue()
            }
        }
    }

    private fun checkInternetStatus() {
        val isConnected = NetworkUtils.isInternetAvailable(this)
        val networkType = NetworkUtils.getNetworkType(this)
        
        internetStatusText.text = if (isConnected) {
            "Internet: Connected ($networkType)"
        } else {
            "Internet: Not Connected"
        }
        internetStatusText.setTextColor(
            if (isConnected) ContextCompat.getColor(this, android.R.color.holo_green_dark)
            else ContextCompat.getColor(this, android.R.color.holo_red_dark)
        )

        // Update fetch button state
        fetchDomainButton.isEnabled = isConnected
    }

    private fun fetchDomains(mobileNumber: String) {
        fetchDomainButton.isEnabled = false
        domainStatusText.visibility = View.VISIBLE
        domainStatusText.text = "Fetching domains..."
        domainStatusText.setTextColor(ContextCompat.getColor(this@InitialSetupActivity, android.R.color.holo_blue_dark))

        CoroutineScope(Dispatchers.Main).launch {
            try {
                // Use mock for now - replace with actual API call
                val result = withContext(Dispatchers.IO) {
                    domainApiService.fetchDomainsMock(mobileNumber)
                }

                when (result) {
                    is DomainApiService.ApiResult.Success -> {
                        domains = result.domains
                        if (domains.isNotEmpty()) {
                            setupDomainSpinner()
                            domainSelectionContainer.visibility = View.VISIBLE
                            domainStatusText.text = "Found ${domains.size} domain(s)"
                            domainStatusText.setTextColor(ContextCompat.getColor(this@InitialSetupActivity, android.R.color.holo_green_dark))
                        } else {
                            domainStatusText.text = "No domains found for this mobile number"
                            domainStatusText.setTextColor(ContextCompat.getColor(this@InitialSetupActivity, android.R.color.holo_red_dark))
                        }
                    }
                    is DomainApiService.ApiResult.Error -> {
                        domainStatusText.text = "Error: ${result.message}"
                        domainStatusText.setTextColor(ContextCompat.getColor(this@InitialSetupActivity, android.R.color.holo_red_dark))
                    }
                }
            } catch (e: Exception) {
                domainStatusText.text = "Error: ${e.message}"
                domainStatusText.setTextColor(ContextCompat.getColor(this@InitialSetupActivity, android.R.color.holo_red_dark))
            } finally {
                fetchDomainButton.isEnabled = true
            }
        }
    }

    private fun setupDomainSpinner() {
        val domainNames = domains.map { "${it.name} (${it.domain})" }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, domainNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        domainSpinner.adapter = adapter
        domainSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedDomain = domains[position]
                checkSetupComplete()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    @SuppressLint("MissingPermission")
    private fun discoverPrinters() {
        availablePrinters.clear()
        updatePrinterStatus("Discovering printers...")
        printerSettingsContainer.visibility = View.VISIBLE

        when (selectedConnectionType) {
            "bluetooth" -> discoverBluetoothPrinters()
            "usb" -> discoverUsbPrinters()
            "lan" -> showLanConfiguration()
            "autoreplyprint" -> discoverAutoReplyPrintPrinters()
        }

        printerRecyclerView.adapter?.notifyDataSetChanged()
    }

    @SuppressLint("MissingPermission")
    private fun discoverBluetoothPrinters() {
        if (!checkBluetoothPermissions()) {
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

        if (adapter == null || !adapter.isEnabled) {
            updatePrinterStatus("Bluetooth not available or not enabled")
            return
        }

        try {
            val pairedDevices = bluetoothPrinter.getPairedPrinters()
            if (pairedDevices.isEmpty()) {
                updatePrinterStatus("No paired Bluetooth printers found")
            } else {
                pairedDevices.forEach { device ->
                    availablePrinters.add(
                        PrinterInfo(
                            name = device.name ?: "Unknown Device",
                            address = device.address,
                            type = "bluetooth",
                            model = detectPrinterModel(device.name ?: ""),
                            device = device
                        )
                    )
                }
                updatePrinterStatus("Found ${pairedDevices.size} Bluetooth printer(s)")
            }
        } catch (e: Exception) {
            updatePrinterStatus("Error: ${e.message}")
        }
    }

    private fun discoverUsbPrinters() {
        try {
            val devices = usbPrinter.listPrinters()
            if (devices.isEmpty()) {
                updatePrinterStatus("No USB printers found")
            } else {
                devices.forEach { device ->
                    availablePrinters.add(
                        PrinterInfo(
                            name = device.deviceName,
                            address = "${device.vendorId}:${device.productId}",
                            type = "usb",
                            model = detectPrinterModel(device.deviceName),
                            device = device
                        )
                    )
                }
                updatePrinterStatus("Found ${devices.size} USB printer(s)")
            }
        } catch (e: Exception) {
            updatePrinterStatus("Error: ${e.message}")
        }
    }

    private fun showLanConfiguration() {
        availablePrinters.add(
            PrinterInfo(
                name = "LAN Printer (Manual Configuration)",
                address = "192.168.1.100:9100",
                type = "lan",
                model = selectedModel
            )
        )
        updatePrinterStatus("LAN printer configuration available")
    }

    private fun discoverAutoReplyPrintPrinters() {
        try {
            if (!autoReplyPrint.isAvailable()) {
                updatePrinterStatus("AutoReplyPrint SDK not available. Please ensure autoreplyprint.aar is in app/libs/")
                return
            }

            updatePrinterStatus("Discovering AutoReplyPrint printers...")
            
            // Start discovery
            val discoveryStarted = autoReplyPrint.startDiscover { printerDevice ->
            runOnUiThread {
                try {
                    // Extract printer information using reflection
                    val deviceClass = printerDevice::class.java
                    var printerName = "Unknown Printer"
                    var printerAddress = ""
                    var connectionType = "Unknown"

                    // Try to get printer name
                    try {
                        val nameField = deviceClass.getField("printer_name")
                        printerName = nameField.get(printerDevice)?.toString() ?: "Unknown Printer"
                    } catch (_: Exception) {
                        try {
                            val nameMethod = deviceClass.getMethod("getPrinterName")
                            printerName = nameMethod.invoke(printerDevice)?.toString() ?: "Unknown Printer"
                        } catch (_: Exception) {}
                    }

                    // Try to get printer address
                    try {
                        val addressField = deviceClass.getField("printer_address")
                        printerAddress = addressField.get(printerDevice)?.toString() ?: ""
                    } catch (_: Exception) {
                        try {
                            val addressMethod = deviceClass.getMethod("getPrinterAddress")
                            printerAddress = addressMethod.invoke(printerDevice)?.toString() ?: ""
                        } catch (_: Exception) {}
                    }

                    // Try to get connection type
                    try {
                        val typeField = deviceClass.getField("connection_type")
                        connectionType = typeField.get(printerDevice)?.toString() ?: "Unknown"
                    } catch (_: Exception) {
                        try {
                            val typeMethod = deviceClass.getMethod("getConnectionType")
                            connectionType = typeMethod.invoke(printerDevice)?.toString() ?: "Unknown"
                        } catch (_: Exception) {}
                    }

                    // Add to available printers list
                    val printerInfo = PrinterInfo(
                        name = printerName,
                        address = printerAddress,
                        type = "autoreplyprint",
                        model = detectPrinterModel(printerName),
                        device = printerDevice
                    )

                    // Check if already added
                    if (!availablePrinters.any { it.address == printerAddress && it.type == "autoreplyprint" }) {
                        availablePrinters.add(printerInfo)
                        printerRecyclerView.adapter?.notifyDataSetChanged()
                        updatePrinterStatus("Found ${availablePrinters.size} AutoReplyPrint printer(s)")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("InitialSetupActivity", "Error processing discovered printer", e)
                }
            }
        }

            if (!discoveryStarted) {
                updatePrinterStatus("Failed to start AutoReplyPrint discovery")
            } else {
                // Stop discovery after 10 seconds
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try {
                        autoReplyPrint.stopDiscover()
                        updatePrinterStatus("Discovery completed. Found ${availablePrinters.size} printer(s)")
                    } catch (e: Exception) {
                        android.util.Log.e("InitialSetupActivity", "Error stopping discovery", e)
                    }
                }, 10000)
            }
        } catch (e: Exception) {
            android.util.Log.e("InitialSetupActivity", "Error in discoverAutoReplyPrintPrinters", e)
            updatePrinterStatus("Error discovering printers: ${e.message}")
        }
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

    private fun testPrint() {
        if (selectedPrinter == null) {
            Toast.makeText(this, "Please select a printer first", Toast.LENGTH_SHORT).show()
            return
        }

        testPrintButton.isEnabled = false
        updatePrinterStatus("Connecting to printer...")

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val testContent = """
                    ================================
                    TEST PRINT
                    ================================
                    
                    Printer: ${selectedPrinter!!.name}
                    Model: $selectedModel
                    Type: ${selectedPrinter!!.type.uppercase()}
                    Paper: $selectedPaperSize
                    Cut Mode: $selectedCutMode
                    Print Mode: $selectedPrintMode
                    
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

                val result = if (selectedConnectionType == "autoreplyprint" && selectedPrinter != null) {
                    // Use AutoReplyPrint SDK directly
                    withContext(Dispatchers.IO) {
                        try {
                            if (!autoReplyPrint.isAvailable()) {
                                UnifiedPrinterHandler.PrintResult(false, "AutoReplyPrint SDK not available")
                            } else {
                                // Connect to printer if not connected
                                if (!autoReplyPrint.isConnected() && selectedPrinter!!.device != null) {
                                    autoReplyPrint.connectSync(selectedPrinter!!.device!!)
                                }
                                
                                if (autoReplyPrint.isConnected()) {
                                    // Create bitmap from text and print
                                    val bitmap = createTextBitmap(testContent, 
                                        when (selectedPaperSize) {
                                            "58mm" -> 384
                                            "112mm" -> 832
                                            else -> 576
                                        }
                                    )
                                    val printResult = autoReplyPrint.printBitmap(bitmap)
                                    if (printResult) {
                                        UnifiedPrinterHandler.PrintResult(true, "Print successful")
                                    } else {
                                        UnifiedPrinterHandler.PrintResult(false, "Print failed")
                                    }
                                } else {
                                    UnifiedPrinterHandler.PrintResult(false, "Failed to connect to printer")
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("InitialSetupActivity", "Error in AutoReplyPrint test print", e)
                            UnifiedPrinterHandler.PrintResult(false, "Error: ${e.message}")
                        }
                    }
                } else {
                    withContext(Dispatchers.IO) {
                        unifiedPrinterHandler.print(testContent, selectedConnectionType)
                    }
                }

                if (result.success) {
                    updatePrinterStatus("✅ Test print sent successfully!")
                    Toast.makeText(this@InitialSetupActivity, "Test print sent successfully!", Toast.LENGTH_SHORT).show()
                } else {
                    updatePrinterStatus("❌ Test print failed: ${result.message}")
                    Toast.makeText(this@InitialSetupActivity, "Test print failed: ${result.message}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                updatePrinterStatus("❌ Error: ${e.message}")
                Toast.makeText(this@InitialSetupActivity, "Test print error: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                testPrintButton.isEnabled = true
            }
        }
    }

    private fun validateSetup(): Boolean {
        val customerName = customerNameEditText.text.toString().trim()
        val mobileNumber = mobileNumberEditText.text.toString().trim()

        if (customerName.isEmpty()) {
            Toast.makeText(this, "Please enter customer name", Toast.LENGTH_SHORT).show()
            return false
        }

        if (mobileNumber.isEmpty()) {
            Toast.makeText(this, "Please enter mobile number", Toast.LENGTH_SHORT).show()
            return false
        }

        if (selectedDomain == null) {
            Toast.makeText(this, "Please select a domain", Toast.LENGTH_SHORT).show()
            return false
        }

        // Printer is optional but recommended
        if (selectedPrinter == null) {
            val proceed = android.app.AlertDialog.Builder(this)
                .setTitle("No Printer Selected")
                .setMessage("You haven't selected a printer. You can configure it later. Do you want to continue?")
                .setPositiveButton("Yes") { _, _ -> saveSetupAndContinue() }
                .setNegativeButton("No", null)
                .create()
            proceed.show()
            return false
        }

        return true
    }

    private fun checkSetupComplete() {
        val customerName = customerNameEditText.text.toString().trim()
        val mobileNumber = mobileNumberEditText.text.toString().trim()
        
        val isComplete = customerName.isNotEmpty() && 
                        mobileNumber.isNotEmpty() && 
                        selectedDomain != null
        
        saveAndContinueButton.isEnabled = isComplete
    }

    private fun saveSetupAndContinue() {
        val customerName = customerNameEditText.text.toString().trim()
        val mobileNumber = mobileNumberEditText.text.toString().trim()
        val footerText = footerTextEditText.text.toString().trim()

        // Save customer information
        prefs.edit().apply {
            putString("customer_name", customerName)
            putString("mobile_number", mobileNumber)
            putString("selected_domain", selectedDomain?.domain ?: "")
            putString("selected_domain_name", selectedDomain?.name ?: "")
            putString("footer_text", footerText)
            putBoolean("is_setup_completed", true)
            commit()
        }

        // Save printer configuration if printer is selected
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
                "autoreplyprint" -> mapOf(
                    "deviceName" to printer.name,
                    "address" to printer.address,
                    "connectionType" to "autoreplyprint"
                )
                else -> emptyMap()
            }

            val paperWidth = when (selectedPaperSize) {
                "58mm" -> PrinterConfigManager.PAPER_58MM
                "112mm" -> PrinterConfigManager.PAPER_112MM
                else -> PrinterConfigManager.PAPER_80MM
            }

            val advancedSettings = mapOf(
                "cutMode" to selectedCutMode,
                "printMode" to selectedPrintMode,
                "footerText" to footerText
            )

            val config = PrinterConfigManager.PrinterConfig(
                type = if (printer.type == "autoreplyprint") PrinterConfigManager.TYPE_AUTOREPLYPRINT else printer.type,
                name = "${printer.name} (${selectedModel})",
                enabled = true,
                paperWidth = paperWidth,
                connectionParams = connectionParams,
                advancedSettings = advancedSettings,
                isDefault = true
            )

            printerConfigManager.saveProfile(config)
            prefs.edit().putBoolean("printer_configured", true).apply()
        }

        // Export settings to JSON (backup)
        exportSettingsToJson()

        Toast.makeText(this, "Setup completed successfully!", Toast.LENGTH_SHORT).show()
        navigateToMainActivity()
    }

    private fun exportSettingsToJson() {
        try {
            val settingsJson = JSONObject().apply {
                put("customer_name", customerNameEditText.text.toString().trim())
                put("mobile_number", mobileNumberEditText.text.toString().trim())
                put("domain", selectedDomain?.domain ?: "")
                put("domain_name", selectedDomain?.name ?: "")
                put("footer_text", footerTextEditText.text.toString().trim())
                put("printer_type", selectedConnectionType)
                put("printer_model", selectedModel)
                put("paper_size", selectedPaperSize)
                put("cut_mode", selectedCutMode)
                put("print_mode", selectedPrintMode)
                put("setup_completed_at", System.currentTimeMillis())
            }

            prefs.edit().putString("settings_backup_json", settingsJson.toString()).apply()
        } catch (e: Exception) {
            android.util.Log.e("InitialSetupActivity", "Error exporting settings", e)
        }
    }

    private fun isSetupCompleted(): Boolean {
        return prefs.getBoolean("is_setup_completed", false)
    }

    private fun navigateToMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }

    private fun updatePrinterStatus(message: String) {
        printerStatusText.text = message
    }

    // RecyclerView Adapter
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
                val previousSelected = selectedPosition
                selectedPosition = holder.adapterPosition
                if (previousSelected != -1) {
                    notifyItemChanged(previousSelected)
                }
                notifyItemChanged(selectedPosition)
                onItemClick(printer)
            }
        }

        override fun getItemCount() = printers.size
    }
    
    // Helper function to create bitmap from text
    private fun createTextBitmap(text: String, width: Int): android.graphics.Bitmap {
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            textSize = 24f
            isAntiAlias = true
        }
        
        val bounds = android.graphics.Rect()
        val lines = text.lines()
        var maxWidth = 0
        
        lines.forEach { line ->
            paint.getTextBounds(line, 0, line.length, bounds)
            if (bounds.width() > maxWidth) maxWidth = bounds.width()
        }
        
        val height = (bounds.height() * (lines.size + 2)).coerceAtLeast(100)
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        
        var y = bounds.height().toFloat()
        lines.forEach { line ->
            canvas.drawText(line, 0f, y, paint)
            y += bounds.height() + 10
        }
        
        return bitmap
    }
}

