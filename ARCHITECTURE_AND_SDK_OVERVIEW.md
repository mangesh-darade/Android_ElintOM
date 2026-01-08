## ElintPOS Android Wrapper – Architecture & SDK Overview

हे डॉक्युमेंट Android प्रोजेक्ट (`Android_ElintOM`) मध्ये use झालेली **business logic**, **printer SDK integration** आणि **महत्वाचे Kotlin classes** यांचा एक full overview देते.

---

## 1. App चा मुख्य उद्देश (Business Logic)

- **Web-based POS → Android app**
  - हे app `ElintPOS` चा web version Android device वर चालवण्यासाठी एक **WebView wrapper** आहे.
  - WebView मधून POS web UI चालते, आणि native Android code:
    - thermal printers वर **receipt/invoice print** करते (Bluetooth / USB / LAN / vendor SDK),
    - PDF / Excel / CSV viewer उघडते,
    - kiosk mode, auto-start on boot, permissions इ. manage करते.

- **Main Flow (User POV)**
  1. App सुरू झाल्यावर `SplashActivity` → `InitialSetupActivity` / `SetupActivity` मध्ये:
     - base URL सेट करणे (उदा. `https://androidtesting.elintpos.in`),
     - login / token / store selection,
     - printer configuration (Bluetooth/USB/LAN इ.).
  2. नंतर `MainActivity` उघडते आणि POS web UI WebView मध्ये load होते.
  3. POS web page मधून JS calls (`window.ElintPOSNative.*`) येतात:
     - print invoice / receipt / labels,
     - printer settings उघडणे,
     - kiosk mode, auto-start, permissions वगैरे.
  4. Native code `UnifiedPrinterHandler` + vendor SDK wrappers वापरून actual printer शी बोलतो.

---

## 2. High-Level Architecture

- **Core Components**
  - `MainActivity`  
    - full-screen WebView initialize आणि configure करते.
    - `JavaScriptBridge` JS interface add करते (`"ElintPOSNative"` नावाने).
    - download handling, file chooser (camera + files), SSL handling, deep-links इ. manage करते.
  - `JavaScriptBridge`  
    - WebView JS ↔ Kotlin यामधला मुख्य **bridge**.
    - printing, receipt viewer, SDK downloads, kiosk, permissions इ. सगळे public APIs इथे आहेत.
  - `UnifiedPrinterHandler`  
    - printing साठी एक unified entry point:
      - Printer प्रोफाइल निवडणे (`PrinterConfigManager`),
      - त्या profile नुसार Bluetooth / USB / LAN / Epson / XPrinter / AutoReplyPrint / Vendor SDK वापरून print करणे.
  - `PrinterConfigManager`  
    - सगळे printer **profiles** (type, paper size, margins, connection info इ.) SharedPreferences मध्ये save/load करते.

- **Printer related helper classes**
  - ESC/POS:
    - `BluetoothEscPosPrinter`, `UsbEscPosPrinter`, `LanEscPosPrinter`
    - `ReceiptFormatter` (JSON → receipt layout / bitmap),
    - `HtmlInvoicePrinter` (HTML → bitmap → print),
    - `PrinterTester` (test prints).
  - Vendor SDK wrappers:
    - `AutoReplyPrint` (Caysn AutoReplyPrint SDK),
    - `EpsonPrinter` (Epson ePOS2 SDK),
    - `XPrinter` (XPrinter Android SDK),
    - `VendorPrinter` (इतर vendor Android SDK 3.2.0 साठी general wrapper).

- **Support modules**
  - `sdk/SdkDownloader` – Epson / XPrinter SDK डाउनलोड / manual setup instructions.
  - `viewer/*Activity` – `PdfViewerActivity`, `ExcelViewerActivity`, `CsvViewerActivity`, `ReceiptActivity`.
  - `permissions/PermissionManager` – runtime permission handling.
  - `utils/*` – `PreferencesManager`, `NetworkUtils`, `InputValidator`, `AppLogger`.

---

## 3. MainActivity – WebView + Native Integration

**File**: `app/src/main/java/com/elintpos/wrapper/MainActivity.kt`

