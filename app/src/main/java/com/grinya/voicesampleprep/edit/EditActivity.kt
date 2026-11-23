package com.grinya.voicesampleprep.edit

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import com.grinya.voicesampleprep.ExportActivity
import com.grinya.voicesampleprep.ExportToBankActivity
import com.grinya.voicesampleprep.R
import com.grinya.voicesampleprep.SettingsActivity
import com.grinya.voicesampleprep.audio.AudioDecoder
import com.grinya.voicesampleprep.audio.AudioPlayer
import com.grinya.voicesampleprep.model.AudioClip
import com.grinya.voicesampleprep.util.AudioInfoDialog
import com.grinya.voicesampleprep.util.AudioOps
import com.grinya.voicesampleprep.util.Denoiser
import java.util.Locale
import androidx.activity.result.contract.ActivityResultContracts

class EditActivity : AppCompatActivity(), AudioPlayer.Listener, WaveformView.Listener {

    companion object {
        private var forcedStart = -1
        private var forcedEnd = -1

        @JvmStatic
        fun setForcedInterval(start: Int, end: Int) {
            forcedStart = start
            forcedEnd = end
        }
    }

    private var clip: AudioClip? = null
    private var player: AudioPlayer? = null
    private var wave: WaveformView? = null
    private var isPaused = false

    private lateinit var btnPlay: ImageButton
    private lateinit var btnStop: ImageButton
    private lateinit var txtName: TextView
    private lateinit var txtTime: TextView
    private var isChanged = false

