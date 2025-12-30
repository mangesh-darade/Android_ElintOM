# AutoReplyPrint SDK Integration Guide

Complete step-by-step guide to integrate AutoReplyPrint SDK into your Android app.

## 📋 Prerequisites

- Android Studio
- Android SDK (minSdkVersion 16 or higher)
- AutoReplyPrint SDK AAR file (`autoreplyprint.aar`)

---

## 🔧 Step 1: Add AAR File to Your Project

### Option A: Copy AAR to `libs` folder (Recommended)

1. Create a `libs` folder in your app module if it doesn't exist:
   ```
   your-app/
   └── app/
       └── libs/
           └── autoreplyprint.aar
   ```

2. Copy `autoreplyprint.aar` from the sample project's `aar/` folder to your `app/libs/` folder.

### Option B: Use aar folder (Alternative)

1. Create an `aar` folder in your project root:
   ```
   your-project/
   └── aar/
       └── autoreplyprint.aar
   ```

---

## 📦 Step 2: Configure build.gradle

Open your `app/build.gradle` file and add:

```gradle
android {
    // ... your existing configuration
    
    repositories {
        flatDir {
            dirs 'libs'  // If using libs folder
            // OR
            // dirs '../aar'  // If using aar folder
        }
    }
}

dependencies {
    // Add the AAR file
    implementation(name: 'autoreplyprint', ext: 'aar')
    
    // OR if using libs folder:
    // implementation files('libs/autoreplyprint.aar')
    
    // OR if using aar folder:
    // implementation files('../aar/autoreplyprint.aar')
    
    // Required dependencies (if not already present)
    implementation 'androidx.appcompat:appcompat:1.2.0'
}
```

**Sync your project** after making these changes.

---

## 🔐 Step 3: Add Required Permissions

Add these permissions to your `AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    
    <!-- Internet & WiFi -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
    <uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
    <uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />
    
    <!-- Bluetooth (Android 11 and below) -->
    <uses-permission 
        android:name="android.permission.BLUETOOTH"
        android:maxSdkVersion="30" />
    <uses-permission 
        android:name="android.permission.BLUETOOTH_ADMIN"
        android:maxSdkVersion="30" />
    
    <!-- Bluetooth (Android 12+) -->
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    
    <!-- Location (Required for BLE scanning) -->
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    
    <!-- Storage (Android 10 and below) -->
    <uses-permission 
        android:name="android.permission.READ_EXTERNAL_STORAGE"
        android:maxSdkVersion="32" />
    <uses-permission 
        android:name="android.permission.WRITE_EXTERNAL_STORAGE"
        android:maxSdkVersion="29" />
    
    <!-- Storage (Android 13+) -->
    <uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
    
    <!-- Optional: Camera (if you need QR code scanning) -->
    <uses-permission android:name="android.permission.CAMERA" />
    
    <application
        android:requestLegacyExternalStorage="true"
        ...>
        <!-- Your activities -->
    </application>
</manifest>
```

---

## 🛡️ Step 4: Configure ProGuard Rules

If you're using ProGuard/R8, add these rules to your `proguard-rules.pro`:

```proguard
# AutoReplyPrint SDK
-keep class com.caysn.autoreplyprint.** {*;}
-keep class com.lvrenyang.** {*;}
-keep class android.bluetooth.** {*;}
-keep class android.hardware.usb.** {*;}
```

---

## 💻 Step 5: Basic Integration Code

### 5.1 Initialize Printer Connector

Create a singleton or use Application class:

```java
import com.caysn.autoreplyprint.caprint.CAPrinterConnector;

public class MyApplication extends Application {
    public static CAPrinterConnector printerConnector = new CAPrinterConnector();
    
    @Override
    public void onCreate() {
        super.onCreate();
        // Initialize printer connector
    }
}
```

### 5.2 Discover Printers

