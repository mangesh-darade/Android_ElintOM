# AutoReplyPrint SDK - Setup & Configuration Guide

## 📍 Where to Access AutoReplyPrint SDK Settings

### 1. **On First Boot - Initial Setup Screen**

When you first launch the app, it will show the **Initial Setup Screen** (`InitialSetupActivity`):

**Location**: `app/src/main/java/com/elintpos/wrapper/InitialSetupActivity.kt`

**Flow**:
1. App starts → `SplashActivity`
2. Checks if setup is completed
3. If NOT completed → Shows `InitialSetupActivity`
4. If completed → Goes to `MainActivity`

### 2. **Initial Setup Screen Features**

The setup screen includes:

#### **Step 1: Customer Information**
- Customer Name
- Mobile Number
- Fetch Domains

#### **Step 2: Printer Configuration**
- **Connection Type Spinner** - Now includes "AutoReplyPrint" option
- **Printer Discovery** - Automatically discovers AutoReplyPrint printers
- **Printer Selection** - Select from discovered printers
- **Printer Settings**:
  - Paper Size (58mm, 80mm, 112mm)
  - Cut Mode
  - Print Mode
  - Footer Text

#### **Step 3: Test Print**
- Test print functionality
- Verifies printer connection

#### **Step 4: Save & Continue**
- Saves configuration to `PrinterConfigManager`
- Marks setup as completed
- Navigates to MainActivity

## 🔧 How AutoReplyPrint Configuration Works

### **In InitialSetupActivity**

1. **Connection Type Selection**:
   ```kotlin
   // Connection types now include:
   private val connectionTypes = listOf("Bluetooth", "USB", "LAN", "AutoReplyPrint")
   ```

2. **Printer Discovery**:
   When "AutoReplyPrint" is selected:
   ```kotlin
   private fun discoverAutoReplyPrintPrinters() {
       // Checks if SDK is available
       if (!autoReplyPrint.isAvailable()) {
           updatePrinterStatus("AutoReplyPrint SDK not available...")
           return
       }
       
       // Starts discovery
       autoReplyPrint.startDiscover { printerDevice ->
           // Extracts printer info and adds to list
       }
   }
   ```

3. **Configuration Saved**:
   ```kotlin
   val config = PrinterConfigManager.PrinterConfig(
       type = PrinterConfigManager.TYPE_AUTOREPLYPRINT,
       name = "${printer.name} (${selectedModel})",
       paperWidth = paperWidth,
       connectionParams = mapOf(
           "deviceName" to printer.name,
           "address" to printer.address
       )
   )
   printerConfigManager.saveProfile(config)
   ```

## 📱 Accessing Settings After Setup

### **From MainActivity (JavaScript Bridge)**

Once setup is complete, you can access AutoReplyPrint SDK from JavaScript:

```javascript
// Check if SDK is available
const available = JSON.parse(Android.isAutoReplyPrintAvailable()).available;

// Discover printers
Android.autoreplyprintDiscoverPrinters();

// Connect
Android.autoreplyprintIsConnected();

// Print
Android.autoreplyprintPrintText("Hello World");

// Get status
Android.autoreplyprintGetStatus();
```

### **From Kotlin Code**

```kotlin
// Get SDK instance
val autoReplyPrint = (applicationContext as ElintApp).autoReplyPrint

// Check availability
if (autoReplyPrint.isAvailable()) {
    // Use SDK
    autoReplyPrint.startDiscover { printer ->
        // Handle discovered printer
    }
}
```

## 🔄 Setup Flow Diagram

```
App Launch
    ↓
SplashActivity
    ↓
Check: is_setup_completed?
    ↓
    ├─ NO → InitialSetupActivity
    │         ├─ Customer Info
    │         ├─ Domain Selection
    │         ├─ Printer Configuration
    │         │   ├─ Select Connection Type (Bluetooth/USB/LAN/AutoReplyPrint)
    │         │   ├─ Discover Printers
    │         │   ├─ Select Printer
    │         │   └─ Configure Settings
    │         ├─ Test Print
    │         └─ Save & Continue
    │             └─ Set is_setup_completed = true
    │
    └─ YES → MainActivity
              └─ Use configured printer
```

## ⚙️ Configuration Storage

### **Where Settings Are Stored**

1. **Setup Completion Flag**:
   - Key: `is_setup_completed`
   - Location: SharedPreferences (`settings`)
   - File: `app/src/main/java/com/elintpos/wrapper/InitialSetupActivity.kt`

