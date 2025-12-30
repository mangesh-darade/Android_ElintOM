# AutoReplyPrint SDK - Access & Configuration Guide

This guide explains how to access and configure the AutoReplyPrint SDK in your application.

## 📍 Where to Access AutoReplyPrint SDK

### 1. **From Kotlin/Java Code**

```kotlin
// In any Activity or Fragment
val autoReplyPrint = (applicationContext as ElintApp).autoReplyPrint

// Check if SDK is available
if (autoReplyPrint.isAvailable()) {
    // SDK is ready to use
    autoReplyPrint.initialize()
}
```

### 2. **From JavaScript (WebView)**

The SDK is accessible via JavaScript bridge methods in your WebView:

```javascript
// Check if SDK is available
const result = Android.isAutoReplyPrintAvailable();
const available = JSON.parse(result).available;

if (available) {
    // SDK is ready to use
    console.log("AutoReplyPrint SDK is available");
}
```

## 🔧 Configuration Methods

### JavaScript Bridge Methods

All methods return JSON strings with `{"ok": true/false, ...}` format.

#### 1. **Check SDK Availability**
```javascript
const result = Android.isAutoReplyPrintAvailable();
// Returns: {"ok": true, "available": true/false}
```

#### 2. **Discover Printers**
```javascript
// Start discovering printers
const result = Android.autoreplyprintDiscoverPrinters();
// Returns: {"ok": true, "msg": "Discovery started"}

// Listen for discovered printers
window.onAutoReplyPrintDiscovered = function(printer) {
    console.log("Found printer:", printer.name);
    console.log("Address:", printer.address);
    console.log("Connection Type:", printer.connectionType);
};

// Stop discovery
Android.autoreplyprintStopDiscovery();
```

#### 3. **Connect to Printer**
```javascript
// Check connection status
const statusResult = Android.autoreplyprintIsConnected();
const status = JSON.parse(statusResult);

if (!status.connected) {
    // Connect to printer (use device from discovery)
    // Note: You need to store discovered device objects
    const connectResult = Android.autoreplyprintConnect(deviceHash);
}
```

#### 4. **Print Text**
```javascript
// Print text (converts to bitmap internally)
const result = Android.autoreplyprintPrintText("Hello World\nTest Print");
// Returns: {"ok": true} or {"ok": false, "msg": "error message"}
```

#### 5. **Print Bitmap**
```javascript
// Convert image to base64 and print
const canvas = document.getElementById('myCanvas');
const base64Image = canvas.toDataURL('image/png').split(',')[1];
const result = Android.autoreplyprintPrintBitmap(base64Image);
```

#### 6. **Get Printer Status**
```javascript
const result = Android.autoreplyprintGetStatus();
const status = JSON.parse(result);
// Returns: {
//   "ok": true,
//   "status": {
//     "available": true,
//     "connected": true,
//     "resolution": {
//       "widthMM": 58,
//       "heightMM": 100,
//       "dotsPerMM": 8
//     }
//   }
// }
```

#### 7. **Disconnect**
```javascript
const result = Android.autoreplyprintDisconnect();
// Returns: {"ok": true/false}
```

## ⚙️ Printer Configuration

### Using PrinterConfigManager

The AutoReplyPrint SDK integrates with the existing `PrinterConfigManager` system:

```kotlin
// Create a printer profile for AutoReplyPrint
val config = PrinterConfigManager.PrinterConfig(
    type = PrinterConfigManager.TYPE_AUTOREPLYPRINT,
    name = "My AutoReplyPrint Printer",
    enabled = true,
    paperWidth = PrinterConfigManager.PAPER_58MM, // or PAPER_80MM, PAPER_112MM
    leftMargin = 0,
    rightMargin = 0,
    lineSpacing = 30,
    autoConnect = false,
    connectionParams = mapOf(
        "deviceName" to "Printer Name",
        "address" to "00:11:22:33:44:55"
    ),
    advancedSettings = mapOf(
        "binaryzationMethod" to 2,
        "compressionMethod" to 0,
        "paperType" to 1,
        "printAlignment" to 1,
        "printSpeed" to 150,
        "printDensity" to 7,
        "kickDrawer" to false,
        "feedPaper" to 10.0,
        "cutPaper" to 0
    )
)

// Save the profile
printerConfigManager.saveProfile(config)
```

### From JavaScript

