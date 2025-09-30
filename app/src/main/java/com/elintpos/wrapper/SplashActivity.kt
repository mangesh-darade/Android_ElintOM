package com.elintpos.wrapper

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity

/**
 * SplashActivity - Application Entry Point
 * 
 * Purpose: Displays a splash screen for 5 seconds when the app launches, then transitions
 * to the main activity. This provides a professional startup experience and allows time
 * for app initialization.
 * 
 * Flow:
 * 1. App launches -> SplashActivity is shown (defined as LAUNCHER in AndroidManifest)
 * 2. Wait 5 seconds (SPLASH_DELAY)
 * 3. Launch MainActivity (main WebView interface)
 * 4. Finish SplashActivity (remove from back stack)
 * 
 * Note: @SuppressLint("CustomSplashScreen") suppresses warning about using Android 12+ Splash API
 * This custom implementation is used for compatibility with older Android versions
 */
@SuppressLint("CustomSplashScreen")
class SplashActivity : ComponentActivity() {

    /**
     * Duration to display splash screen in milliseconds
     * 5000ms = 5 seconds - provides time for app initialization and branding display
     */
    private val SPLASH_DELAY: Long = 5000 // 5 seconds

    /**
     * Called when the activity is first created
     * 
     * Sets up a delayed transition to MainActivity using Handler
     * No UI is set (setContentView not called) - uses default activity background
     * 
     * @param savedInstanceState Previously saved state (not used in splash screen)
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Schedule transition to MainActivity after SPLASH_DELAY
        // Handler posts to main thread's message queue for execution after delay
        Handler(Looper.getMainLooper()).postDelayed({
            // Create intent to launch MainActivity
            val intent = Intent(this, MainActivity::class.java)
            
            // Start MainActivity
            startActivity(intent)
            
            // Remove SplashActivity from back stack so user can't return to it
            finish()
        }, SPLASH_DELAY)
    }
}