2. **Printer Profiles**:
   - Manager: `PrinterConfigManager`
   - Location: SharedPreferences (`printer_config`)
   - File: `app/src/main/java/com/elintpos/wrapper/printer/PrinterConfigManager.kt`

3. **SDK Instance**:
   - Location: `ElintApp` (Application class)
   - File: `app/src/main/java/com/elintpos/wrapper/ElintApp.kt`
   - Auto-initialized on app startup

## 🎯 Quick Setup Steps

### **For First Time Setup:**

1. **Launch App** → Shows Initial Setup Screen
2. **Enter Customer Info** → Name & Mobile Number
3. **Fetch Domains** → Select your domain/outlet
4. **Select Connection Type** → Choose "AutoReplyPrint"
5. **Wait for Discovery** → Printers will appear in list
6. **Select Printer** → Tap on discovered printer
7. **Configure Settings** → Paper size, cut mode, etc.
8. **Test Print** → Verify printer works
9. **Save & Continue** → Configuration saved

### **After Setup:**

- Settings are saved permanently
- App will skip setup on next launch
- Printer configuration available in `PrinterConfigManager`
- Access SDK via JavaScript bridge or Kotlin code

## 🔍 Checking Configuration

### **From JavaScript:**

```javascript
// Get all printer profiles
const profiles = Android.getAllPrinterProfiles();
const profilesObj = JSON.parse(profiles);

// Find AutoReplyPrint profile
const autoReplyProfile = profilesObj.profiles.find(p => p.type === "autoreplyprint");

if (autoReplyProfile) {
    console.log("AutoReplyPrint configured:", autoReplyProfile.name);
    console.log("Paper width:", autoReplyProfile.paperWidth);
}
```

### **From Kotlin:**

```kotlin
val printerConfigManager = PrinterConfigManager(context)
val profiles = printerConfigManager.getAllProfiles()
val autoReplyProfile = profiles.find { it.type == PrinterConfigManager.TYPE_AUTOREPLYPRINT }

if (autoReplyProfile != null) {
    Log.d("Setup", "AutoReplyPrint configured: ${autoReplyProfile.name}")
}
```

## 🛠️ Troubleshooting

### **Issue: Setup screen doesn't show**

**Solution**: Check `SplashActivity` navigation:
```kotlin
// Should navigate to InitialSetupActivity if setup not completed
val isSetupCompleted = prefs.getBoolean("is_setup_completed", false)
val intent = if (isSetupCompleted) {
    Intent(this, MainActivity::class.java)
} else {
    Intent(this, InitialSetupActivity::class.java)
}
```

### **Issue: AutoReplyPrint not in connection types**

**Solution**: Verify `InitialSetupActivity` has:
```kotlin
private val connectionTypes = listOf("Bluetooth", "USB", "LAN", "AutoReplyPrint")
```

### **Issue: No printers discovered**

**Solution**: 
1. Check if SDK is available: `Android.isAutoReplyPrintAvailable()`
2. Ensure AAR file is in `app/libs/autoreplyprint.aar`
3. Check permissions (Bluetooth, Location)
4. Verify printer is powered on and in range

## 📚 Related Files

- **Setup Activity**: `app/src/main/java/com/elintpos/wrapper/InitialSetupActivity.kt`
- **Splash Activity**: `app/src/main/java/com/elintpos/wrapper/SplashActivity.kt`
- **Application Class**: `app/src/main/java/com/elintpos/wrapper/ElintApp.kt`
- **Printer Config Manager**: `app/src/main/java/com/elintpos/wrapper/printer/PrinterConfigManager.kt`
- **AutoReplyPrint Wrapper**: `app/src/main/java/com/elintpos/wrapper/printer/vendor/AutoReplyPrint.kt`
- **Main Activity**: `app/src/main/java/com/elintpos/wrapper/MainActivity.kt`

## ✅ Summary

- **First Boot**: Shows `InitialSetupActivity` with AutoReplyPrint option
- **Connection Type**: "AutoReplyPrint" added to connection types
- **Discovery**: Automatic printer discovery when AutoReplyPrint selected
- **Configuration**: Saved to `PrinterConfigManager` with type `TYPE_AUTOREPLYPRINT`
- **Access**: Available via JavaScript bridge or Kotlin code after setup
- **Storage**: Settings persisted in SharedPreferences

The AutoReplyPrint SDK is now fully integrated into the initial setup flow! 🎉

