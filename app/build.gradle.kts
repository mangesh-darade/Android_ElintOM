import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.elintpos.wrapper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.elintpos.wrapper"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                // Prevent bundling Windows SDK files
                "Windows SDK 2.04/**",
                // Exclude vendor docs/demo; keep only referenced AAR
                "Android SDK 3.2.0/**"
            )
        }
    }
}

configurations.all {
    resolutionStrategy {
        // Force a single version of androidx.coordinatorlayout to avoid duplicate R classes
        // This resolves conflicts when local AARs include coordinatorlayout as a dependency
        force("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
    }
}

// Task to fix duplicate R classes in local AAR files
tasks.register("fixDuplicateAarClasses") {
    group = "build"
    description = "Removes duplicate androidx.coordinatorlayout R classes from local AAR files"
    
    doLast {
        val libsDir = file("libs")
        if (!libsDir.exists()) return@doLast
        
        // Clean build cache first to ensure we work with fresh AARs
        val buildDir = layout.buildDirectory.get().asFile
        val intermediatesDirs = listOf(
            File(buildDir, "intermediates/project_dex_archive"),
            File(buildDir, "intermediates/dexBuilderDebug"),
            File(buildDir, "intermediates/packaged_res")
        )
        intermediatesDirs.forEach { dir ->
            if (dir.exists()) {
                println("Cleaning build cache: ${dir.absolutePath}")
                dir.deleteRecursively()
            }
        }
        
        libsDir.listFiles { _, name -> name.endsWith(".aar") }?.forEach { aarFile ->
            println("Checking ${aarFile.name} for duplicate R classes...")
            
            val tempDir = file("${layout.buildDirectory.get().asFile}/temp_aar_fix/${aarFile.nameWithoutExtension}")
            tempDir.mkdirs()
            
            try {
                // Extract AAR (AAR is just a ZIP file)
                val zipFile = ZipFile(aarFile)
                var totalEntries = 0
                var rClassEntries = 0
                val entries = zipFile.entries().asSequence()
                entries.forEach { entry: ZipEntry ->
                    totalEntries++
                    val entryFile = File(tempDir, entry.name)
                    // Log if we find R classes anywhere
                    if (entry.name.contains("androidx/coordinatorlayout") && entry.name.contains("R")) {
                        rClassEntries++
                        println("  Found potential R class: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        entryFile.mkdirs()
                    } else {
                        entryFile.parentFile.mkdirs()
                        zipFile.getInputStream(entry).use { input: InputStream ->
                            FileOutputStream(entryFile).use { output: OutputStream ->
                                input.copyTo(output)
                            }
                        }
                    }
                }
                zipFile.close()
                println("  Processed $totalEntries entries, found $rClassEntries potential R class references")
                
                // Check and fix classes.jar if it exists
                val classesJar = File(tempDir, "classes.jar")
                if (classesJar.exists()) {
                    val classesTempDir = File(tempDir, "classes_extracted")
                    classesTempDir.mkdirs()
                    
                    // Extract classes.jar
                    val classesZipFile = ZipFile(classesJar)
                    var hasDuplicateR = false
                    var removedCount = 0
                    
                    val classesEntries = classesZipFile.entries().asSequence()
                    classesEntries.forEach { entry: ZipEntry ->
                        val entryFile = File(classesTempDir, entry.name)
                        // Skip androidx.coordinatorlayout R classes - be more aggressive
                        if (entry.name.startsWith("androidx/coordinatorlayout/R") || 
                            entry.name.startsWith("androidx/coordinatorlayout/R\$") ||
                            entry.name == "androidx/coordinatorlayout/R.class" ||
                            entry.name.matches(Regex("androidx/coordinatorlayout/R\\$.*\\.class"))) {
                            hasDuplicateR = true
                            removedCount++
                            println("  Removing duplicate: ${entry.name}")
                            return@forEach
                        }
                        
                        if (entry.isDirectory) {
                            entryFile.mkdirs()
                        } else {
                            entryFile.parentFile.mkdirs()
                            classesZipFile.getInputStream(entry).use { input: InputStream ->
                                FileOutputStream(entryFile).use { output: OutputStream ->
                                    input.copyTo(output)
                                }
                            }
                        }
                    }
                    classesZipFile.close()
                    
                    if (hasDuplicateR) {
                        // Recreate classes.jar without duplicate R classes
                        classesJar.delete()
                        ZipOutputStream(FileOutputStream(classesJar)).use { zos: ZipOutputStream ->
                            classesTempDir.walkTopDown().forEach { file: File ->
                                if (file.isFile) {
                                    val relativePath = file.relativeTo(classesTempDir).path.replace("\\", "/")
                                    zos.putNextEntry(ZipEntry(relativePath))
                                    file.inputStream().use { it.copyTo(zos) }
                                    zos.closeEntry()
                                }
                            }
                        }
                        println("  Fixed classes.jar - removed $removedCount duplicate R classes")
                        
                        // Recreate AAR with fixed classes.jar
                        val backupFile = File(aarFile.parent, "${aarFile.name}.backup")
                        if (!backupFile.exists()) {
                            aarFile.copyTo(backupFile)
                            println("  Created backup: ${backupFile.name}")
                        }
                        
                        aarFile.delete()
                        ZipOutputStream(FileOutputStream(aarFile)).use { zos: ZipOutputStream ->
                            tempDir.walkTopDown().forEach { file: File ->
                                if (file.isFile) {
                                    val relativePath = file.relativeTo(tempDir).path.replace("\\", "/")
                                    zos.putNextEntry(ZipEntry(relativePath))
                                    file.inputStream().use { it.copyTo(zos) }
                                    zos.closeEntry()
                                }
                            }
                        }
                        
                        println("  Fixed ${aarFile.name}")
                    } else {
                        println("  No duplicate R classes found in classes.jar")
                    }
                } else {
                    println("  Warning: classes.jar not found in AAR")
                }
            } catch (e: Exception) {
                println("  Error fixing ${aarFile.name}: ${e.message}")
                e.printStackTrace()
            } finally {
                // Cleanup
                tempDir.deleteRecursively()
            }
        }
    }
}

// Run fix task before building - it will handle cleaning automatically
tasks.named("preBuild") {
    dependsOn("fixDuplicateAarClasses")
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    
    // For barcode/QR code generation (optional)
    implementation("com.google.zxing:core:3.5.2")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // Excel (.xlsx) reading (Apache POI port for Android)
    implementation("com.github.SUPERCILEX.poi-android:poi:3.17")

    // Printer SDKs - Epson ePOS2 SDK
    // Note: Epson SDK requires manual download due to repository access issues
    // Download from: https://download.epson-biz.com/modules/pos/index.php?page=single_soft&cid=4571&scat=58&pcat=3
    // Place the AAR file in app/libs/ directory
    // implementation("com.epson.epos2:epos2:2.8.0")
    
    // XPrinter SDK (if available on Maven)
    // Note: XPrinter SDK might not be available on public Maven repositories
    // You may need to download it manually and place in libs folder
    // implementation("com.xprinter:xprinter-sdk:1.0.0") // Uncomment if available

    // Network and HTTP for SDK downloads
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    
    // Coroutines for async operations
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Local SDKs: auto-include any AAR/JAR in app/libs
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
}