    private var isStereoMode = false
    private var isLeftEnabled = true
    private var isRightEnabled = true
    private val recentActions = ArrayList<String>()
    private var clipboard: FloatArray? = null
    private var pendingStartMarker = -1
    private var forcedStartLocal = -1
    private var forcedEndLocal = -1
    private val insertLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            handleInsertResult(result.data?.data)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    confirmExit()
                }
        })

        val c = EditActivityHolder.current
        if (c == null) {
            finish()
            return
        }
        clip = c

        wave = findViewById(R.id.wave)
        txtName = findViewById(R.id.txtName)
        txtTime = findViewById(R.id.txtTime)
        btnPlay = findViewById(R.id.btnPlay)
        btnStop = findViewById(R.id.btnStop)

        txtName.text = c.name

        player = AudioPlayer()
        player?.setListener(this)
        player?.setPcm(c.pcm)

        wave?.setListener(this)
        wave?.setData(c.pcm, c.sampleRate, c.markers)
        wave?.updateExportIntervals(c.exportIntervals)

        val prefs = getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE)
        isStereoMode = prefs.getBoolean(SettingsActivity.KEY_STEREO_WAVES, false)
        wave?.setStereoView(isStereoMode)
        wave?.setZeroCrossingSnap(prefs.getBoolean("zero_crossing", true))
        player?.setChannelMask(isLeftEnabled, isRightEnabled)

        wireToolbar()
        wireTransport()
        wireTools()
        updateDynamicButtons()
        // Инициализация видимости кнопок перелистывания
        updateIntervalPagingButtons(false)
    }

    private fun wireToolbar() {
        findViewById<View>(R.id.btnBack).setOnClickListener { confirmExit() }
        findViewById<View>(R.id.btnUndo).setOnClickListener {
            val c = clip ?: return@setOnClickListener
            if (c.canUndo()) {
                c.undo()
                refreshAfterChange()
                wave?.updateExportIntervals(c.exportIntervals)
            }
        }
        findViewById<View>(R.id.btnRedo).setOnClickListener {
            val c = clip ?: return@setOnClickListener
            if (c.canRedo()) {
                c.redo()
                refreshAfterChange()
                wave?.updateExportIntervals(c.exportIntervals)
            }
        }

        findViewById<View>(R.id.btnMenu).setOnClickListener { v ->
            val popup = PopupMenu(this, v)
            popup.menu.add(0, 1, 0, "Сохранить")
            popup.menu.add(0, 2, 0, "Инфо")
            popup.menu.add(0, 3, 0, "Экспорт")
            popup.menu.add(0, 4, 0, "Экспорт в банк")

            popup.setOnMenuItemClickListener { item ->
                val c = clip ?: return@setOnMenuItemClickListener false
                when (item.itemId) {
                    1 -> {
                        try {
                            c.save()
                            isChanged = false
                            Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                        true
                    }
                    2 -> {
                        val start = if (wave?.hasSelection() == true) wave!!.getSelStart() else -1
                        val end = if (wave?.hasSelection() == true) wave!!.getSelEnd() else -1
                        AudioInfoDialog.show(this, c, start, end)
                        true
                    }
                    3 -> {
                        val s = if (wave?.hasSelection() == true) wave!!.getSelStart() else -1
                        val e = if (wave?.hasSelection() == true) wave!!.getSelEnd() else -1
                        ExportActivity.setSource(c, s, e)
                        startActivity(Intent(this, ExportActivity::class.java))
                        true
                    }
                    4 -> {
                        val s = if (wave?.hasSelection() == true) wave!!.getSelStart() else -1
                        val e = if (wave?.hasSelection() == true) wave!!.getSelEnd() else -1
                        ExportToBankActivity.setSource(c, s, e)
                        startActivity(Intent(this, ExportToBankActivity::class.java))
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }
    }
    private fun updateDynamicButtons() {
        val btn1 = findViewById<TextView>(R.id.btnDynamic1)
        val btn2 = findViewById<TextView>(R.id.btnDynamic2)

        if (recentActions.isNotEmpty()) {
            val a1 = recentActions[0]
            btn1.text = a1
            btn1.visibility = View.VISIBLE
            btn1.setOnClickListener { executeActionByName(a1) }
        } else {
            btn1.visibility = View.GONE
            btn1.setOnClickListener(null)
        }

        if (recentActions.size > 1) {
            val a2 = recentActions[1]
            btn2.text = a2
            btn2.visibility = View.VISIBLE
            btn2.setOnClickListener { executeActionByName(a2) }
        } else {
            btn2.visibility = View.GONE
            btn2.setOnClickListener(null)
        }
    }

    private fun executeActionByName(actionName: String) {
        val c = clip ?: return
        val w = wave ?: return
        when (actionName) {
            "Insert" -> {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/wav", "audio/x-wav",
                            "audio/mpeg", "audio/mp4", "audio/aac", "audio/flac", "audio/x-flac",
                    "audio/*"))
                }
                insertLauncher.launch(i)
            }
            "Silence" -> {
                if (!requireSelection()) return
                c.silence(w.getSelStart(), w.getSelEnd(), applyL(), applyR())
                refreshAfterChange()
            }
            "Gain" -> showGainDialog()
            "Normalize" -> showNormalizeDialog()
            "Fade In" -> showFadeDialog(true)
            "Fade Out" -> showFadeDialog(false)
            "Reverse" -> {
                if (w.hasSelection()) c.reverseRange(w.getSelStart(), w.getSelEnd(), applyL(), applyR())
                else c.reverse(applyL(), applyR())
                refreshAfterChange()
            }
            "Invert" -> {
                if (w.hasSelection()) c.invertPhase(w.getSelStart(), w.getSelEnd(), applyL(), applyR())
                else c.invertPhase(0, c.frameCount(), applyL(), applyR())
                refreshAfterChange()
            }
            "To Mono" -> {
                if (w.hasSelection()) c.mixToMonoRange(w.getSelStart(), w.getSelEnd())
                else c.mixToMonoRange(0, c.frameCount())
                refreshAfterChange()
            }
            "Auto-Trim" -> showAutoTrimDialog()
            "High-Pass" -> showHighPassDialog()
            "Denoise" -> runDenoise()
            "Channels" -> runChannels()
            "VAD Gate" -> runVadGate()
            "De-esser" -> runDeEsser()
            "Compressor" -> runCompressor()
        }
    }

    private fun runDenoise() {
        val c = clip ?: return
        if (c.pcm.isEmpty()) return
        val w = wave ?: return
        val start = if (w.hasSelection()) w.getSelStart() else 0
        val end = if (w.hasSelection()) w.getSelEnd() else c.frameCount()
        val range = AudioOps.trim(c.pcm, start, end)
        Toast.makeText(this, "Обработка Denoise...", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val denoised = Denoiser.denoiseStereo(range)
                runOnUiThread {
                    c.replaceRangePcm(denoised, start, end)
                    refreshAfterChange()
                    Toast.makeText(this, "Denoise готов", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "Ошибка Denoise: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun runChannels() {
        val c = clip ?: return
        val w = wave ?: return
        val l = applyL()
        val r = applyR()
        if (l && r) {
            if (w.hasSelection()) c.swapChannels(w.getSelStart(), w.getSelEnd())
            else c.swapChannels(0, c.frameCount())
            Toast.makeText(this, "L ↔ R", Toast.LENGTH_SHORT).show()
        } else if (l) {
            if (w.hasSelection()) c.copyRtoL(w.getSelStart(), w.getSelEnd())
            else c.copyRtoL(0, c.frameCount())
            Toast.makeText(this, "R → L", Toast.LENGTH_SHORT).show()
        } else if (r) {
            if (w.hasSelection()) c.copyLtoR(w.getSelStart(), w.getSelEnd())
            else c.copyLtoR(0, c.frameCount())
            Toast.makeText(this, "L → R", Toast.LENGTH_SHORT).show()
        }
        refreshAfterChange()
    }

    private fun runVadGate() {
        val c = clip ?: return
        if (c.pcm.isEmpty()) return
        val w = wave ?: return
        val start = if (w.hasSelection()) w.getSelStart() else 0
        val end = if (w.hasSelection()) w.getSelEnd() else c.frameCount()
        val range = AudioOps.trim(c.pcm, start, end)
        Thread {
            try {
                val out = AudioOps.vadGate(range, c.sampleRate, -40f, 100)
                runOnUiThread {
                    c.replaceRangePcm(out, start, end)
                    refreshAfterChange()
                    Toast.makeText(this, "VAD Gate готов", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "Ошибка VAD: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun runDeEsser() {
        val c = clip ?: return
        if (c.pcm.isEmpty()) return
        val w = wave ?: return
        val start = if (w.hasSelection()) w.getSelStart() else 0
        val end = if (w.hasSelection()) w.getSelEnd() else c.frameCount()
        val range = AudioOps.trim(c.pcm, start, end)
        Thread {
            try {
                val out = AudioOps.deEss(range, c.sampleRate, -30f, 6f)
                runOnUiThread {
                    c.replaceRangePcm(out, start, end)
                    refreshAfterChange()
                    Toast.makeText(this, "De-esser готов", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "Ошибка De-esser: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun runCompressor() {
        val c = clip ?: return
        if (c.pcm.isEmpty()) return
        val w = wave ?: return
        val start = if (w.hasSelection()) w.getSelStart() else 0
        val end = if (w.hasSelection()) w.getSelEnd() else c.frameCount()
        val range = AudioOps.trim(c.pcm, start, end)
        Thread {
            try {
                val out = AudioOps.compress(range, c.sampleRate, -18f, 3f, 10f, 100f, 2f)
                runOnUiThread {
                    c.replaceRangePcm(out, start, end)
                    refreshAfterChange()
                    Toast.makeText(this, "Компрессор готов", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "Ошибка компрессора: ${e.message}", Toast.LENGTH_LONG)
                    .show()
                }
            }
        }.start()
    }

    private fun wireTransport() {
        val p = player ?: return
        val w = wave ?: return
        findViewById<View>(R.id.btnPlay).setOnClickListener {
            if (p.isPlaying()) {
                p.pause()
                isPaused = true
            } else {
                val startFrame = if (isPaused) w.getActivePlayFrame() else w.getPlayheadFrame()
                p.seekToFrame(startFrame)
                p.play()
                isPaused = false
            }
        }

        findViewById<View>(R.id.btnStop).setOnClickListener {
            p.stop()
            isPaused = false
            val ph = w.getPlayheadFrame()
            w.snapActivePlayheadFrame(ph)
            w.setPlayheadFrame(ph)
            updateTimeLabel()
        }

        findViewById<View>(R.id.btnZoomIn).setOnClickListener { w.zoomInAroundCenter(2.0) }
        findViewById<View>(R.id.btnZoomOut).setOnClickListener { w.zoomInAroundCenter(0.5) }
    }

    private fun wireTools() {
        val c = clip ?: return
        val w = wave ?: return

        findViewById<View>(R.id.btnTrim).setOnClickListener {
            if (!requireSelection()) return@setOnClickListener
            c.trim(w.getSelStart(), w.getSelEnd())
            w.clearSelection()
            refreshAfterChange()
        }

        findViewById<View>(R.id.btnCut).setOnClickListener {
            if (!requireSelection()) return@setOnClickListener
            val s = w.getSelStart()
            val e = w.getSelEnd()
            clipboard = AudioOps.trim(c.pcm, s, e)
            c.delete(s, e)
            w.clearSelection()
            refreshAfterChange()
            Toast.makeText(this, "Вырезано", Toast.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.btnCopy).setOnClickListener {
            if (!requireSelection()) return@setOnClickListener
            clipboard = AudioOps.trim(c.pcm, w.getSelStart(), w.getSelEnd())
            Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.btnPaste).setOnClickListener {
            val buf = clipboard
            if (buf == null || buf.isEmpty()) {
                Toast.makeText(this, "Буфер пуст", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val start = player?.getCurrentFrame() ?: 0
            val selStart = if (w.hasSelection()) w.getSelStart() else start
            val selEnd = if (w.hasSelection()) w.getSelEnd() else start
            c.insert(buf, start, selStart, selEnd)
            w.clearSelection()
            refreshAfterChange()
            Toast.makeText(this, "Вставлено", Toast.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.btnMore).setOnClickListener { v ->
            val popup = PopupMenu(this, v)
            popup.menu.add(0, 1, 0, "Denoise")
            popup.menu.add(0, 2, 0, "Auto-Trim")
            popup.menu.add(0, 3, 0, "Channels")
            popup.menu.add(0, 4, 0, "Insert")
            popup.menu.add(0, 5, 0, "Silence")
            popup.menu.add(0, 6, 0, "Gain")
            popup.menu.add(0, 7, 0, "Normalize")
            popup.menu.add(0, 8, 0, "Fade In")
            popup.menu.add(0, 9, 0, "Fade Out")
            popup.menu.add(0, 10, 0, "Reverse")
            popup.menu.add(0, 11, 0, "Invert")
            popup.menu.add(0, 12, 0, "To Mono")
            popup.menu.add(0, 13, 0, "High-Pass")
            popup.menu.add(0, 14, 0, "VAD Gate")
            popup.menu.add(0, 15, 0, "De-esser")
            popup.menu.add(0, 16, 0, "Compressor")

            popup.setOnMenuItemClickListener { item ->
                val actionName = item.title.toString()
                if (!recentActions.contains(actionName)) {
                    recentActions.add(0, actionName)
                    if (recentActions.size > 2) recentActions.removeAt(2)
                    updateDynamicButtons()
                }
                executeActionByName(actionName)
                true
            }
            popup.show()
        }

        findViewById<View>(R.id.btnMarker).setOnClickListener {
            if (w.hasSelection()) {
                c.addMarker(w.getSelStart())
                c.addMarker(w.getSelEnd())
                w.clearSelection() // B12
            } else {
                var f = player?.getCurrentFrame() ?: 0
                if (w.isZeroCrossingSnap() && c.pcm.isNotEmpty()) {
                    f = AudioOps.nearestZeroCrossing(c.pcm, f)
                }
                c.addMarker(f)
            }
            w.updateMarkers(c.markers)
            w.updateExportIntervals(c.exportIntervals)
            isChanged = true
        }

        findViewById<View>(R.id.btnMarker).setOnLongClickListener {
            showMarkerList()
            true
        }

        // B13: кнопки перелистывания интервалов
        findViewById<View>(R.id.btnIntervalUp).setOnClickListener { w.nextIntervalPage() }
        findViewById<View>(R.id.btnIntervalDown).setOnClickListener { w.prevIntervalPage() }
    }
    private fun showGainDialog() {
        val c = clip ?: return
        val w = wave ?: return
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL
        input.setText("0")

        AlertDialog.Builder(this).setTitle(R.string.gain_dialog_title).setView(input)
        .setPositiveButton(R.string.ok) { _, _ ->
            val dB = input.text.toString().trim().toFloatOrNull() ?: return@setPositiveButton
            if (w.hasSelection()) {
                c.gainRange(w.getSelStart(), w.getSelEnd(), dB, applyL(), applyR())
            } else {
                c.gainRange(0, c.frameCount(), dB, applyL(), applyR())
            }
            refreshAfterChange()
        }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun showNormalizeDialog() {
        val c = clip ?: return
        val w = wave ?: return
        val prefs = getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE)
        val defaultTarget = prefs.getFloat(SettingsActivity.KEY_NORMALIZE_TARGET, 0.9f)

        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        input.setText(defaultTarget.toString())
        val useSel = w.hasSelection()
        val title = getString(R.string.normalize_dialog_title) +
        if (useSel) " (к выделению)" else " (весь файл)"
        AlertDialog.Builder(this).setTitle(title)
        .setView(input).setPositiveButton(R.string.ok) { _, _ ->
            var target = input.text.toString().trim().toFloatOrNull()
            if (target != null) {
                if (target <= 0f || target > 1f) target = 0.9f
                if (useSel) {
                    c.normalizeRangePeak(w.getSelStart(), w.getSelEnd(), target, applyL(), applyR())
                } else {
                    c.normalizePeak(target, applyL(), applyR())
                }
                refreshAfterChange()
            }
        }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun showFadeDialog(fadeIn: Boolean) {
        val c = clip ?: return
        val w = wave ?: return
        val prefs = getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE)
        val defaultMs = prefs.getInt(SettingsActivity.KEY_FADE_MS, 5)

        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.setText(defaultMs.toString())
        val useSel = w.hasSelection()
        val title = getString(R.string.fade_dialog_title) +
        if (useSel) " (к выделению)" else " (весь файл)"
        AlertDialog.Builder(this).setTitle(title)
        .setView(input).setPositiveButton(R.string.ok) { _, _ ->
            val ms = input.text.toString().trim().toIntOrNull()
            if (ms != null) {
                val frames = Math.max(1, ms * c.sampleRate / 1000)
                if (useSel) {
                    if (fadeIn) c.fadeInRange(w.getSelStart(), w.getSelEnd(), frames, applyL(),
                        applyR())
                    else c.fadeOutRange(w.getSelStart(), w.getSelEnd(), frames, applyL(), applyR())
                } else {
                    if (fadeIn) c.fadeIn(frames, applyL(), applyR())
                    else c.fadeOut(frames, applyL(), applyR())
                }
                refreshAfterChange()
            }
        }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun showAutoTrimDialog() {
        val c = clip ?: return
        val w = wave ?: return
        val prefs = getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE)
        val defaultThreshold = prefs.getFloat(SettingsActivity.KEY_AUTO_TRIM_THRESHOLD, 0.001f)

        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        input.setText(defaultThreshold.toString())
        input.hint = "Порог амплитуды (0..1)"

        AlertDialog.Builder(this).setTitle("Auto-Trim (порог тишины)")
        .setMessage("Например: 0.001 (тихо) ... 0.01 (громче)").setView(input)
        .setPositiveButton("OK") { _, _ ->
            val threshold = input.text.toString().trim().toFloatOrNull()
            ?: return@setPositiveButton
            if (threshold <= 0 || threshold >= 1) {
                Toast.makeText(this, "Порог должен быть от 0 до 1", Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }
            if (w.hasSelection()) c.autoTrim(w.getSelStart(), w.getSelEnd(), threshold,
                applyL(), applyR())
            else c.autoTrim(0, c.frameCount(), threshold, applyL(), applyR())
            w.clearSelection()
            refreshAfterChange()
            Toast.makeText(this, "Тишина обрезана", Toast.LENGTH_SHORT).show()
        }.setNegativeButton("Отмена", null).show()
    }

    private fun showHighPassDialog() {
        val c = clip ?: return
        val w = wave ?: return
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        input.setText("80")
        input.hint = "Частота среза (Гц)"

        AlertDialog.Builder(this).setTitle("High-Pass Filter (Гц)").setView(input)
        .setPositiveButton("OK") { _, _ ->
            val cutoff = input.text.toString().trim().toFloatOrNull() ?: return@setPositiveButton
            if (cutoff <= 0 || cutoff >= 20000) {
                Toast.makeText(this, "Некорректная частота", Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }
            if (w.hasSelection()) c.highPass(w.getSelStart(), w.getSelEnd(), cutoff, applyL(),
                applyR())
            else c.highPass(0, c.frameCount(), cutoff, applyL(), applyR())
            refreshAfterChange()
            Toast.makeText(this, "High-Pass применён", Toast.LENGTH_SHORT).show()
        }.setNegativeButton("Отмена", null).show()
    }

    private fun showMarkerList() {
        val c = clip ?: return
        val w = wave ?: return
        if (c.markers.isEmpty()) {
            Toast.makeText(this, "Маркеров нет", Toast.LENGTH_SHORT).show()
            return
        }
        val items = c.markers.map { formatMs(it * 1000 / c.sampleRate) }
        AlertDialog.Builder(this).setTitle("Маркеры (${c.markers.size})")
        .setItems(items.toTypedArray()) { _, which ->
            val frame = c.markers[which]
            player?.seekToFrame(frame)
            w.setPlayheadFrame(frame)
            updateTimeLabel()
        }.setNegativeButton("Удалить все") { _, _ ->
            c.clearMarkers()
            c.clearExportIntervals()
            w.updateMarkers(c.markers)
            w.updateExportIntervals(c.exportIntervals)
            w.setIntervalsEditMode(false) // <-- Выходим из режима
            updateIntervalPagingButtons(false) // <-- Обновляем кнопки
            isChanged = true
        }.setNeutralButton("Закрыть", null).show()
    }

    private fun requireSelection(): Boolean {
        if (wave?.hasSelection() != true) {
            Toast.makeText(this, R.string.no_selection, Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun refreshAfterChange() {
        val c = clip ?: return
        isChanged = true
        val p = player ?: return
        val w = wave ?: return

        val prevFrame = p.getCurrentFrame()
        if (p.isPlaying()) p.pause()

        p.setPcm(c.pcm)
        w.updatePcm(c.pcm)

        val f = Math.max(0, Math.min(prevFrame, c.frameCount()))
        p.seekToFrame(f)
        w.setPlayheadFrame(f)
        updateTimeLabel()
        w.updateMarkers(c.markers)
        w.updateExportIntervals(c.exportIntervals)
    }

    private fun updateTimeLabel() {
        val c = clip ?: return
        val p = player ?: return
        val curMs = p.getCurrentFrame() * 1000 / c.sampleRate
        val totalMs = c.frameCount() * 1000 / c.sampleRate
        txtTime.text = "${formatMs(curMs)} / ${formatMs(totalMs)}"
    }

    private fun formatMs(ms: Int): String {
        val m = ms / 60000
        val s = (ms % 60000) / 1000
        val r = ms % 1000
        return String.format(Locale.US, "%02d:%02d.%03d", m, s, r)
    }

    private fun applyL(): Boolean = !isStereoMode || isLeftEnabled
    private fun applyR(): Boolean = !isStereoMode || isRightEnabled

    // ---------- AudioPlayer.Listener ----------

    override fun onPosition(positionMs: Int) {
        wave?.setActivePlayheadFrame(player?.getCurrentFrame() ?: 0)
        updateTimeLabel()
    }

    override fun onEnded() {
        isPaused = false
        val c = clip ?: return
        player?.setPlayRange(0, c.frameCount())
        wave?.setActivePlayheadFrame(c.frameCount())
        updateTimeLabel()
    }

    override fun onStateChanged(playing: Boolean) {
        btnPlay.setImageResource(
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
    }

    // ---------- WaveformView.Listener ----------

    override fun onSelectionChanged(startFrame: Int, endFrame: Int) {
    }

    override fun onTap(frame: Int) {
        val c = clip ?: return
        val w = wave ?: return
        var snapped = frame
        if (w.isZeroCrossingSnap() && c.pcm.isNotEmpty()) {
            snapped = AudioOps.nearestZeroCrossing(c.pcm, frame)
        }
        player?.seekToFrame(snapped)
        w.setPlayheadFrame(snapped)
        w.snapActivePlayheadFrame(snapped)
        updateTimeLabel()
    }

    override fun onPlayheadDragged(frame: Int) {
        player?.seekToFrame(frame)
        wave?.setActivePlayheadFrame(frame)
        updateTimeLabel()
    }

    override fun onMarkerTapped(frame: Int) {
        player?.seekToFrame(frame)
        wave?.setPlayheadFrame(frame)
        updateTimeLabel()
    }

    override fun onMarkerMoved(oldFrame: Int, newFrame: Int) {
        val c = clip ?: return
        if (c.moveMarker(oldFrame, newFrame)) {
            refreshAfterChange()
            updateIntervalPagingButtons() // <-- Обновляем кнопки
            Toast.makeText(this, "Маркер перемещён", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Не удалось переместить (занято?)", Toast.LENGTH_SHORT).show()
            refreshAfterChange()
        }
    }

    override fun onMarkerLongPressed(frame: Int) {
        val c = clip ?: return
        val w = wave ?: return

        if (pendingStartMarker == frame) {
            pendingStartMarker = -1
            Toast.makeText(this, "Соединение отменено", Toast.LENGTH_SHORT).show()
            return
        }

        if (pendingStartMarker != -1) {
            val start = Math.min(pendingStartMarker, frame)
            val end = Math.max(pendingStartMarker, frame)
            val exists = c.exportIntervals.any { it[0] == start && it[1] == end }
            if (exists) {
                Toast.makeText(this, "Этот интервал уже существует", Toast.LENGTH_SHORT).show()
            } else if (c.addExportInterval(start, end)) {
                Toast.makeText(this, "Интервал создан", Toast.LENGTH_SHORT).show()
                w.updateExportIntervals(c.exportIntervals)
                updateIntervalPagingButtons() // <-- Обновляем кнопки
                isChanged = true
            } else {
                Toast.makeText(this, "Не удалось создать интервал", Toast.LENGTH_SHORT).show()
            }
            pendingStartMarker = -1
            return
        }

        val time = formatMs(frame * 1000 / c.sampleRate)
        val hasInterval = c.exportIntervals.any { it[0] == frame || it[1] == frame }

        val options = ArrayList<String>()
        options.add("Переместить")
        options.add("Соединить")
        if (hasInterval) options.add("Отменить соединение")
        options.add("Удалить маркер")

        AlertDialog.Builder(this).setTitle("Маркер @$time")
        .setItems(options.toTypedArray()) { _, which ->
            when (options[which]) {
                "Переместить" -> {
                    w.startMarkerDrag(frame)
                    Toast.makeText(this, "Ткните в новое место", Toast.LENGTH_SHORT).show()
                }
                "Соединить" -> {
                    pendingStartMarker = frame
                    Toast.makeText(this, "Выберите второй маркер (долгий тап)",
                        Toast.LENGTH_SHORT).show()
                }
                "Отменить соединение" -> {
                    c.removeExportIntervalsWithMarker(frame)
                    w.updateExportIntervals(c.exportIntervals)
                    updateIntervalPagingButtons() // <-- Обновляем кнопки
                    isChanged = true
                    Toast.makeText(this, "Соединение отменено", Toast.LENGTH_SHORT).show()
                }
                "Удалить маркер" -> {
                    c.removeExportIntervalsWithMarker(frame)
                    c.removeMarker(frame)
                    w.updateMarkers(c.markers)
                    w.updateExportIntervals(c.exportIntervals)
                    updateIntervalPagingButtons() // <-- Обновляем кнопки
                    isChanged = true
                }
            }
        }.setNegativeButton("Отмена", null).show()
    }

    override fun onChannelToggled(left: Boolean, right: Boolean) {
        isLeftEnabled = left
        isRightEnabled = right
        player?.setChannelMask(left, right)
        if (player?.isPlaying() == true) {
            player?.pause()
            btnPlay.isEnabled = true
        }
    }

    override fun onIntervalLongPressed(startFrame: Int, endFrame: Int) {
        val c = clip ?: return
        val w = wave ?: return
        val timeStart = formatMs(startFrame * 1000 / c.sampleRate)
        val timeEnd = formatMs(endFrame * 1000 / c.sampleRate)

        AlertDialog.Builder(this).setTitle("Интервал $timeStart – $timeEnd")
        .setItems(arrayOf("Проиграть", "Удалить", "Экспортировать", "В банк", "Инфо"))
        { _, which ->
            when (which) {
                0 -> {
                    player?.setPlayRange(startFrame, endFrame)
                    player?.seekToFrame(startFrame)
                    player?.play()
                }
                1 -> {
                    c.removeExportInterval(startFrame, endFrame)
                    w.updateExportIntervals(c.exportIntervals)
                    isChanged = true
                    updateIntervalPagingButtons() // <-- Обновляем кнопки

                    // Если интервалов не осталось — выходим из режима
                    if (c.exportIntervals.isEmpty()) {
                        w.setIntervalsEditMode(false)
                        updateIntervalPagingButtons(false)
                    }
                }
                2 -> {
                    ExportActivity.setForcedInterval(startFrame, endFrame)
                    ExportActivity.setSource(c, -1, -1)
                    startActivity(Intent(this, ExportActivity::class.java))
                }
                3 -> {
                    ExportToBankActivity.setForcedInterval(startFrame, endFrame)
                    EditActivityHolder.current = c
                    startActivity(Intent(this, ExportToBankActivity::class.java))
                }
                4 -> AudioInfoDialog.showInterval(this, c, startFrame, endFrame)
            }
        }.setNegativeButton("Отмена", null).show()
    }

    override fun onIntervalsModeChanged(enabled: Boolean) {
        updateIntervalPagingButtons(enabled)
    }

    private fun updateIntervalPagingButtons(forceMode: Boolean? = null) {
        val w = wave ?: return
        val isMode = forceMode ?: w.isIntervalsEditMode()
        val count = w.getIntervalCount()
        val pageSize = w.getIntervalPageSize()

        // Показываем кнопки только в режиме интервалов И если их больше одной страницы
        val show = isMode && count > pageSize
        val vis = if (show) View.VISIBLE else View.GONE

        findViewById<View>(R.id.btnIntervalUp).visibility = vis
        findViewById<View>(R.id.btnIntervalDown).visibility = vis
    }

    override fun onIntervalTapped(startFrame: Int, endFrame: Int) {
        val w = wave ?: return
        if (!w.isIntervalsEditMode()) w.setIntervalsEditMode(true)
    }

    override fun onPause() {
        super.onPause()
        if (player?.isPlaying() == true) player?.pause()
        pendingStartMarker = -1
        wave?.cancelMarkerDrag()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        pendingStartMarker = -1
        if (EditActivityHolder.current === clip) {
            EditActivityHolder.clear()
        }
    }
    private fun handleInsertResult(uri: Uri?) {
        uri ?: return
        Thread {
            try {
                val tmp = java.io.File(cacheDir, "insert_tmp")
                contentResolver.openInputStream(uri)?.use { input ->
                    java.io.FileOutputStream(tmp).use { output ->
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                        }
                    }
                }
                val res = AudioDecoder.decode(tmp)
                tmp.delete()

                val newPcm = res.pcm
                val atFrame = player?.getCurrentFrame() ?: 0
                val selStart = wave?.getSelStart() ?: 0
                val selEnd = wave?.getSelEnd() ?: 0

                runOnUiThread {
                    val c = clip ?: return@runOnUiThread
                    val w = wave ?: return@runOnUiThread
                    c.insert(newPcm, atFrame, selStart, selEnd)
                    w.clearSelection()
                    refreshAfterChange()
                    Toast.makeText(this, "Вставлено", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Ошибка вставки: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun confirmExit() {
        val c = clip ?: run { finish(); return }
        if (isChanged) {
            AlertDialog.Builder(this).setTitle("Выйти без сохранения?")
            .setMessage("В файле есть несохранённые изменения.")
            .setPositiveButton("Сохранить") { _, _ ->
                try {
                    c.save()
                    isChanged = false
                    finish()
                } catch (e: Exception) {
                    Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }.setNegativeButton("Выйти без сохранения") { _, _ -> finish() }
            .setNeutralButton("Отмена", null).show()
        } else {
            finish()
        }
    }
}
