package com.grinya.voicesampleprep

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.grinya.voicesampleprep.audio.Recorder
import com.grinya.voicesampleprep.model.AudioClip
import com.grinya.voicesampleprep.model.AudioMeta
import com.grinya.voicesampleprep.model.Bank
import com.grinya.voicesampleprep.model.BankManager
import java.util.Locale

class RecordActivity : AppCompatActivity(), Recorder.Listener {

    companion object {
        @JvmStatic
        var currentBank: Bank? = null
    }

    private var recorder: Recorder? = null
    private lateinit var bankManager: BankManager
    private lateinit var btnRec: Button
    private lateinit var btnDone: Button
    private lateinit var txtTime: TextView
    private lateinit var levelBar: ProgressBar

    private var recordedPcm: FloatArray? = null
    private var recStartTime: Long = 0
    private val timer = Handler(Looper.getMainLooper())
    private val timerTick = object : Runnable {
        override fun run() {
            val r = recorder
            if (r != null && r.isRecording()) {
                val elapsed = System.currentTimeMillis() - recStartTime
                txtTime.text = formatMs(elapsed.toInt())
                timer.postDelayed(this, 50)
            }
        }
    }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startRecording()
        } else {
            Toast.makeText(this, "Нужен доступ к микрофону", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_record)

        if (currentBank == null) {
            finish()
            return
        }
        bankManager = BankManager(this)

        btnRec = findViewById(R.id.btnRec)
        btnDone = findViewById(R.id.btnDone)
        txtTime = findViewById(R.id.txtTime)
        levelBar = findViewById(R.id.levelBar)

        val cbNS = findViewById<CheckBox>(R.id.cbNoiseSuppression)
        cbNS.isChecked = true
        cbNS.setOnCheckedChangeListener { _, checked ->
            recorder?.setNoiseSuppressionEnabled(checked)
        }

        recorder = Recorder()
        recorder?.setListener(this)

        btnRec.setOnClickListener {
            val r = recorder
            if (r != null && r.isRecording()) stopRecording() else ensurePermissionAndStart()
        }
        btnDone.setOnClickListener { saveAndFinish() }
    }

    private fun ensurePermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startRecording()
    }

    private fun startRecording() {
        btnRec.text = "■ STOP"
        btnRec.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
        btnDone.visibility = View.GONE
        recordedPcm = null
        recStartTime = System.currentTimeMillis()
        timer.post(timerTick)
        recorder?.start()
    }

    private fun stopRecording() {
        recorder?.stop()
        timer.removeCallbacks(timerTick)
        btnRec.text = "● REC"
        btnRec.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFFF3B30.toInt())
    }

    private fun saveAndFinish() {
        val pcm = recordedPcm
        if (pcm == null || pcm.isEmpty()) {
            finish()
            return
        }
        try {
            val clip = AudioClip("rec", pcm, 48000)
            val saved = bankManager.saveClipToBank(currentBank!!, clip, "rec")

            val meta = AudioMeta()
            meta.sampleRate = 48000
            meta.channels = 2
            meta.bits = 16
            meta.format = "wav"
            AudioMeta.save(saved, meta)

            Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- Recorder.Listener ----------

    override fun onLevel(rms: Float) {
        val p = Math.min(1000, (rms * 5000).toInt())
        levelBar.progress = p
    }

    override fun onFinished(pcm: FloatArray, elapsedMs: Int) {
        recordedPcm = pcm
        txtTime.text = formatMs(elapsedMs)
        levelBar.progress = 0
        btnDone.visibility = View.VISIBLE
    }

    override fun onError(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        btnRec.text = "● REC"
    }

    override fun onDestroy() {
        super.onDestroy()
        recorder?.stop()
        recorder?.release()
        timer.removeCallbacks(timerTick)
    }

    private fun formatMs(ms: Int): String {
        val m = ms / 60000
        val s = (ms % 60000) / 1000
        val r = ms % 1000
        return String.format(Locale.US, "%02d:%02d.%03d", m, s, r)
    }
}
