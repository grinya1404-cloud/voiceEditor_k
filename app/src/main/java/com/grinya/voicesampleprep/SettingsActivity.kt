package com.grinya.voicesampleprep

import android.content.SharedPreferences
import android.os.Bundle
import android.widget.CheckBox
import android.widget.ImageButton
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    companion object {
        const val PREFS = "vsp_prefs"
        const val KEY_STEREO_WAVES = "stereo_waves"
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

        findViewById<android.view.View>(R.id.btnAbout).setOnClickListener {
            AlertDialog.Builder(this).setTitle("О программе").setMessage(
                "VoiceSamplePrep v0.1\n\nПриложение для подготовки голосовых сэмплов.\n\n" +
                        "Запись, нарезка, маркеры, экспорт по маркерам в WAV (16/24-bit).\n\n© grinya"
            ).setPositiveButton("OK", null).show()
        }
    }
}