- **WebView setup**
  - runtime मध्ये programmatically `WebView` तयार करतो आणि layout मध्ये add करतो.
  - settings:
    - `javaScriptEnabled = true`, `domStorageEnabled = true`, `databaseEnabled = true`
    - responsive layout साठी normal layout algorithm, no zoom distortion:
      - `useWideViewPort = false`
      - `loadWithOverviewMode = false`
      - `layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL`
      - `textZoom = 100`
    - user-agent string ला custom suffix: `"DesktopAndroidWebView/1366x768"`.
  - `CookieManager` वापरून first-party + third-party cookies allow.

- **URL handling / SSL**
  - internal helper:
    - `getBaseUrl()` / `getBaseDomain()` – `PreferencesManager` मधून.
    - `buildAbsoluteUrl(input)` – relative URLs base URL / current WebView URL वरून resolve करतो.
  - `WebViewClient` मध्ये:
    - logout (`/auth/logout`, `/logout`) साठी redirect allow.
    - काही POS paths (`/pos/view/`, `/sales/view/`) WebView मध्येच reload करतो.
    - external schemes:
      - `tel:`, `mailto:`, `upi:` – external app ला `Intent.ACTION_VIEW` ने पाठवतो.
      - `intent:` – parsed करून external app मध्ये open करण्याचा प्रयत्न.
    - domain check:
      - URL host `getBaseDomain()` मध्ये असेल तर WebView मध्येच.
      - इतर host → external browser.
    - `onReceivedSslError`:  
      - बेस domain किंवा त्याच्या subdomains साठी SSL error **proceed** करतो (self‑signed / internal cert support),
      - इतर domains साठी **cancel** आणि Toast.

- **Downloads / uploads / file chooser**
  - Download listener:
    - `DownloadManager` वापरून browser सारखा download experience (notification + `Downloads` folder).
  - File chooser (uploads):
    - `onShowFileChooser` → custom chooser:
      - camera capture (image), किंवा
      - file picker (`ACTION_GET_CONTENT`) with MIME filters.

- **Kiosk आणि navigation**
  - `isKioskEnabled()` → `PreferencesManager.kioskEnabled`.
  - `checkAndEnableKioskMode()`:
    - enabled असेल तर `startLockTask()` (Android lock-task kiosk mode).
  - `onBackPressed()`:
    - WebView ला history असेल तर back,
    - kiosk enabled असेल तर back block,
    - नाहीतर normal back.

- **Printer quick settings popup**
  - `showPrinterSettingsPopup()`:
    - `PrinterConfigManager` मधून last used / enabled profile घेते.
    - status:
      - connected? (`MainActivity.isBtConnected/isUsbConnected/isLanConnected` flags),
      - paper size mapping (58/80/90/112mm).
    - AlertDialog:
      - **Test Print** → `unifiedPrinterHandler.print(...)` वापरून sample receipt,
      - **Advanced Settings** → `openPrinterManagement()` (web asset page).

---

## 4. JavaScriptBridge – Web ↔ Native API

**File**: `app/src/main/java/com/elintpos/wrapper/bridge/JavaScriptBridge.kt`

WebView JS मध्ये:

```javascript
window.ElintPOSNative.printFromWeb(...)
window.ElintPOSNative.formatAndPrintInvoice(...)
// इ. इ.
```

याचे implementation इथे आहे:

- **Common helpers**
  - `createJsonResponse(ok, msg?, data?)` → सर्व JS responses साठी standard JSON (`{ ok, msg, ... }`).
  - `createErrorResponse(message)`, `createSuccessResponse(message?, data?)`.
  - `buildAbsoluteUrl(input)` – base URL आणि current WebView URL वरून absolute URL बनवतो.

- **Debug / utility methods**
  - `debugLog(message)`, `checkInterfaceAvailable()`, `debugPageInfo()` – troubleshooting साठी.
  - `forceInterfaceReconnect()` – page reload करून JS interface पुन्हा bind करतो.

- **Receipt viewer methods**
  - `openReceiptUrl(url)`, `openReceiptForOrder(orderId)`:
    - URL / orderId validate,
    - `ReceiptActivity` उघडून receipt दाखवतो.
  - `openReceiptDialogUrl(url)` / `openReceiptDialogForOrder(orderId)`:
    - full-screen dialog style viewer साठी helper URLs.

