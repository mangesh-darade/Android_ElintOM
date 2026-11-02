# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

ElintPOS Android wrapper (`com.elintpos.wrapper`): a WebView shell around the ElintPOS web app, plus native thermal-printer printing. Single Gradle module `:app` (Kotlin 1.9.24, AGP 8.5.0, compileSdk/targetSdk 34, minSdk 23, Java 17). Package root: `app/src/main/java/com/elintpos/wrapper/`.

`ARCHITECTURE_AND_SDK_OVERVIEW.md` is a detailed overview (written in Marathi/English); `docs/ELINTPOS_SETUP_GUIDE.md` is the end-user setup/login guide.

## Commands

Windows; use `gradlew.bat` (or `./gradlew` in Git Bash).

- Build debug APK: `./gradlew :app:assembleDebug`
- Release build: `./gradlew :app:assembleRelease` (minify is off)
- All unit tests: `./gradlew :app:testDebugUnitTest`
- Single test: `./gradlew :app:testDebugUnitTest --tests "com.elintpos.wrapper.printer.K8PrintRulesTest"`
- No lint/format config is set up. Test coverage is minimal: `K8PrintRulesTest` is the only unit test (`app/src/test/`), so printing/layout changes are mostly verified on-device.

### Local AAR quirk
`app/libs/autoreplyprint.aar` bundles duplicate `androidx.coordinatorlayout` R classes that break the build. `app/build.gradle.kts` registers a `fixDuplicateAarClasses` task that strips them (leaving `.aar.backup`), and `fix_aar.ps1` in the root does the same manually. `build.gradle` and `build.gradle.kts` both exist at the root, as do stray `AndroidManifest.xml`, `classes.jar`, `jni/`, `R.txt` (extracted AAR contents) — they are not part of the app build; the real manifest lives under `app/src/main`.

## Architecture

- **WebView host**: `MainActivity` (with `webview/WebViewManager`) loads the POS site and exposes `bridge/JavaScriptBridge` to JS as `window.ElintPOSNative`. The web page calls it to print receipts/labels, open printer settings, open PDF/Excel/CSV viewers, toggle kiosk/auto-start, request permissions.
- **Startup flow**: `SplashActivity` → `InitialSetupActivity`/`SetupActivity` (base URL/domain, login, store) → `MainActivity`. `LoginActivity`/`LoginSettingsActivity`, `api/DomainApiService` handle domain/login. The target domain is a config commit away (see git log, e.g. `1602clothingqa.elintpos.in`).
- **Printing pipeline**: `UnifiedPrinterHandler` is the single entry point. It picks a profile from `printer/PrinterConfigManager` (SharedPreferences; type, paper size, margins, spacing, cut) and dispatches to:
  - ESC/POS transports in `escpos/` (`BluetoothEscPosPrinter`, `UsbEscPosPrinter`, `LanEscPosPrinter`), with `ReceiptFormatter` converting the POS JSON to a receipt;
  - vendor SDK wrappers in `printer/vendor/` (`AutoReplyPrint`, `EpsonPrinter`, `XPrinter`, `VendorPrinter`, `TecMiK8Printer`);
  - bitmap/HTML paths (`HtmlInvoicePrinter`, `printer/K8Bill*`), and layout helpers (`ReceiptLayout`, `ReceiptText`). Recent commits tune bill column fitting, font scaling, tax summary, and 80mm layout here.
- `PrinterSetupActivity` is the UI for editing printer profiles; `PrinterTester` does test prints.
- `config/AppConfig` holds shared constants (session timeout, paper widths 384/576/832 dots, etc.); `utils/PreferencesManager` wraps prefs.
- Vendor SDKs live in `app/libs/` (AutoReplyPrint AAR, `icod_3.1.8.jar`); `sdk/SdkDownloader` handles Epson/XPrinter SDK setup. The "Android SDK 3.2.0" and "Windows SDK 2.04" folders are excluded from packaging.
