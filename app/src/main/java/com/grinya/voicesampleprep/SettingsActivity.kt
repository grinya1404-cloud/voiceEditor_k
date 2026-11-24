package com.grinya.voicesampleprep

import android.content.SharedPreferences
import android.os.Bundle
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    companion object {
        const val PREFS = "vsp_prefs"
        const val KEY_STEREO_WAVES = "stereo_waves"
        const val KEY_AUTO_TRIM_THRESHOLD = "auto_trim_threshold"
        const val KEY_FADE_MS = "fade_ms"
        const val KEY_NORMALIZE_TARGET = "normalize_target"
        const val KEY_VAD_THRESHOLD = "vad_threshold"
        const val KEY_VAD_HOLD = "vad_hold"
        const val KEY_DEESSER_THRESHOLD = "deesser_threshold"
        const val KEY_DEESSER_REDUCTION = "deesser_reduction"
        const val KEY_COMP_THRESHOLD = "comp_threshold"
        const val KEY_COMP_RATIO = "comp_ratio"
        const val KEY_COMP_ATTACK = "comp_attack"
        const val KEY_COMP_RELEASE = "comp_release"
        const val KEY_COMP_MAKEUP = "comp_makeup"
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var etAutoTrim: EditText
    private lateinit var etFade: EditText
    private lateinit var etNormalize: EditText
    private lateinit var etVadThreshold: EditText
    private lateinit var etVadHold: EditText
    private lateinit var etDeEsserThreshold: EditText
    private lateinit var etDeEsserReduction: EditText
    private lateinit var etCompThreshold: EditText
    private lateinit var etCompRatio: EditText
    private lateinit var etCompAttack: EditText
    private lateinit var etCompRelease: EditText
    private lateinit var etCompMakeup: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        // Чекбоксы
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

        // Поля ввода
        etAutoTrim = findViewById(R.id.etAutoTrimThreshold)
        etFade = findViewById(R.id.etFadeMs)
        etNormalize = findViewById(R.id.etNormalizeTarget)

        etAutoTrim.setText(prefs.getFloat(KEY_AUTO_TRIM_THRESHOLD, 0.001f).toString())
        etFade.setText(prefs.getInt(KEY_FADE_MS, 5).toString())
        etNormalize.setText(prefs.getFloat(KEY_NORMALIZE_TARGET, 0.9f).toString())

        etVadThreshold = findViewById(R.id.etVadThreshold)
        etVadHold = findViewById(R.id.etVadHold)
        etDeEsserThreshold = findViewById(R.id.etDeEsserThreshold)
        etDeEsserReduction = findViewById(R.id.etDeEsserReduction)
        etCompThreshold = findViewById(R.id.etCompThreshold)
        etCompRatio = findViewById(R.id.etCompRatio)
        etCompAttack = findViewById(R.id.etCompAttack)
        etCompRelease = findViewById(R.id.etCompRelease)
        etCompMakeup = findViewById(R.id.etCompMakeup)

        etVadThreshold.setText(prefs.getFloat(KEY_VAD_THRESHOLD, -40f).toString())
        etVadHold.setText(prefs.getInt(KEY_VAD_HOLD, 100).toString())
        etDeEsserThreshold.setText(prefs.getFloat(KEY_DEESSER_THRESHOLD, -30f).toString())
        etDeEsserReduction.setText(prefs.getFloat(KEY_DEESSER_REDUCTION, 6f).toString())
        etCompThreshold.setText(prefs.getFloat(KEY_COMP_THRESHOLD, -18f).toString())
        etCompRatio.setText(prefs.getFloat(KEY_COMP_RATIO, 3f).toString())
        etCompAttack.setText(prefs.getFloat(KEY_COMP_ATTACK, 10f).toString())
        etCompRelease.setText(prefs.getFloat(KEY_COMP_RELEASE, 100f).toString())
        etCompMakeup.setText(prefs.getFloat(KEY_COMP_MAKEUP, 2f).toString())

        findViewById<android.view.View>(R.id.btnAbout).setOnClickListener {
            AlertDialog.Builder(this).setTitle("О программе").setMessage(
                "VoiceSamplePrep v0.1\n\nПриложение для подготовки голосовых сэмплов.\n\n" +
                "Запись, нарезка, маркеры, экспорт по маркерам в WAV (16/24-bit).\n\n© grinya"
            ).setPositiveButton("OK", null).show()
        }
    }

    override fun onPause() {
        super.onPause()
        // Сохраняем значения при выходе с экрана
        val autoTrimVal = etAutoTrim.text.toString().toFloatOrNull() ?: 0.001f
        val fadeVal = etFade.text.toString().toIntOrNull() ?: 5
        val normalizeVal = etNormalize.text.toString().toFloatOrNull() ?: 0.9f

        prefs.edit()
        .putFloat(KEY_AUTO_TRIM_THRESHOLD, autoTrimVal)
        .putInt(KEY_FADE_MS, fadeVal)
        .putFloat(KEY_NORMALIZE_TARGET, normalizeVal)
        .putFloat(KEY_VAD_THRESHOLD, etVadThreshold.text.toString().toFloatOrNull() ?: -40f)
        .putInt(KEY_VAD_HOLD, etVadHold.text.toString().toIntOrNull() ?: 100)
        .putFloat(KEY_DEESSER_THRESHOLD, etDeEsserThreshold.text.toString().toFloatOrNull() ?: -30f)
        .putFloat(KEY_DEESSER_REDUCTION, etDeEsserReduction.text.toString().toFloatOrNull() ?: 6f)
        .putFloat(KEY_COMP_THRESHOLD, etCompThreshold.text.toString().toFloatOrNull() ?: -18f)
        .putFloat(KEY_COMP_RATIO, etCompRatio.text.toString().toFloatOrNull() ?: 3f)
        .putFloat(KEY_COMP_ATTACK, etCompAttack.text.toString().toFloatOrNull() ?: 10f)
        .putFloat(KEY_COMP_RELEASE, etCompRelease.text.toString().toFloatOrNull() ?: 100f)
        .putFloat(KEY_COMP_MAKEUP, etCompMakeup.text.toString().toFloatOrNull() ?: 2f)
        .apply()
    }
}