- **Unified print methods (दैनिक वापरासाठी महत्त्वाचे)**
  - `printFromWeb(text, prefer = "auto")`:
    - content HTML आहे का हे check:
      - `<html>`, `<body>`, `<div>`, `<table>`, `<style>` tags असल्यास → HTML मानतो.
      - HTML असेल तर: `unifiedPrinterHandler.printHtmlInvoice(html, preferType)` वापरून **bitmap printing** route.
      - plain text असेल तर: `unifiedPrinterHandler.print(text, preferType)` direct text printing.
    - print success/failure नुसार Toast / Retry dialog:
      - failure case मध्ये `showPrintFailedDialog(...)`:
        - Retry (पुन्हा print),
        - Printer Settings (native setup activity उघडणे),
        - Cancel.
  - `printWebContent(content, printerType = "auto")`:
    - `PreferencesManager.showPrintDialog` preference वर अवलंबून:
      - **true** → Android OS print dialog (`PrintManager`) उघडतो.
      - **false** → direct `UnifiedPrinterHandler.print(...)`.

- **Invoice / receipt / kitchen order formatting**
  - `formatAndPrintInvoice(saleDataJson, configJson = "{}", prefer = "auto")`:
    - JSON → `ReceiptFormatter.ReceiptConfig`,
    - `ReceiptFormatter.formatAndPrintInvoiceAsBitmap(...)`:
      - sale data → HTML/bitmap layout → unified printer वर bitmap print.
  - `formatAndPrintReceipt(...)`, `formatAndPrintKitchenOrder(...)`:
    - receipts / kitchen tickets साठीही bitmap printing वापरतो (text mode deprecated).

- **Printer status / settings**
  - `getPrinterStatus() / getSimplePrinterStatus()`:
    - `PrinterConfigManager` मधून active profile,
    - `MainActivity` flags मधून simple `connected` status.
    - output: `{ connected, name, paperSize, status, type }`.
  - `showPrinterSettingsPopup()`:
    - `MainActivity.showPrinterSettingsPopup()` call करतो.
  - `testPrint()`:
    - last configured/ enabled profile वर small test receipt print, failure असल्यास Retry/Settings dialog.

- **AutoReplyPrint / Epson / XPrinter / Vendor प्रिंट methods**
  - `vendorAvailable()`, `epsonAvailable()`, `xprinterAvailable()` – reflection आधारित SDK presence check.
  - `epsonPrintText(text)`, `xprinterPrintText(text)`, `vendorPrintText(text)`:
    - validate text,
    - जर SDK available नसेल तर JS ला `"SDK not available"` error.
  - AutoReplyPrint:
    - `isAutoReplyPrintAvailable()`, `autoreplyprintDiscoverPrinters()`, `autoreplyprintStopDiscovery()`,
    - `autoreplyprintIsConnected()`, `autoreplyprintDisconnect()`,
    - `autoreplyprintPrintText(text)`, `autoreplyprintPrintBitmap(base64Image)`,
    - `autoreplyprintGetStatus()` – printer resolution / status JSON मध्ये.

- **SDK download / install methods**
  - `downloadEpsonSdk()`, `downloadXPrinterSdk()`, `installAllSdks()`:
    - background coroutine मध्ये `SdkDownloader` वापरून SDK download / instruction files तयार करतो.
    - result JS callback मधून:
      - `window.onEpsonSdkDownloadComplete(json)`,
      - `window.onXPrinterSdkDownloadComplete(json)`,
      - `window.onAllSdksInstallComplete(json)`.
  - `checkSdkAvailability()`:
    - `SdkDownloader.checkSdkAvailability()` मधील map JSON मध्ये परत.

- **Settings / kiosk / permissions / session**
  - `getKioskEnabled()` / `setKioskEnabledJs(enabled)`:
    - preference सेट करून `startLockTask()` / `stopLockTask()` कॉल.
  - `getShowPrintDialog()` / `setShowPrintDialog(enabled)`:
    - print dialog checkbox साठी.
  - `getAutoStartEnabled()`, `setAutoStartEnabledJs(enabled)`.
  - `requestNotificationsPermission()` – `MainActivity.requestNotificationsPermissionIfNeeded()`.
  - `checkPermissions(permsJson)`, `requestPermissions(permsJson)`, `requestAllPermissions()`.
  - `logout()`, `clearSession()` – session clear करून `InitialSetupActivity` वर redirect.

