package com.grinya.voicesampleprep

import android.content.SharedPreferences
import android.os.Bundle
import android.widget.CheckBox
import android.widget.ImageButton
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.widget.EditText

class SettingsActivity : AppCompatActivity() {

    companion object {
        const val PREFS = "vsp_prefs"
        const val KEY_STEREO_WAVES = "stereo_waves"
        const val KEY_AUTO_TRIM_THRESHOLD = "auto_trim_threshold"
        const val KEY_FADE_MS = "fade_ms"
        const val KEY_NORMALIZE_TARGET = "normalize_target"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        val prefs: SharedPreferences = getSharedPreferences(PREFS, MODE_PRIVATE)

        val cbStereo = findViewById<CheckBox>(R.id.cbStereoWaves)
        cbStereo.isChecked = prefs.getBoolean(KEY_STEREO_WAVES, false)
        cbStereo.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_STEREO_WAVES, isChecked).apply()
        }

        val cbZeroCrossing = findViewById<CheckBox>(R.id.cbZeroCrossing)
        cbZeroCrossing.isChecked = prefs.getBoolean("zero_crossing", true)
        cbZeroCrossing.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("zero_crossing", isChecked).apply()
        }

        // --- Настройки обработки ---
        val etAutoTrim = findViewById<EditText>(R.id.etAutoTrimThreshold)
        val etFade = findViewById<EditText>(R.id.etFadeMs)
        val etNormalize = findViewById<EditText>(R.id.etNormalizeTarget)

        etAutoTrim.setText(prefs.getFloat(KEY_AUTO_TRIM_THRESHOLD, 0.001f).toString())
        etFade.setText(prefs.getInt(KEY_FADE_MS, 5).toString())
        etNormalize.setText(prefs.getFloat(KEY_NORMALIZE_TARGET, 0.9f).toString())

        etAutoTrim.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val v = etAutoTrim.text.toString().toFloatOrNull() ?: 0.001f
                prefs.edit().putFloat(KEY_AUTO_TRIM_THRESHOLD, v).apply()
            }
        }
        etFade.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val v = etFade.text.toString().toIntOrNull() ?: 5
                prefs.edit().putInt(KEY_FADE_MS, v).apply()
            }
        }
        etNormalize.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val v = etNormalize.text.toString().toFloatOrNull() ?: 0.9f
                prefs.edit().putFloat(KEY_NORMALIZE_TARGET, v).apply()
            }
        }

        findViewById<android.view.View>(R.id.btnAbout).setOnClickListener {
            AlertDialog.Builder(this).setTitle("О программе").setMessage(
                "VoiceSamplePrep v0.1\n\nПриложение для подготовки голосовых сэмплов.\n\n" +
                "Запись, нарезка, маркеры, экспорт по маркерам в WAV (16/24-bit).\n\n© grinya"
            ).setPositiveButton("OK", null).show()
        }
    }
}
