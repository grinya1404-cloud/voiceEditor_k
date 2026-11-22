package com.grinya.voicesampleprep

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.grinya.voicesampleprep.audio.WavWriter
import com.grinya.voicesampleprep.edit.EditActivityHolder
import com.grinya.voicesampleprep.model.AudioClip
import com.grinya.voicesampleprep.model.AudioMeta
import com.grinya.voicesampleprep.model.Bank
import com.grinya.voicesampleprep.model.BankManager
import com.grinya.voicesampleprep.util.AudioOps
import com.grinya.voicesampleprep.util.ExportUtils
import java.io.File
import java.util.Locale

class ExportToBankActivity : AppCompatActivity() {

    companion object {
        private var forcedStart = -1
        private var forcedEnd = -1
        private var selStart = -1
        private var selEnd = -1

        @JvmStatic
        fun setForcedInterval(start: Int, end: Int) {
            forcedStart = start
            forcedEnd = end
        }

        @JvmStatic
        fun setSource(clip: AudioClip, start: Int, end: Int) {
            EditActivityHolder.current = clip
            selStart = start
            selEnd = end
        }
    }

    private lateinit var bankManager: BankManager
    private lateinit var clip: AudioClip
    private lateinit var rv: RecyclerView
    private lateinit var adapter: BankAdapter

    private var forcedStartLocal = -1
    private var forcedEndLocal = -1
    private var selStartLocal = -1
    private var selEndLocal = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_export_to_bank)

        val c = EditActivityHolder.current
        if (c == null) {
            finish()
            return
        }
        clip = c

        forcedStartLocal = forcedStart
        forcedEndLocal = forcedEnd
        forcedStart = -1
        forcedEnd = -1

        selStartLocal = selStart
        selEndLocal = selEnd
        selStart = -1
        selEnd = -1

        if (forcedStartLocal != -1) {
            findViewById<View>(R.id.rgMode).visibility = View.GONE
        } else {
            // Обновляем текст первой кнопки, если есть выделение
            val rbSingle = findViewById<RadioButton>(R.id.rbSingle)
            if (selStartLocal >= 0 && selEndLocal > selStartLocal) {
                val durationMs = (selEndLocal - selStartLocal) * 1000 / clip.sampleRate
                val m = durationMs / 60000
                val s = (durationMs % 60000) / 1000
                val r = durationMs % 1000
                val timeStr = String.format(Locale.US, "%02d:%02d.%03d", m, s, r)
                rbSingle.text = "Один файл (выделение $timeStr)"
            } else {
                rbSingle.text = "Один файл (весь клип)"
            }

            // --- Динамический текст для режима маркеров/интервалов ---
            val rbByMarkers = findViewById<RadioButton>(R.id.rbByMarkers)
            val intervalsCount = clip.exportIntervals.size
            val markersCount = clip.markers.size

            if (intervalsCount > 0) {
                rbByMarkers.text = "По интервалам ($intervalsCount)"
            } else if (markersCount >= 2) {
                rbByMarkers.text = "По маркерам ($markersCount)"
            } else {
                rbByMarkers.isEnabled = false
                rbByMarkers.text = "По маркерам (нужно ≥ 2)"
            }
        }

        bankManager = BankManager(this)
        rv = findViewById(R.id.rvBanks)
        adapter = BankAdapter()
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnNewBank).setOnClickListener { showCreateBankDialog() }

        reloadBanks()
    }

    private fun reloadBanks() {
        adapter.setData(bankManager.listBanks())
    }

    private fun showCreateBankDialog() {
        val input = EditText(this)
        input.hint = "Имя банка"

        AlertDialog.Builder(this).setTitle("Новый банк").setView(input)
        .setPositiveButton("Создать") { _, _ ->
            var name = input.text.toString().trim()
            if (name.isEmpty()) name = "bank"
            bankManager.createBank(name)
            reloadBanks()
        }.setNegativeButton("Отмена", null).show()
    }

    private fun exportToBank(targetBank: Bank) {
        val ranges: List<IntArray>

        if (forcedStartLocal != -1 && forcedEndLocal > forcedStartLocal) {
            ranges = listOf(intArrayOf(forcedStartLocal, forcedEndLocal))
        } else {
            val byMarkers = findViewById<RadioButton>(R.id.rbByMarkers).isChecked
            if (byMarkers) {
                ranges = ExportUtils.buildRanges(clip, true, -1, -1)
            } else {
                if (selStartLocal >= 0 && selEndLocal > selStartLocal) {
                    ranges = listOf(intArrayOf(selStartLocal, selEndLocal))
                } else {
                    ranges = listOf(intArrayOf(0, clip.frameCount()))
                }
            }
        }

        if (ranges.isEmpty()) {
            Toast.makeText(this, "Нечего экспортировать", Toast.LENGTH_SHORT).show()
            return
        }

        val saved = intArrayOf(0)
        val failed = intArrayOf(0)
        val oldMeta = if (clip.dir != null) AudioMeta.load(clip.dir) else null

        Thread {
            for ((idx, r) in ranges.withIndex()) {
                try {
                    val rangePcm = AudioOps.trim(clip.pcm, r[0], r[1])

                    // Если один файл (выделение или весь), имя без _001
                    val nameNoExt = if (ranges.size == 1) {
                        ExportUtils.stripExt(clip.name)
                    } else {
                        ExportUtils.stripExt(clip.name) + "_" + String.format(Locale.US, "%03d", idx + 1)
                    }

                    var outFile = File(targetBank.dir, "$nameNoExt.wav")
                    var counter = 1
                    while (outFile.exists()) {
                        outFile = File(targetBank.dir, "${nameNoExt}_$counter.wav")
                        counter++
                    }

                    WavWriter.write(outFile, rangePcm, clip.sampleRate, clip.sampleRate, 16,
                        false, false, false, false, false)

                    val meta = AudioMeta()
                    if (oldMeta != null && (oldMeta.sampleRate > 0 || oldMeta.channels > 0)) {
                        meta.sampleRate = oldMeta.sampleRate
                        meta.channels = oldMeta.channels
                        meta.bits = oldMeta.bits
                        meta.format = oldMeta.format
                    } else {
                        meta.sampleRate = clip.sampleRate
                        meta.channels = clip.channels
                        meta.bits = 16
                        meta.format = "wav"
                    }
                    AudioMeta.save(outFile, meta)

                    saved[0]++
                } catch (e: Exception) {
                    android.util.Log.e("EXPORT_BANK", "Error", e)
                    failed[0]++
                }
            }

            runOnUiThread {
                var msg = "Сохранено в банк: ${saved[0]}"
                if (failed[0] > 0) msg += " (ошибок: ${failed[0]})"
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                if (saved[0] > 0) finish()
            }
        }.start()
    }

    private inner class BankAdapter : RecyclerView.Adapter<BankAdapter.VH>() {
        private var data: List<Bank> = emptyList()

        fun setData(d: List<Bank>) {
            data = d
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_bank_picker, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, pos: Int) {
            val b = data[pos]
            h.name.text = b.name
            h.info.text = "${b.clips.size} клипов"
            h.itemView.setOnClickListener { exportToBank(b) }
        }

        override fun getItemCount(): Int = data.size

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.txtBankName)
            val info: TextView = v.findViewById(R.id.txtBankInfo)
        }
    }
}