---

## 5. UnifiedPrinterHandler – एकच entry point प्रिंटिंग साठी

**File**: `app/src/main/java/com/elintpos/wrapper/UnifiedPrinterHandler.kt`

- **जवाबदारी**
  - वेगवेगळ्या printer प्रकार आणि SDKs साठी common flow:
    1. योग्य **PrinterConfig profile** निवडणे (`getPrinterProfile(preferType)`).
    2. त्या profile नुसार **connectToPrinter(profile)**:
       - Bluetooth: `BluetoothAdapter` + `BluetoothEscPosPrinter`,
       - USB: `UsbManager` + `UsbEscPosPrinter`,
       - LAN: `LanEscPosPrinter`,
       - Epson / XPrinter / Vendor: त्यांच्या wrappers ची `isAvailable()` आणि `printText`.
    3. `printWithProfile(profile, text)`:
       - margins, paperWidth, lineSpacing, width/height multiplier इ. profile नुसार apply करून ESC/POS printers वर print.

- **Printer profile निवड logic (`getPrinterProfile`)**
  1. `preferType` (उदा. `"bluetooth"`, `"usb"`, `"lan"`, `"epson"`…) पास झाल्यास त्या type चा default profile शोधतो.
  2. नसेल तर `PrinterConfigManager.getLastUsedProfile()`.
  3. अजूनही नसेल तर **default profile** (`isDefault = true`) किंवा first enabled profile.

- **Barcode / QR support**
  - `printBarcode(data, barcodeType, height, width, position, preferType)`:
    - LAN printer असेल तर ESC/POS barcode command ने print,
    - इतर types साठी fallback `print("Barcode: $data")`.
  - `printQRCode(data, size, errorCorrection, preferType)`:
    - LAN साठी native QR print,
    - इतरांचे fallback text.

- **HTML invoice printing**
  - `printHtmlInvoice(htmlContent, preferType)`:
    - `HtmlInvoicePrinter` वापरून:
      - WebView मध्ये HTML पूर्ण render,
      - संपूर्ण content bitmap म्हणून capture,
      - paper width नुसार scale,
      - unified printer SDK वर bitmap print.

- **Connections बंद करणे**
  - `closeAll()` – Bluetooth / USB / LAN ESC/POS connections बंद.

---

## 6. PrinterConfigManager – Profiles आणि Settings

**File**: `app/src/main/java/com/elintpos/wrapper/printer/PrinterConfigManager.kt`

- **PrinterConfig data class (महत्वाचे fields)**
  - **Identification**
    - `id` – unique ID,
    - `type` – one of:
      - `bluetooth`, `usb`, `lan`, `epson`, `xprinter`, `vendor`, `autoreplyprint`.
    - `name` – user-friendly नाव (उदा. `XP-F800 USB`, `Kitchen LAN Printer`).
  - **Layout / page setup**
    - `paperWidth` – dots मध्ये (constants):
      - 58mm → 384 dots,
      - 80mm → 576 dots,
      - 90mm → 640 dots,
      - 112mm → 832 dots.
    - `leftMargin`, `rightMargin` (dots),
    - `lineSpacing` (dots),
    - `widthMultiplier`, `heightMultiplier` (ESC/POS scale).
  - **Connection info**
    - `connectionParams` (Map<String, String>):
      - Bluetooth: `mac`,
      - USB: `deviceName`, `vendorId`, `productId`,
      - LAN: `ip`, `port`.
  - **Flags**
    - `enabled`, `autoConnect`, `timeout`,
    - `isDefault`, `lastUsed`.

- **Storage**
  - `SharedPreferences` key `"printer_config"`:
    - `KEY_PROFILES` – JSON object, प्रत्येक profile id → `PrinterConfig.toJson()`.
    - `KEY_LAST_USED` – last used profile id.

- **Operations**
  - `getAllProfiles()`, `getProfilesByType(type)`,
  - `getProfile(id)`, `getDefaultProfile(type)`,
  - `saveProfile(config)`, `deleteProfile(id)`,
  - `setAsDefault(id)`,
  - `getLastUsedProfile()`, `setLastUsedProfile(id)`,
  - `createProfileFromConnection(type, name, connectionParams, paperWidth)`,
  - `duplicateProfile(id, newName)`,
  - `exportProfiles()` / `importProfiles(jsonString)` – backup/restore,
  - `clearAllProfiles()` – फक्त default profiles सोडून इतर delete,
  - `getStatistics()` – total/enabled/autoconnect counts, types इ.

