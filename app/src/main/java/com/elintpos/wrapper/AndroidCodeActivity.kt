package com.elintpos.wrapper

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.elintpos.wrapper.config.AppConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Shown after printer setup when no shop URL is saved yet.
 * The user enters the Android (merchant) code, the merchant directory returns the shop URL,
 * it is saved, and MainActivity opens it (the web login follows).
 */
class AndroidCodeActivity : ComponentActivity() {

    private lateinit var input: EditText
    private lateinit var button: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(28), dp(24), dp(28))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.parseColor("#E2E8F0"))
            }
        }
        card.addView(ImageView(this).apply {
            setImageResource(R.drawable.logo)
            adjustViewBounds = true
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(72)))
        card.addView(TextView(this).apply {
            text = "Connect your shop"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(4))
        })
        card.addView(TextView(this).apply {
            text = "Enter your Android code to continue"
            textSize = 14f
            setTextColor(Color.parseColor("#64748B"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(20))
        })
        input = EditText(this).apply {
            hint = "Android code (e.g. LH022)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setSingleLine(true)
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.parseColor("#0F172A"))
            setHintTextColor(Color.parseColor("#94A3B8"))
            setPadding(dp(14), 0, dp(14), 0)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F8FAFC"))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.parseColor("#CBD5E1"))
            }
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, _, _ -> connect(); true }
        }
        card.addView(input, LinearLayout.LayoutParams(-1, dp(52)))
        status = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#DC2626"))
            setPadding(0, dp(10), 0, dp(10))
        }
        card.addView(status, LinearLayout.LayoutParams(-1, -2))
        progress = ProgressBar(this).apply { visibility = View.GONE }
        card.addView(progress, LinearLayout.LayoutParams(dp(32), dp(32)))
        button = Button(this).apply {
            text = "Connect"
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#6200EA"))
                cornerRadius = dp(10).toFloat()
            }
            setOnClickListener { connect() }
        }
        card.addView(button, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(8) })

        val inner = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            val cardWidth = minOf(dp(420), resources.displayMetrics.widthPixels - dp(48))
            addView(card, LinearLayout.LayoutParams(cardWidth, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val root = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F1F5F9"))
            isFillViewport = true
            addView(inner, android.widget.FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(root)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Setup is mandatory
    }

    private fun connect() {
        val code = input.text.toString().trim().uppercase()
        if (!code.matches(Regex("^[A-Za-z0-9_-]{3,64}$"))) {
            status.text = "Code must be 3 to 64 letters or numbers."
            return
        }
        button.isEnabled = false
        progress.visibility = View.VISIBLE
        status.text = ""
        Thread {
            var url = ""
            var name = ""
            var company = ""
            var err: String? = null
            try {
                val master = AppConfig.Network.SETUP_MASTER_URL.trimEnd('/')
                val c = URL("$master/attendance_api/setup").openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 15000
                c.readTimeout = 15000
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.setRequestProperty("Accept", "application/json")
                c.outputStream.use { it.write("android_code=${URLEncoder.encode(code, "UTF-8")}".toByteArray()) }
                val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
                val json = JSONObject(stream?.bufferedReader()?.use { it.readText() } ?: "{}")
                if (json.optString("status").equals("success", true) && json.optString("url").isNotBlank()) {
                    url = json.optString("url").trim()
                    name = json.optString("name")
                    company = json.optString("code")
                } else {
                    err = json.optString("message").ifBlank { "Android code was not found (HTTP ${c.responseCode})" }
                }
                c.disconnect()
            } catch (e: Exception) {
                err = "Server did not respond: ${e.localizedMessage ?: "check internet"}"
            }
            runOnUiThread {
                progress.visibility = View.GONE
                if (err == null) {
                    val u = if (url.endsWith("/")) url else "$url/"
                    getSharedPreferences("settings", MODE_PRIVATE).edit()
                        .putString("selected_domain", u)
                        .putString("selected_domain_name", name)
                        .putString("android_code", code)
                        .putString("company_code", company)
                        .apply()
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                } else {
                    status.text = err
                    button.isEnabled = true
                }
            }
        }.start()
    }
}
