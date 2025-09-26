# SDK Installation Guide - ElintPOS

This guide explains how to install printer SDKs for enhanced functionality in the ElintPOS Android app.

## Overview

The ElintPOS app supports multiple printer SDKs for advanced printing capabilities:
- **Epson ePOS2 SDK** - For Epson thermal printers
- **XPrinter SDK** - For XPrinter thermal printers
- **ESC/POS Printing** - Universal thermal printer support (always available)

## Installation Methods

### Method 1: Using the SDK Installer (Recommended)

1. Open the ElintPOS app
2. Navigate to the print test page
3. Click "Install SDKs" button
4. Follow the on-screen instructions for each SDK

### Method 2: Manual Installation

#### Epson ePOS2 SDK

1. **Download the SDK:**
   - Visit: https://download.epson-biz.com/modules/pos/index.php?page=single_soft&cid=4571&scat=58&pcat=3
   - Download the "ePOS2 SDK for Android"
   - Extract the downloaded ZIP file

2. **Install the SDK:**
   - Find the AAR file in the extracted folder (usually named `epos2-2.8.0.aar`)
   - Copy the AAR file to `app/libs/` directory in your project
   - Rename it to `epson-epos2-2.8.0.aar`
   - Rebuild the project

3. **Verify Installation:**
   - Run the app and check Printer Diagnostics
   - Epson SDK should show as "Available"

#### XPrinter SDK

1. **Download the SDK:**
   - Visit: https://www.xprinter.com/download/android-sdk
   - Download the XPrinter Android SDK
   - Extract the downloaded package

2. **Install the SDK:**
   - Find the AAR file in the extracted folder
   - Copy the AAR file to `app/libs/` directory in your project
   - Rename it to `xprinter-sdk.aar`
   - Rebuild the project

3. **Verify Installation:**
   - Run the app and check Printer Diagnostics
   - XPrinter SDK should show as "Available"

## Directory Structure

After manual installation, your `app/libs/` directory should contain:

```
app/libs/
├── epson-epos2-2.8.0.aar    # Epson SDK (if installed)
├── xprinter-sdk.aar         # XPrinter SDK (if installed)
└── epson-sdk-instructions.txt  # Installation instructions
```

## Troubleshooting

### Build Errors

If you encounter build errors after installing SDKs:

1. **Clean and Rebuild:**
   ```bash
   ./gradlew clean
   ./gradlew build
   ```

2. **Check AAR File Names:**
   - Ensure AAR files are named exactly as specified
   - Check that files are in the correct `app/libs/` directory

3. **Verify Dependencies:**
   - Make sure all required dependencies are in `build.gradle.kts`
   - Check that repositories are properly configured in `settings.gradle`

### SDK Not Detected

If the app doesn't detect installed SDKs:

1. **Check File Location:**
   - Verify AAR files are in `app/libs/` directory
   - Ensure file names match exactly

2. **Rebuild Project:**
   - Clean and rebuild the project
   - Restart Android Studio

3. **Check Logs:**
   - Look for error messages in the build output
   - Check the app logs for SDK detection issues

## Features by SDK

### Epson ePOS2 SDK
- Receipt and label printing
- Barcode and QR code generation
- Image printing support
- Advanced formatting options
- Multiple printer model support

### XPrinter SDK
- Thermal receipt printing
- Bluetooth and USB connectivity
- Custom paper size support
- High-speed printing
- Multi-language support

### ESC/POS (Always Available)
- Universal thermal printer support
- USB, Bluetooth, and LAN printing
- Basic text and receipt printing
- Works without vendor SDKs

## Support

For issues with SDK installation or functionality:

1. Check the Printer Diagnostics in the app
2. Review the installation instructions in `app/libs/` directory
3. Ensure all dependencies are properly installed
4. Verify printer connectivity and permissions

## Notes

- **ESC/POS printing works without any SDKs** - you can use basic printing functionality immediately
- **Vendor SDKs provide enhanced features** - install them for advanced printing capabilities
- **Manual installation is required** due to repository access restrictions
- **All SDKs are optional** - the app functions fully with just ESC/POS support