---

## 7. SDKs आणि त्यांची Configuration

### 7.1 Gradle dependencies (non-printer)

**File**: `app/build.gradle.kts` – मुख्य libraries:

- **AndroidX / UI**
  - `androidx.appcompat:appcompat:1.7.0`
  - `com.google.android.material:material:1.12.0`
  - `androidx.activity:activity-ktx:1.9.0`
  - `androidx.core:core-ktx:1.13.1`

- **Barcode / QR**
  - `com.google.zxing:core:3.5.2`
  - `com.journeyapps:zxing-android-embedded:4.3.0`

- **Excel / files**
  - `com.github.SUPERCILEX.poi-android:poi:3.17` – Excel `.xlsx` reader/writer.

- **HTTP / Networking**
  - `com.squareup.okhttp3:okhttp:4.12.0`
  - `com.squareup.okhttp3:logging-interceptor:4.12.0`

- **Coroutines**
  - `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3`

### 7.2 Printer / Vendor SDK integration

- **Local AARs (fileTree)**
  - `implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))`
  - `app/libs` मध्ये ठेवलेले सर्व `.aar`/`.jar` आपोआप app मध्ये add होतात.
  - सध्या repo मध्ये:
    - `autoreplyprint.aar` – AutoReplyPrint SDK,
    - `autoreplyprint.aar.backup` – backup copy.

- **Epson ePOS2 SDK**
  - Gradle snippet (commented):
    - `// implementation("com.epson.epos2:epos2:2.8.0")`
  - व्यवहारात:
    1. Epson वेबसाइट वरून SDK download (`SDK_INSTALLATION_GUIDE.md` / comments मधील लिंक),
    2. AAR (उदा. `epos2-2.8.0.aar`) rename करून `epson-epos2-2.8.0.aar`,
    3. `app/libs/` मध्ये ठेवा,
    4. build केल्यावर `EpsonPrinter` wrapper `Class.forName("com.epson.epos2.printer.Printer")` मार्फत SDK detect करतो.

- **XPrinter SDK**
  - Public Maven वर सामान्यतः उपलब्ध नाही; म्हणून:
    1. `https://www.xprinter.com/download/android-sdk` वरून SDK download,
    2. SDK मधील AAR rename करून `xprinter-sdk.aar`,
    3. `app/libs/` मध्ये ठेवा,
    4. `XPrinter` wrapper `Class.forName("com.xprinter.sdk.XPrinterManager")` ने availability check करतो.

- **AutoReplyPrint SDK (Caysn)**
  - AAR: `app/libs/autoreplyprint.aar`
  - Wrapper class: `AutoReplyPrint.kt`
    - SDK classes:
      - `CAPrinterConnector`
      - `CAPrinterDiscover`
      - `CAPrinterDevice`
      - `CAPrintCommon`
    - reflection ने:
      - discovery listener register,
      - connect/disconnect,
      - bitmap print,
      - printer resolution/status fetch.
  - Web side वरून bridging:
    - `ElintPOSNative.autoreplyprintDiscoverPrinters()`
    - `ElintPOSNative.autoreplyprintPrintText(...)` / `PrintBitmap(...)` इ.

- **Generic Vendor SDK (Android SDK 3.2.0)**
  - Wrapper: `VendorPrinter.kt`
  - Target entry class: `"com.vendor.printer.PrinterManager"` (placeholder).
  - सध्या `printText()` व `printBitmap()` मध्ये फक्त TODO comments / log आहेत – actual SDK जोडल्यावर implement करावं.

### 7.3 AAR clean-up / R class conflicts

- **Task: `fixDuplicateAarClasses`** (`app/build.gradle.kts`)
  - Purpose: local AARs मधून येणारे duplicate `androidx.coordinatorlayout:R` classes काढणे.
  - Steps:
    1. `app/libs` मधील प्रत्येक `.aar` temp directory मध्ये unzip.
    2. `classes.jar` unpack करून `androidx/coordinatorlayout/R*.class` delete.
    3. `classes.jar` पुन्हा zip, नंतर updated AAR पुन्हा pack.
    4. मूळ AAR चे `.backup` file first time बनवतो.
  - `preBuild` task ला dependency:
    - `tasks.named("preBuild") { dependsOn("fixDuplicateAarClasses") }`