```javascript
// Create printer profile
const profile = {
    type: "autoreplyprint",
    name: "My AutoReplyPrint Printer",
    enabled: true,
    paperWidth: 384, // 58mm
    connectionParams: {
        deviceName: "Printer Name",
        address: "00:11:22:33:44:55"
    },
    advancedSettings: {
        binaryzationMethod: 2,
        printSpeed: 150,
        printDensity: 7
    }
};

const result = Android.savePrinterProfile(JSON.stringify(profile));
```

## 📝 Complete Example

### JavaScript Example

```javascript
// 1. Check if SDK is available
const availableResult = Android.isAutoReplyPrintAvailable();
if (!JSON.parse(availableResult).available) {
    alert("AutoReplyPrint SDK not available");
    return;
}

// 2. Discover printers
let discoveredPrinters = [];
window.onAutoReplyPrintDiscovered = function(printer) {
    discoveredPrinters.push(printer);
    console.log("Discovered:", printer.name);
};

Android.autoreplyprintDiscoverPrinters();

// Wait a few seconds for discovery
setTimeout(() => {
    Android.autoreplyprintStopDiscovery();
    
    if (discoveredPrinters.length > 0) {
        // 3. Connect to first printer
        const printer = discoveredPrinters[0];
        // Note: Actual connection requires device object
        
        // 4. Check connection
        const status = JSON.parse(Android.autoreplyprintIsConnected());
        if (status.connected) {
            // 5. Print
            Android.autoreplyprintPrintText("Test Print\nHello World");
        }
    }
}, 5000);
```

### Kotlin Example

```kotlin
class MyActivity : AppCompatActivity() {
    private val autoReplyPrint: AutoReplyPrint by lazy {
        (applicationContext as ElintApp).autoReplyPrint
    }
    
    fun setupPrinter() {
        if (!autoReplyPrint.isAvailable()) {
            Log.e("MyActivity", "AutoReplyPrint SDK not available")
            return
        }
        
        // Discover printers
        autoReplyPrint.startDiscover { printerDevice ->
            runOnUiThread {
                // Handle discovered printer
                Log.d("MyActivity", "Found printer")
                
                // Connect
                autoReplyPrint.connectAsync(printerDevice)
            }
        }
    }
    
    fun printReceipt(text: String) {
        if (!autoReplyPrint.isConnected()) {
            Toast.makeText(this, "Printer not connected", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Create bitmap from text
        val bitmap = createReceiptBitmap(text)
        
        // Print with custom settings
        val success = autoReplyPrint.printBitmap(
            bitmap = bitmap,
            binaryzationMethod = 2,
            printSpeed = 150,
            printDensity = 7,
            feedPaper = 10.0,
            cutPaper = 1
        )
        
        if (success) {
            Toast.makeText(this, "Print successful", Toast.LENGTH_SHORT).show()
        }
    }
}
```

## 🔍 Where Configuration is Stored

1. **Printer Profiles**: Stored in SharedPreferences via `PrinterConfigManager`
   - Location: `printer_config` SharedPreferences
   - Access via: `printerConfigManager.getAllProfiles()`

2. **SDK Instance**: Initialized in `ElintApp` (Application class)
   - Location: `app/src/main/java/com/elintpos/wrapper/ElintApp.kt`
   - Access: `(applicationContext as ElintApp).autoReplyPrint`

3. **JavaScript Bridge**: Methods defined in `MainActivity`
   - Location: `app/src/main/java/com/elintpos/wrapper/MainActivity.kt`
   - Access: `Android.methodName()` from JavaScript

## 📋 Configuration Checklist

- [x] SDK AAR file added to `app/libs/`
- [x] Build configuration updated (automatic via fileTree)
- [x] ProGuard rules added
- [x] Permissions added to AndroidManifest
- [x] SDK wrapper class created (`AutoReplyPrint.kt`)
- [x] SDK initialized in Application class
- [x] JavaScript bridge methods added
- [x] PrinterConfigManager updated with AutoReplyPrint type

## 🎯 Quick Start

1. **Check SDK availability**:
   ```javascript
   Android.isAutoReplyPrintAvailable()
   ```

2. **Discover and connect**:
   ```javascript
   Android.autoreplyprintDiscoverPrinters()
   // Wait for discovery, then connect
   ```

3. **Print**:
   ```javascript
   Android.autoreplyprintPrintText("Your text here")
   ```

## 📚 Additional Resources

- Integration Guide: `INTEGRATION_GUIDE.md`
- API Documentation: `doc/api_documentation_en.pdf`
- Chinese Documentation: `doc/接口说明文档_中文.pdf`

