package com.elintpos.wrapper

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.VideoView
import android.widget.Toast
import androidx.activity.ComponentActivity

/**
 * SplashActivity - Application Entry Point
 * 
 * Purpose: Plays a splash video when the app launches, then transitions to MainActivity.
 * This provides a professional startup experience and branding display.
 * 
 * Flow:
 * 1. App launches -> SplashActivity is shown (defined as LAUNCHER in AndroidManifest)
 * 2. Play video from raw resources or assets folder
 * 3. When video completes (or user clicks Skip) -> Launch MainActivity
 * 4. Finish SplashActivity (remove from back stack)
 * 
 * Note: @SuppressLint("CustomSplashScreen") suppresses warning about using Android 12+ Splash API
 * This custom implementation is used for compatibility with older Android versions
 */
@SuppressLint("CustomSplashScreen")
class SplashActivity : ComponentActivity() {

    private lateinit var videoView: VideoView
    private lateinit var progressBar: ProgressBar
    private lateinit var skipButton: TextView

    /**
     * Called when the activity is first created
     * 
     * Sets up VideoView to play splash video and transitions to MainActivity when complete
     * 
     * @param savedInstanceState Previously saved state (not used in splash screen)
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Initialize views
        videoView = findViewById(R.id.splashVideoView)
        progressBar = findViewById(R.id.progressBar)
        skipButton = findViewById(R.id.skipButton)

        // Set up skip button
        skipButton.setOnClickListener {
            navigateToMainActivity()
        }

        // Try to play video from raw resources
        // Note: Place your video file in app/src/main/res/raw/ folder
        // Example: app/src/main/res/raw/splash_video.mp4
        val videoResourceId = resources.getIdentifier("splash_video", "raw", packageName)
        
        if (videoResourceId != 0) {
            // Video found in raw resources
            val videoUri = Uri.parse("android.resource://$packageName/$videoResourceId")
            setupVideoView(videoUri)
        } else {
            // If no video found, try loading from assets folder
            // Example: app/src/main/assets/splash_video.mp4
            val videoPath = "android.asset://splash_video.mp4"
            val assetVideoUri = Uri.parse(videoPath)
            
            try {
                setupVideoView(assetVideoUri)
            } catch (e: Exception) {
                // If no video is found, show message and proceed to MainActivity
                Toast.makeText(this, "No splash video found. Add 'splash_video.mp4' to res/raw/ or assets/", Toast.LENGTH_LONG).show()
                
                // Wait 2 seconds then navigate to MainActivity
                videoView.postDelayed({
                    navigateToMainActivity()
                }, 2000)
            }
        }
    }

    /**
     * Sets up the VideoView with the provided video URI
     * 
     * @param videoUri URI of the video to play
     */
    private fun setupVideoView(videoUri: Uri) {
        try {
            videoView.setVideoURI(videoUri)

            // Show progress bar while video is preparing
            videoView.setOnPreparedListener { mediaPlayer ->
                progressBar.visibility = View.GONE
                
                // Optional: Make video fill the screen
                // mediaPlayer.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                
                // Start playing the video
                videoView.start()
            }

            // Navigate to MainActivity when video completes
            videoView.setOnCompletionListener {
                navigateToMainActivity()
            }

            // Handle errors
            videoView.setOnErrorListener { _, what, extra ->
                Toast.makeText(this, "Error playing video: $what, $extra", Toast.LENGTH_SHORT).show()
                progressBar.visibility = View.GONE
                
                // Navigate to MainActivity even if video fails
                navigateToMainActivity()
                true
            }

        } catch (e: Exception) {
            Toast.makeText(this, "Failed to load video: ${e.message}", Toast.LENGTH_SHORT).show()
            progressBar.visibility = View.GONE
            navigateToMainActivity()
        }
    }

    /**
     * Navigates to MainActivity and finishes this activity
     */
    private fun navigateToMainActivity() {
        // Create intent to launch MainActivity
        val intent = Intent(this, MainActivity::class.java)
        
        // Start MainActivity
        startActivity(intent)
        
        // Remove SplashActivity from back stack so user can't return to it
        finish()
    }

    /**
     * Prevent back button from closing the splash screen
     */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Do nothing - user must watch video or click skip
        // Or uncomment below to allow back button to skip
        // navigateToMainActivity()
    }
}
