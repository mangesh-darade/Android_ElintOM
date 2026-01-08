# PowerShell script to extract, clean, and repackage autoreplyprint.aar
# This removes duplicate androidx.coordinatorlayout R classes

$ErrorActionPreference = "Stop"

$aarPath = "app\libs\autoreplyprint.aar"
$tempDir = "app\libs\autoreplyprint_temp"
$backupPath = "app\libs\autoreplyprint.aar.backup"

Write-Host "Fixing autoreplyprint.aar to remove duplicate R classes..." -ForegroundColor Cyan

# Create backup
if (Test-Path $aarPath) {
    Write-Host "Creating backup..." -ForegroundColor Yellow
    Copy-Item $aarPath $backupPath -Force
} else {
    Write-Host "Error: $aarPath not found!" -ForegroundColor Red
    exit 1
}

# Create temp directory
if (Test-Path $tempDir) {
    Remove-Item $tempDir -Recurse -Force
}
New-Item -ItemType Directory -Path $tempDir | Out-Null

try {
    # Extract AAR (AAR is just a ZIP file)
    Write-Host "Extracting AAR..." -ForegroundColor Yellow
    Expand-Archive -Path $aarPath -DestinationPath $tempDir -Force
    
    # Extract classes.jar
    $classesJar = Join-Path $tempDir "classes.jar"
    $classesDir = Join-Path $tempDir "classes_extracted"
    if (Test-Path $classesJar) {
        New-Item -ItemType Directory -Path $classesDir | Out-Null
        Expand-Archive -Path $classesJar -DestinationPath $classesDir -Force
        
        # Remove duplicate R classes
        $rClassPath = Join-Path $classesDir "androidx\coordinatorlayout"
        if (Test-Path $rClassPath) {
            Write-Host "Removing duplicate androidx.coordinatorlayout R classes..." -ForegroundColor Yellow
            Remove-Item $rClassPath -Recurse -Force
            Write-Host "Removed duplicate R classes" -ForegroundColor Green
        } else {
            Write-Host "Warning: androidx.coordinatorlayout R classes not found in expected location" -ForegroundColor Yellow
        }
        
        # Recreate classes.jar
        Write-Host "Repackaging classes.jar..." -ForegroundColor Yellow
        Remove-Item $classesJar -Force
        Set-Location $classesDir
        jar -cf ..\classes.jar *
        Set-Location ..\..
    }
    
    # Recreate AAR
    Write-Host "Repackaging AAR..." -ForegroundColor Yellow
    Remove-Item $aarPath -Force
    Set-Location $tempDir
    jar -cf ..\autoreplyprint.aar *
    Set-Location ..\..
    
    Write-Host "Success! Fixed AAR created. Backup saved as $backupPath" -ForegroundColor Green
    Write-Host "You can now rebuild your project." -ForegroundColor Green
    
} catch {
    Write-Host "Error: $_" -ForegroundColor Red
    Write-Host "Restoring backup..." -ForegroundColor Yellow
    Copy-Item $backupPath $aarPath -Force
    exit 1
} finally {
    # Cleanup
    if (Test-Path $tempDir) {
        Remove-Item $tempDir -Recurse -Force
    }
}