### 7.4 Packaging configuration

- `android { packaging { resources { excludes += ... } } }`
  - खालील folders exclude:
    - `"Windows SDK 2.04/**"` – Windows driver/SDK files app मध्ये जाऊ नयेत.
    - `"Android SDK 3.2.0/**"` – full vendor Android SDK docs/demos न जाता फक्त reference असलेला AAR वापरला जातो.

---

## 8. AndroidManifest – Permissions & Entry Points

**File**: `app/src/main/AndroidManifest.xml`

- **Permissions (मुख्य)**
  - Network:
    - `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`.
  - Media / Storage:
    - `READ_EXTERNAL_STORAGE` (maxSdkVersion 32),
    - `WRITE_EXTERNAL_STORAGE` (maxSdkVersion 28),
    - `MANAGE_EXTERNAL_STORAGE` (tools ignore scoped storage),
    - `READ_MEDIA_IMAGES/VIDEO/AUDIO` (Android 13+).
  - Camera + audio:
    - `CAMERA`, `RECORD_AUDIO` (WebRTC/video capture).
  - Location:
    - `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`.
  - Notifications:
    - `POST_NOTIFICATIONS`.
  - Auto-start / foreground:
    - `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`, `NEARBY_WIFI_DEVICES`.
  - Bluetooth:
    - Legacy (maxSdkVersion 30): `BLUETOOTH`, `BLUETOOTH_ADMIN`,
    - Android 12+: `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`,
    - `android.hardware.bluetooth` feature (not required).
  - USB:
    - `android.hardware.usb.host` feature (not required).

- **Application / Activities**
  - `ElintApp` – custom `Application` class.
  - `SplashActivity` – launcher (MAIN/LAUNCHER).
  - `InitialSetupActivity`, `SetupActivity`, `PrinterSetupActivity`, `LoginSettingsActivity`.
  - `MainActivity` – main POS WebView host:
    - `android:launchMode="singleTask"`,
    - landscape, non-resizeable,
    - deep links for `androidtesting.elintpos.in`,
    - USB device attach intent filter + `@xml/usb_device_filter` meta-data.
  - Viewer activities:
    - `PdfViewerActivity`, `CsvViewerActivity`, `ExcelViewerActivity`, `ReceiptActivity`.
  - `FileProvider` – `authorities="${applicationId}.fileprovider"` for PDFs / crash logs share etc.
  - `BootReceiver` + `StartupForegroundService` – auto-start on boot/unlock/update.
  - `MyDeviceAdminReceiver` – device admin for kiosk / lock-task mode.

---

## 9. पुढे कोड वाचताना कुठे पाहायचं?

- **पू्र्ण flow समजून घ्यायचा असेल तर:**
  - `SplashActivity`, `InitialSetupActivity`, `SetupActivity`, `PrinterSetupActivity` – setup screens.
  - `MainActivity` – WebView + bridge initialization + file uploads + navigation.
  - `JavaScriptBridge` – webapp calls साठी सर्व native APIs.
  - `UnifiedPrinterHandler` + `PrinterConfigManager` – printer selection आणि print pipeline.

- **Printer specific debugging साठी:**
  - ESC/POS: `escpos/` package – `BluetoothEscPosPrinter`, `UsbEscPosPrinter`, `LanEscPosPrinter`, `ReceiptFormatter`, `HtmlInvoicePrinter`.
  - AutoReplyPrint: `printer/vendor/AutoReplyPrint.kt` आणि JS मध्ये `autoreplyprint*` calls.
  - Epson / XPrinter / Vendor: त्यांच्या wrappers + `SdkDownloader`.

जर तुम्हाला यापैकी कोणत्या भागाचा **detail sequence diagram / step-by-step मराठी explanation** हवा असेल (उदा. फक्त AutoReplyPrint discovery flow किंवा HTML invoice print pipeline), तर त्या specific भागासाठी वेगळं subsection किंवा independent `.md` file देखील तयार करता येईल.


