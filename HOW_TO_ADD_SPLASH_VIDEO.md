# How to Add Splash Video to Your Android App

Your Android app now has a video splash screen feature! Follow these steps to add your video:

## Quick Start

1. **Get your video ready**
   - Format: MP4 (recommended)
   - Duration: 3-10 seconds
   - Resolution: 1920x1080 or lower
   - File size: Under 5MB
   - Aspect ratio: 16:9 (landscape)

2. **Place your video file**
   
   **Option 1 - res/raw folder (Recommended):**
   ```
   app/src/main/res/raw/splash_video.mp4
   ```
   
   **Option 2 - assets folder:**
   ```
   app/src/main/assets/splash_video.mp4
   ```

3. **Rebuild the app**
   ```bash
   ./gradlew assembleDebug
   ```
   or use Android Studio's "Build > Rebuild Project"

## How It Works

- **On App Launch**: Video plays automatically
- **After Video**: Automatically transitions to the main WebView
- **Skip Option**: Users can tap "Skip" button to bypass the video
- **No Video**: If no video is found, app shows a message and continues to the main screen after 2 seconds

## Customization Options

### Change Video Name
If you want to use a different filename, edit `SplashActivity.kt`:

```kotlin
// Line 60: Change "splash_video" to your filename (without extension)
val videoResourceId = resources.getIdentifier("your_video_name", "raw", packageName)
```

### Remove Skip Button
Edit `app/src/main/res/layout/activity_splash.xml`:

```xml
<!-- Set visibility to "gone" -->
<TextView
    android:id="@+id/skipButton"
    android:visibility="gone"
    ... />
```

### Change Skip Button Text/Style
Edit `app/src/main/res/layout/activity_splash.xml`:

```xml
<TextView
    android:id="@+id/skipButton"
    android:text="Skip Intro"
    android:textColor="#FFFFFF"
    android:textSize="16sp"
    ... />
```

### Enable Back Button to Skip
Edit `SplashActivity.kt` line 146-150:

```kotlin
@Deprecated("Deprecated in Java")
override fun onBackPressed() {
    // Uncomment to allow back button to skip
    navigateToMainActivity()
}
```

## Files Modified

- ✅ `app/src/main/java/com/elintpos/wrapper/SplashActivity.kt` - Added video playback logic
- ✅ `app/src/main/res/layout/activity_splash.xml` - Created layout with VideoView
- ✅ `app/src/main/res/raw/` - Created folder for video file
- ✅ `app/src/main/AndroidManifest.xml` - Already configured (SplashActivity is the launcher)

## Testing Without Video

The app will still work if you don't add a video file. It will:
1. Show a toast message: "No splash video found..."
2. Wait 2 seconds
3. Navigate to the main WebView

## Supported Video Formats

- **MP4** (H.264 codec) - ✅ Recommended
- **3GP**
- **WEBM**
- **MKV** (may have limited support)

## Troubleshooting

### Video doesn't play
1. Check file name is exactly `splash_video.mp4`
2. Check file is in correct folder
3. Rebuild the project (Clean & Rebuild)
4. Check video codec (H.264 is most compatible)

### Video quality issues
- Reduce resolution (try 720p instead of 1080p)
- Re-encode with H.264 codec
- Reduce bitrate

### App crashes on startup
- Check video file is not corrupted
- Try a different video format
- Check logcat for error messages

## Example Video Specifications

**Good Example:**
```
Format: MP4 (H.264)
Resolution: 1920x1080
Duration: 5 seconds
File size: 3MB
FPS: 30
```

Need help? Check the video player implementation in `SplashActivity.kt`
