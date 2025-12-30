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

@SuppressLint("CustomSplashScreen")
class SplashActivity : ComponentActivity() {

    private lateinit var videoView: VideoView
    private lateinit var progressBar: ProgressBar
    private lateinit var skipButton: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        videoView = findViewById(R.id.splashVideoView)
        progressBar = findViewById(R.id.progressBar)
        skipButton = findViewById(R.id.skipButton)

        skipButton.setOnClickListener { navigateToNextScreen() }

        val videoResourceId = resources.getIdentifier("splash_video", "raw", packageName)
        
        if (videoResourceId != 0) {
            val videoUri = Uri.parse("android.resource://$packageName/$videoResourceId")
            setupVideoView(videoUri)
        } else {
            Toast.makeText(this, "No splash video found. Add 'splash_video.mp4' to res/raw/", Toast.LENGTH_LONG).show()
            videoView.postDelayed({ navigateToNextScreen() }, 2000)
        }
    }

    private fun setupVideoView(videoUri: Uri) {
        try {
            videoView.setVideoURI(videoUri)
            videoView.setOnPreparedListener {
                progressBar.visibility = View.GONE
                videoView.start()
            }
            videoView.setOnCompletionListener { navigateToNextScreen() }
            videoView.setOnErrorListener { _, _, _ ->
                Toast.makeText(this, "Error playing video", Toast.LENGTH_SHORT).show()
                navigateToNextScreen()
                true
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to load video: ${e.message}", Toast.LENGTH_SHORT).show()
            navigateToNextScreen()
        }
    }

    private fun navigateToNextScreen() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val isSetupCompleted = prefs.getBoolean("is_setup_completed", false)
        
        val intent = if (isSetupCompleted) {
            Intent(this, MainActivity::class.java)
        } else {
            Intent(this, InitialSetupActivity::class.java)
        }
        startActivity(intent)
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Do nothing
    }
}