```java
import com.caysn.autoreplyprint.caprint.CAPrinterDiscover;
import com.caysn.autoreplyprint.caprint.CAPrinterDevice;
import java.util.ArrayList;
import java.util.List;

public class PrinterDiscoveryActivity extends AppCompatActivity {
    private CAPrinterDiscover printerDiscover;
    private List<CAPrinterDevice> printerDeviceList = new ArrayList<>();
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        printerDiscover = new CAPrinterDiscover();
        startSearchPrinter();
    }
    
    private void startSearchPrinter() {
        printerDiscover.setOnPrinterDiscoveredListener(new CAPrinterDiscover.OnPrinterDiscoveredListener() {
            @Override
            public void onPrinterDiscovered(final CAPrinterDevice printerDevice) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        // Add printer to list
                        printerDeviceList.add(printerDevice);
                        // Update your UI (ListView, RecyclerView, etc.)
                        updatePrinterList();
                    }
                });
            }
        });
        
        printerDiscover.startDiscover();
    }
    
    private void stopSearchPrinter() {
        printerDiscover.stopDiscover();
    }
    
    private void updatePrinterList() {
        // Update your adapter
    }
}
```

### 5.3 Connect to Printer

```java
import com.caysn.autoreplyprint.caprint.CAPrinterConnector;
import com.caysn.autoreplyprint.caprint.CAPrinterDevice;

public class PrinterConnectionHelper {
    private CAPrinterConnector printerConnector;
    
    public PrinterConnectionHelper(CAPrinterConnector connector) {
        this.printerConnector = connector;
    }
    
    // Connect asynchronously
    public void connectAsync(CAPrinterDevice printerDevice) {
        printerConnector.connectPrinterAsync(printerDevice);
    }
    
    // Connect synchronously (use in background thread)
    public boolean connectSync(CAPrinterDevice printerDevice) {
        return printerConnector.connectPrinterSync(printerDevice);
    }
    
    // Check connection status
    public boolean isConnected() {
        return printerConnector.isCurrentConnectedPrinter();
    }
    
    // Disconnect
    public void disconnect() {
        printerConnector.disconnectPrinter();
    }
    
    // Get current printer
    public CAPrinterDevice getCurrentPrinter() {
        if (printerConnector.isCurrentConnectedPrinter()) {
            return printerConnector.getCurrentPrinterDevice();
        }
        return null;
    }
}
```

### 5.4 Print Bitmap

```java
import com.caysn.autoreplyprint.caprint.CAPrintCommon;
import com.caysn.autoreplyprint.caprint.CAPrinterConnector;
import com.caysn.autoreplyprint.caprint.CAPrinterResolution;
import android.graphics.Bitmap;

public class PrintHelper {
    private CAPrinterConnector printerConnector;
    
    public PrintHelper(CAPrinterConnector connector) {
        this.printerConnector = connector;
    }
    
    public void printBitmap(Bitmap bitmap, Activity activity) {
        if (!printerConnector.isCurrentConnectedPrinter()) {
            // Show error: Printer not connected
            return;
        }
        
        // Show progress dialog
        ProgressDialog dialog = new ProgressDialog(activity);
        dialog.setMessage("Printing...");
        dialog.setCancelable(false);
        dialog.show();
        
        new Thread(new Runnable() {
            @Override
            public void run() {
                // Get printer resolution
                CAPrinterResolution resolution = CAPrintCommon.getPrinterResolution(printerConnector);
                int maxWidth = 0;
                if (resolution != null) {
                    maxWidth = resolution.getWidthMM() * resolution.getDotsPerMM();
                }
                
                // Print parameters
                int binaryzationMethod = 2;  // Error diffusion
                int compressionMethod = 0;  // Compression method
                int paperType = 1;           // 1=Serial, 2=Gap, 3=BlackMarker
                int printAlignment = 1;      // Alignment
                int printSpeed = 150;        // Print speed
                int printDensity = 7;       // Print density (0-15)
                boolean kickDrawer = false;   // Open cash drawer before print
                double feedPaper = 10.0;     // Feed paper after print (mm)
                int cutPaper = 0;            // Cut paper after print (0=No, 1=Yes)
                int waitPrintFinished = 30000; // Wait for print completion (ms)
                
                // Print bitmap
                boolean result = CAPrintCommon.printBitmap(
                    printerConnector,
                    bitmap,
                    binaryzationMethod,
                    compressionMethod,
                    paperType,
                    printAlignment,
                    printSpeed,
                    printDensity,
                    kickDrawer,
                    feedPaper,
                    cutPaper,
                    waitPrintFinished
                );
                
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        dialog.dismiss();
                        if (result) {
                            Toast.makeText(activity, "Print successful", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(activity, "Print failed", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        }).start();
    }
}
```

