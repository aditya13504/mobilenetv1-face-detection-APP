package com.example.facedetection

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreference

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings, SettingsFragment())
                .commit()
        }

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            // Set up preference listeners
            setupPreferenceListeners()
        }

        private fun setupPreferenceListeners() {
            // Confidence threshold preference
            findPreference<SeekBarPreference>("confidence_threshold")?.let { pref ->
                pref.setOnPreferenceChangeListener { _, newValue ->
                    val confidence = (newValue as Int) / 100f
                    pref.summary = "Current: ${String.format("%.2f", confidence)}"
                    true
                }
                // Set initial summary
                val currentValue = pref.value
                pref.summary = "Current: ${String.format("%.2f", currentValue / 100f)}"
            }

            // Show landmarks preference
            findPreference<SwitchPreference>("show_landmarks")?.let { pref ->
                pref.setOnPreferenceChangeListener { _, newValue ->
                    val showLandmarks = newValue as Boolean
                    pref.summary = if (showLandmarks) "Facial landmarks will be displayed"
                    else "Facial landmarks will be hidden"
                    true
                }
                // Set initial summary
                pref.summary = if (pref.isChecked) "Facial landmarks will be displayed"
                else "Facial landmarks will be hidden"
            }

            // GPU acceleration preference
            findPreference<SwitchPreference>("use_gpu")?.let { pref ->
                pref.setOnPreferenceChangeListener { _, newValue ->
                    val useGpu = newValue as Boolean
                    pref.summary = if (useGpu) "GPU acceleration enabled (requires app restart)"
                    else "Using CPU processing"
                    true
                }
                // Set initial summary
                pref.summary = if (pref.isChecked) "GPU acceleration enabled"
                else "Using CPU processing"
            }
        }
    }
}