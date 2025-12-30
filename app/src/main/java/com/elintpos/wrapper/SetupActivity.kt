package com.elintpos.wrapper

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class SetupActivity : ComponentActivity() {

    private lateinit var customerNameEditText: EditText
    private lateinit var mobileNumberEditText: EditText
    private lateinit var fetchDomainButton: Button
    private lateinit var domainSpinner: Spinner
    private lateinit var progressBar: ProgressBar
    private lateinit var printerTypeSpinner: Spinner
    private lateinit var paperSizeSpinner: Spinner
    private lateinit var autoCutSwitch: SwitchMaterial
    private lateinit var footerEditText: EditText
    private lateinit var testPrintButton: Button
    private lateinit var saveButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (prefs.getBoolean("isSetupCompleted", false)) {
            navigateToMain()
            return
        }

        setContentView(R.layout.activity_setup)
        initializeViews()

        fetchDomainButton.setOnClickListener { fetchDomains() }
        saveButton.setOnClickListener { saveSettings() }
        testPrintButton.setOnClickListener { testPrint() }

        setupSpinners()
    }

    private fun initializeViews() {
        customerNameEditText = findViewById(R.id.customerNameEditText)
        mobileNumberEditText = findViewById(R.id.mobileNumberEditText)
        fetchDomainButton = findViewById(R.id.fetchDomainButton)
        domainSpinner = findViewById(R.id.domainSpinner)
        progressBar = findViewById(R.id.progressBar)
        printerTypeSpinner = findViewById(R.id.printerTypeSpinner)
        paperSizeSpinner = findViewById(R.id.paperSizeSpinner)
        autoCutSwitch = findViewById(R.id.autoCutSwitch)
        footerEditText = findViewById(R.id.footerEditText)
        testPrintButton = findViewById(R.id.testPrintButton)
        saveButton = findViewById(R.id.saveButton)
    }

    private fun setupSpinners() {
        val printerTypes = arrayOf("Bluetooth", "USB", "LAN")
        val paperSizes = arrayOf("58mm", "80mm", "112mm")

        printerTypeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, printerTypes)
        paperSizeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, paperSizes)
    }

    private fun fetchDomains() {
        if (!isNetworkAvailable()) {
            Toast.makeText(this, "No internet connection", Toast.LENGTH_SHORT).show()
            return
        }

        val mobileNumber = mobileNumberEditText.text.toString()
        if (mobileNumber.isEmpty()) {
            Toast.makeText(this, "Please enter a mobile number", Toast.LENGTH_SHORT).show()
            return
        }

        progressBar.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client = OkHttpClient()
                val request = Request.Builder()
                    .url("https://api.elintpos.in/api/v1/instance/get-instance-by-phone/$mobileNumber")
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    if (response.isSuccessful && responseBody != null) {
                        val jsonResponse = JSONObject(responseBody)
                        if(jsonResponse.getBoolean("status")){
                            val data = jsonResponse.getJSONArray("data")
                            val domains = mutableListOf<String>()
                            for (i in 0 until data.length()) {
                                domains.add(data.getString(i))
                            }
                            domainSpinner.adapter = ArrayAdapter(this@SetupActivity, android.R.layout.simple_spinner_item, domains)
                        } else {
                             Toast.makeText(this@SetupActivity, jsonResponse.getString("message"), Toast.LENGTH_SHORT).show()
                        }
                       
                    } else {
                        Toast.makeText(this@SetupActivity, "Error fetching domains", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this@SetupActivity, "Failed to fetch domains: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val editor = prefs.edit()

        val settings = JSONObject()
        settings.put("customerName", customerNameEditText.text.toString())
        settings.put("mobileNumber", mobileNumberEditText.text.toString())
        settings.put("selectedDomain", domainSpinner.selectedItem.toString())
        settings.put("printerType", printerTypeSpinner.selectedItem.toString())
        settings.put("paperSize", paperSizeSpinner.selectedItem.toString())
        settings.put("autoCut", autoCutSwitch.isChecked)
        settings.put("footerText", footerEditText.text.toString())

        editor.putString("app_settings", settings.toString())
        editor.putString("app_url", "https://${domainSpinner.selectedItem}")
        editor.putBoolean("isSetupCompleted", true)
        editor.apply()

        navigateToMain()
    }

    private fun testPrint() {
        //  This will be implemented in a future step
        Toast.makeText(this, "Test print functionality not yet implemented", Toast.LENGTH_SHORT).show()
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false

        return when {
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
            else -> false
        }
    }

    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }
}
