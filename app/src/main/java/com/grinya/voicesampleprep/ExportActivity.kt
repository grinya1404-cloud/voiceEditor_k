package com.grinya.voicesampleprep

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.grinya.voicesampleprep.audio.WavWriter
import com.grinya.voicesampleprep.model.AudioClip
import com.grinya.voicesampleprep.model.AudioMeta
import com.grinya.voicesampleprep.model.BankManager
import com.grinya.voicesampleprep.model.ExportSettings
import com.grinya.voicesampleprep.util.AudioOps
import com.grinya.voicesampleprep.util.ExportUtils
import com.grinya.voicesampleprep.util.FileNames
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale

class ExportActivity : AppCompatActivity() {

    companion object {
        private var bankClips: List<AudioClip>? = null
        private var bankName: String? = null

        private var source: AudioClip? = null
        private var selStart = -1
        private var selEnd = -1

        private var forcedStart = -1
        private var forcedEnd = -1

        @JvmStatic
        fun setForcedInterval(start: Int, end: Int) {
            forcedStart = start
            forcedEnd = end
        }

        @JvmStatic
        fun setSource(clip: AudioClip, s: Int, e: Int) {
            source = clip
            selStart = s
            selEnd = e
        }

        @JvmStatic
        fun setBankSource(clips: List<AudioClip>, name: String) {
            bankClips = clips
            bankName = name
            source = null
        }
    }

    private var originalMeta: AudioMeta = AudioMeta()
    private var isDefaultMode = false
    private var isOriginalMode = false

    private var forcedStartLocal = -1
    private var forcedEndLocal = -1