### 5.5 Print Text/Receipt

```java
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.StaticLayout;
import android.text.Layout;
import android.text.TextPaint;

public class ReceiptPrinter {
    
    public static Bitmap createReceiptBitmap(String text, int width) {
        TextPaint paint = new TextPaint();
        paint.setColor(Color.BLACK);
        paint.setTextSize(24);
        paint.setAntiAlias(true);
        
        StaticLayout layout = new StaticLayout(
            text,
            paint,
            width,
            Layout.Alignment.ALIGN_NORMAL,
            1.0f,
            0.0f,
            false
        );
        
        int height = layout.getHeight();
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        layout.draw(canvas);
        
        return bitmap;
    }
    
    public static Bitmap createSampleReceipt() {
        int width = 384; // 58mm printer width
        
        StringBuilder receipt = new StringBuilder();
        receipt.append("=== RECEIPT ===\n");
        receipt.append("Date: ").append(new Date()).append("\n");
        receipt.append("----------------\n");
        receipt.append("Item 1        $10.00\n");
        receipt.append("Item 2        $20.00\n");
        receipt.append("----------------\n");
        receipt.append("Total         $30.00\n");
        receipt.append("================\n");
        receipt.append("Thank you!\n");
        
        return createReceiptBitmap(receipt.toString(), width);
    }
}
```

---

## 📱 Step 6: Request Runtime Permissions

For Android 6.0+, request permissions at runtime:

```java
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class PermissionHelper {
    private static final int PERMISSION_REQUEST_CODE = 1001;
    
    public static boolean hasAllPermissions(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            String[] permissions = getRequiredPermissions();
            for (String permission : permissions) {
                if (ContextCompat.checkSelfPermission(activity, permission) 
                    != PackageManager.PERMISSION_GRANTED) {
                    return false;
                }
            }
        }
        return true;
    }
    
    public static String[] getRequiredPermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES);
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        
        return permissions.toArray(new String[0]);
    }
    
    public static void requestPermissions(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ActivityCompat.requestPermissions(
                activity,
                getRequiredPermissions(),
                PERMISSION_REQUEST_CODE
            );
        }
    }
}
```

---

## 🔄 Step 7: Monitor Connection Status

```java
import com.caysn.autoreplyprint.caprint.CAPrinterConnector;

public class PrinterStatusMonitor {
    private CAPrinterConnector printerConnector;
    
    public PrinterStatusMonitor(CAPrinterConnector connector) {
        this.printerConnector = connector;
        setupConnectionListener();
    }
    
    private void setupConnectionListener() {
        printerConnector.registerConnectionStatusChangedEvent(
            new CAPrinterConnector.ConnectionStatusChangedInterface() {
                @Override
                public void onConnectionStatusChanged() {
                    // Handle connection status change
                    if (printerConnector.isCurrentConnectedPrinter()) {
                        // Printer connected
                        onPrinterConnected();
                    } else {
                        // Printer disconnected
                        onPrinterDisconnected();
                    }
                }
            }
        );
    }
    
    private void onPrinterConnected() {
        // Update UI, enable print buttons, etc.
    }
    
    private void onPrinterDisconnected() {
        // Update UI, disable print buttons, etc.
    }
    
    public void cleanup() {
        printerConnector.unregisterConnectionStatusChangedEvent(
            // Pass the same listener instance you registered
        );
    }
}
```

---

## 🎯 Step 8: Complete Example Activity

Here's a complete example:

```java
import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
import com.caysn.autoreplyprint.caprint.*;

public class PrintActivity extends Activity {
    private CAPrinterConnector printerConnector;
    private CAPrinterDiscover printerDiscover;
    private Button btnSearch, btnConnect, btnPrint;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_print);
        
        printerConnector = new CAPrinterConnector();
        printerDiscover = new CAPrinterDiscover();
        
        btnSearch = findViewById(R.id.btnSearch);
        btnConnect = findViewById(R.id.btnConnect);
        btnPrint = findViewById(R.id.btnPrint);
        
        btnSearch.setOnClickListener(v -> searchPrinters());
        btnConnect.setOnClickListener(v -> connectToPrinter());
        btnPrint.setOnClickListener(v -> printReceipt());
    }
    
    private void searchPrinters() {
        printerDiscover.setOnPrinterDiscoveredListener(printerDevice -> {
            runOnUiThread(() -> {
                Toast.makeText(this, 
                    "Found: " + printerDevice.printer_name, 
                    Toast.LENGTH_SHORT).show();
            });
        });
        printerDiscover.startDiscover();
    }
    
    private void connectToPrinter() {
        // Get selected printer from your UI
        CAPrinterDevice device = getSelectedPrinter();
        if (device != null) {
            printerConnector.connectPrinterAsync(device);
        }
    }
    
    private void printReceipt() {
        if (!printerConnector.isCurrentConnectedPrinter()) {
            Toast.makeText(this, "Printer not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Create receipt bitmap
        Bitmap receipt = ReceiptPrinter.createSampleReceipt();
        
        // Print in background thread
        new Thread(() -> {
            boolean result = CAPrintCommon.printBitmap(
                printerConnector,
                receipt,
                2, 0, 1, 1, 150, 7,
                false, 10.0, 0, 30000
            );
            
            runOnUiThread(() -> {
                Toast.makeText(this, 
                    result ? "Print successful" : "Print failed",
                    Toast.LENGTH_SHORT).show();
            });
        }).start();
    }
    
    private CAPrinterDevice getSelectedPrinter() {
        // Return selected printer from your list
        return null;
    }
}
```

---

## 📚 Key SDK Classes Reference

### CAPrinterConnector
- `connectPrinterAsync(CAPrinterDevice)` - Connect asynchronously
- `connectPrinterSync(CAPrinterDevice)` - Connect synchronously
- `disconnectPrinter()` - Disconnect
- `isCurrentConnectedPrinter()` - Check if connected
- `getCurrentPrinterDevice()` - Get current printer

### CAPrinterDiscover
- `startDiscover()` - Start discovering printers
- `stopDiscover()` - Stop discovery
- `setOnPrinterDiscoveredListener()` - Set discovery callback

### CAPrintCommon
- `printBitmap(connector, bitmap, ...)` - Print bitmap
- `getPrinterStatus(connector)` - Get printer status
- `getPrinterResolution(connector)` - Get resolution
- `queryPrinterBatteryLevel(connector)` - Query battery

---

## ⚠️ Important Notes

1. **Always run printing operations in a background thread** - Don't block the UI thread
2. **Check connection before printing** - Verify printer is connected
3. **Handle permissions properly** - Request runtime permissions for Android 6.0+
4. **Clean up resources** - Unregister listeners in `onDestroy()`
5. **Error handling** - Always handle connection failures gracefully

---

## 🐛 Troubleshooting

### Issue: Printer not found
- **Solution**: Ensure Bluetooth/Location permissions are granted and services are enabled

### Issue: Connection fails
- **Solution**: Check if printer is in range, powered on, and not connected to another device

### Issue: Print fails
- **Solution**: Verify printer is connected, check paper, and ensure bitmap is valid

### Issue: ProGuard errors
- **Solution**: Add all ProGuard rules from Step 4

---

## 📖 Additional Resources

- Check the sample project's documentation in `doc/` folder
- Review the sample code in `caprintsample/src/main/java/`
- Refer to API documentation: `doc/api_documentation_en.pdf`

---

## ✅ Integration Checklist

- [ ] AAR file added to project
- [ ] build.gradle configured
- [ ] Permissions added to AndroidManifest.xml
- [ ] ProGuard rules added (if using)
- [ ] Runtime permissions requested
- [ ] Printer discovery implemented
- [ ] Printer connection implemented
- [ ] Print functionality implemented
- [ ] Error handling added
- [ ] Tested on device

---

**Happy Printing! 🖨️**