    private val treeLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { doExport(it) }
        }
    }

    private fun readSettings(): ExportSettings {
        val s = ExportSettings()
        s.byMarkers = findViewById<RadioButton>(R.id.rbByMarkers).isChecked
        s.bits = if (findViewById<RadioButton>(R.id.rb24).isChecked) 24 else 16
        s.rate = if (findViewById<RadioButton>(R.id.rb441).isChecked) 44100 else 48000
        s.stereoMono = findViewById<RadioButton>(R.id.rbStereoMono).isChecked
        s.trueMono = findViewById<RadioButton>(R.id.rbTrueMono).isChecked
        s.normalize = findViewById<CheckBox>(R.id.cbNormalize).isChecked
        s.fade = findViewById<CheckBox>(R.id.cbFade).isChecked
        s.trimSilence = findViewById<CheckBox>(R.id.cbTrimSilence).isChecked

        val cbDenoise = findViewById<CheckBox>(R.id.cbDenoise)
        s.denoise = cbDenoise?.isChecked == true

        val raw = findViewById<EditText>(R.id.edName).text.toString().trim()
        s.nameTemplate = if (raw.isEmpty()) "{name}_{index}" else raw

        val prefs = getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE)
        s.fadeMs = prefs.getInt(SettingsActivity.KEY_FADE_MS, 5)
        s.normalizeTarget = prefs.getFloat(SettingsActivity.KEY_NORMALIZE_TARGET, 0.9f)

        return s
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val src = source
        val clips = bankClips
        if (src == null && (clips == null || clips.isEmpty())) {
            finish()
            return
        }

        // D10: забираем принудительный интервал
        forcedStartLocal = forcedStart
        forcedEndLocal = forcedEnd
        forcedStart = -1
        forcedEnd = -1

        setContentView(R.layout.activity_export)

        if (src != null) {
            bankClips = null // D5
            originalMeta = AudioMeta.load(src.dir)
            if (forcedStartLocal != -1) {
                findViewById<View>(R.id.rgMode).visibility = View.GONE
            }
            when (originalMeta.channels) {
                1 -> findViewById<RadioButton>(R.id.rbTrueMono).isChecked = true
                2 -> findViewById<RadioButton>(R.id.rbStereo).isChecked = true
            }
            when (originalMeta.sampleRate) {
                44100 -> findViewById<RadioButton>(R.id.rb441).isChecked = true
                48000 -> findViewById<RadioButton>(R.id.rb48).isChecked = true
            }
            when (originalMeta.bits) {
                24 -> findViewById<RadioButton>(R.id.rb24).isChecked = true
                16 -> findViewById<RadioButton>(R.id.rb16).isChecked = true
            }

            if (originalMeta.sampleRate != 0 && (originalMeta.sampleRate != 48000
                    || originalMeta.channels != 2 || originalMeta.bits != 16)) {
                val hint = "Исходный файл: " +
                (if (originalMeta.sampleRate == 44100) "44.1 кГц"
                    else "${originalMeta.sampleRate} Гц") +
                ", " + (if (originalMeta.channels == 1) "моно" else "стерео") +
                (if (originalMeta.bits > 0) ", ${originalMeta.bits}-bit" else "") +
                ". Рекомендуется экспортировать так же."
                Toast.makeText(this, hint, Toast.LENGTH_LONG).show()
            }

            if (selStart >= 0 && selEnd > selStart) {
                val durationMs = (selEnd - selStart) * 1000 / src.sampleRate
                val m = durationMs / 60000
                val s = (durationMs % 60000) / 1000
                val r = durationMs % 1000
                val timeStr = String.format(Locale.US, "%02d:%02d.%03d", m, s, r)
                findViewById<RadioButton>(R.id.rbSingle)
                .text = "Один файл (выделение $timeStr)"
            } else {
                findViewById<RadioButton>(R.id.rbSingle).text = "Один файл (весь клип)"
            }

            // --- Динамический текст для режима маркеров/интервалов ---
            val rbByMarkers = findViewById<RadioButton>(R.id.rbByMarkers)
            val intervalsCount = src.exportIntervals.size
            val markersCount = src.markers.size

            if (intervalsCount > 0) {
                rbByMarkers.text = "По интервалам ($intervalsCount)"
            } else if (markersCount >= 2) {
                rbByMarkers.text = "По маркерам ($markersCount)"
            } else {
                rbByMarkers.isEnabled = false
                rbByMarkers.text = "По маркерам (нужно ≥ 2)"
            }

        } else {
            originalMeta = AudioMeta()
            findViewById<RadioButton>(R.id.rbSingle).text = "Один файл (весь клип)"
        }

        // --- Обновляем текст чекбоксов с учетом настроек ---
        val prefs = getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE)
        val fadeMs = prefs.getInt(SettingsActivity.KEY_FADE_MS, 5)
        val normalizeTarget = prefs.getFloat(SettingsActivity.KEY_NORMALIZE_TARGET, 0.9f)

        findViewById<CheckBox>(R.id.cbNormalize).text = "Нормализовать (пик $normalizeTarget)"
        findViewById<CheckBox>(R.id.cbFade).text = "Fade $fadeMs мс по краям (анти-щелчок)"

        findViewById<View>(R.id.btnExport).setOnClickListener { startExport() }
        findViewById<View>(R.id.btnCancel).setOnClickListener { finish() }

        findViewById<View>(R.id.btnDefaults).setOnClickListener {
            findViewById<RadioButton>(R.id.rb48).isChecked = true
            findViewById<RadioButton>(R.id.rbStereo).isChecked = true
            findViewById<RadioButton>(R.id.rb16).isChecked = true
            isDefaultMode = true
            isOriginalMode = false
            updateButtonHighlights()
        }

        findViewById<View>(R.id.btnOriginal).setOnClickListener {
            if (originalMeta.sampleRate == 0 && originalMeta.channels == 0) {
                Toast.makeText(this, "Исходные параметры неизвестны", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (originalMeta.sampleRate == 44100)
            findViewById<RadioButton>(R.id.rb441).isChecked = true
            else
            findViewById<RadioButton>(R.id.rb48).isChecked = true

            when (originalMeta.channels) {
                1 -> findViewById<RadioButton>(R.id.rbTrueMono).isChecked = true
                2 -> findViewById<RadioButton>(R.id.rbStereo).isChecked = true
            }
            when (originalMeta.bits) {
                24 -> findViewById<RadioButton>(R.id.rb24).isChecked = true
                16 -> findViewById<RadioButton>(R.id.rb16).isChecked = true
            }
            isOriginalMode = true
            isDefaultMode = false
            updateButtonHighlights()
        }

        val resetListener = android.widget.RadioGroup.OnCheckedChangeListener { _, _ ->
            isDefaultMode = false
            isOriginalMode = false
            updateButtonHighlights()
        }
        findViewById<android.widget.RadioGroup>(R.id.rgBits).setOnCheckedChangeListener(resetListener)
        findViewById<android.widget.RadioGroup>(R.id.rgRate).setOnCheckedChangeListener(resetListener)
        findViewById<android.widget.RadioGroup>(R.id.rgChannels).setOnCheckedChangeListener(resetListener)
    }

    private fun startExport() {
        treeLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))
    }

    private fun doExport(treeUri: Uri) {
        val settings = readSettings()
        val clips = bankClips
        if (clips != null && clips.isNotEmpty()) {
            exportBank(treeUri, settings)
            return
        }

        val src = source ?: return

        val ranges: List<IntArray> = if (forcedStartLocal != -1 && forcedEndLocal > forcedStartLocal) {
            listOf(intArrayOf(forcedStartLocal, forcedEndLocal))
        } else {
            buildRanges(settings.byMarkers)
        }
        if (ranges.isEmpty()) return

        val dir = DocumentFile.fromTreeUri(this, treeUri)
        val written = mutableListOf<String>()
        val failed = intArrayOf(0)

        Thread {
            ranges.forEachIndexed { idx, r ->
                var fileName: String? = null
                try {
                    fileName = exportSingleRangeFromClip(treeUri, dir, src, r, idx + 1, settings)
                    if (fileName != null) written.add(fileName) else failed[0]++
                } catch (e: Exception) {
                    android.util.Log.e("EXPORT", "Failed for $fileName", e)
                    failed[0]++
                }
            }
            runOnUiThread { showExportResult(written.size, failed[0]) }
        }.start()
    }

    private fun exportBank(treeUri: Uri, settings: ExportSettings) {
        val clips = bankClips ?: return
        val written = mutableListOf<String>()
        val failed = intArrayOf(0)
        val dir = DocumentFile.fromTreeUri(this, treeUri)
        // D6: один BankManager на цикл
        val bm = BankManager(this)

        Thread {
            for (clip in clips) {
                try {
                    if (settings.byMarkers && clip.markers.size >= 2) {
                        val fullClip = bm.loadClip(clip.dir!!)
                        val ts = java.util.TreeSet(fullClip.markers)
                        val arr = ts.toIntArray()
                        for (i in 0 until arr.size - 1) {
                            if (arr[i + 1] > arr[i]) {
                                val r = intArrayOf(arr[i], arr[i + 1])
                                val fileName = exportSingleRangeFromClip(
                                    treeUri, dir, fullClip, r, i + 1, settings
                                )
                                if (fileName != null) written.add(fileName) else failed[0]++
                            }
                        }
                    } else {
                        val fullClip = bm.loadClip(clip.dir!!)
                        val r = intArrayOf(0, fullClip.frameCount())
                        val fileName = exportSingleRangeFromClip(
                            treeUri, dir, fullClip, r, 1, settings
                        )
                        if (fileName != null) written.add(fileName) else failed[0]++
                    }
                } catch (e: Exception) {
                    android.util.Log.e("EXPORT", "Failed for ${clip.name}", e)
                    failed[0]++
                }
            }
            runOnUiThread { showExportResult(written.size, failed[0]) }
        }.start()
    }

    private fun buildRanges(byMarkers: Boolean): List<IntArray> {
        return ExportUtils.buildRanges(source!!, byMarkers, selStart, selEnd)
    }

    private fun exportSingleRangeFromClip(
        treeUri: Uri,
        dir: DocumentFile?,
        clip: AudioClip,
        r: IntArray,
        index: Int,
        s: ExportSettings
    ): String? {
        val rangePcm = AudioOps.trim(clip.pcm, r[0], r[1])

        var nameNoExt = buildName(s.nameTemplate ?: "{name}_{index}", stripExt(clip.name), index)
        if (nameNoExt.lowercase().endsWith(".wav")) {
            nameNoExt = nameNoExt.substring(0, nameNoExt.length - 4)
        }
        val fileName = getUniqueName(dir, nameNoExt, ".wav")

        val fileUri = createFile(treeUri, fileName) ?: return null

        val tmp = File.createTempFile("exp_", ".wav", cacheDir)
        try {
            // --- Передаем fadeMs и normalizeTarget ---
            WavWriter.write(tmp, rangePcm, clip.sampleRate, s.rate, s.bits, s.stereoMono,
                s.trueMono, s.normalize, s.fade, s.trimSilence, s.denoise,
                s.fadeMs, s.normalizeTarget)
            if (tmp.length() == 0L) {
                android.util.Log.w("EXPORT", "Пустой WAV для $fileName")
                deleteCreated(fileUri)
                return null
            }

            contentResolver.openFileDescriptor(fileUri, "w")?.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).use { out ->
                    FileInputStream(tmp).use { input ->
                        val buf = ByteArray(65536)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                        }
                    }
                }
            }
            return fileName
        } catch (e: Exception) {
            deleteCreated(fileUri)
            throw e
        } finally {
            tmp.delete()
        }
    }

    private fun showExportResult(written: Int, failed: Int) {
        var msg = "Экспортировано: $written"
        if (failed > 0) msg += " (ошибок: $failed)"
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        if (written > 0) finish()
    }

    private fun createFile(treeUri: Uri, name: String): Uri? {
        return try {
            val dir = DocumentFile.fromTreeUri(this, treeUri) ?: return null
            dir.createFile("audio/wav", name)?.uri
        } catch (e: Exception) {
            null
        }
    }

    /** D4: удалить только что созданный DocumentFile при ошибке. */
    private fun deleteCreated(fileUri: Uri) {
        try {
            DocumentFile.fromSingleUri(this, fileUri)?.delete()
        } catch (e: Exception) {
        }
    }

    private fun buildName(template: String, base: String, index: Int): String {
        val out = template.replace("{name}", base)
        .replace("{index}", String.format(Locale.US, "%03d", index))
        return sanitize(out)
    }

    private fun stripExt(s: String?): String {
        if (s == null) return "clip"
        val dot = s.lastIndexOf('.')
        return if (dot > 0) s.substring(0, dot) else s
    }

    private fun sanitize(s: String?): String = FileNames.sanitize(s)

    private fun getUniqueName(dir: DocumentFile?, baseName: String, ext: String): String {
        if (dir == null) return baseName + ext
        var name = baseName + ext
        var counter = 1
        while (dir.findFile(name) != null) {
            name = "${baseName}_$counter$ext"
            counter++
        }
        return name
    }

    private fun updateButtonHighlights() {
        val btnDef = findViewById<Button>(R.id.btnDefaults)
        val btnMeta = findViewById<Button>(R.id.btnOriginal)

        if (isDefaultMode) {
            btnDef.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFFFB300.toInt())
            btnDef.setTextColor(0xFF0E0E10.toInt())
        } else {
            btnDef.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF2A2A2E.toInt())
            btnDef.setTextColor(0xFFE8E8EA.toInt())
        }

        if (isOriginalMode) {
            btnMeta.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFFFB300.toInt())
            btnMeta.setTextColor(0xFF0E0E10.toInt())
        } else {
            btnMeta.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF2A2A2E.toInt())
            btnMeta.setTextColor(0xFFB0B0B0.toInt())
        }
    }
}
