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

        @JvmStatic
        fun setForcedInterval(start: Int, end: Int) {
            forcedStart = start
            forcedEnd = end
        }
    }

    private lateinit var bankManager: BankManager
    private lateinit var clip: AudioClip
    private lateinit var rv: RecyclerView
    private lateinit var adapter: BankAdapter

    private var forcedStartLocal = -1
    private var forcedEndLocal = -1

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

        if (forcedStartLocal != -1) {
            findViewById<View>(R.id.rgMode).visibility = View.GONE
        }

        bankManager = BankManager(this)
        rv = findViewById(R.id.rvBanks)
        adapter = BankAdapter()
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnNewBank).setOnClickListener { showCreateBankDialog() }

        if (clip.markers.size < 2) {
            findViewById<RadioButton>(R.id.rbByMarkers).apply {
                isEnabled = false
                text = "По маркерам (нужно ≥ 2 маркера)"
            }
        }

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
        var tempRanges: List<IntArray>

        if (forcedStartLocal != -1 && forcedEndLocal > forcedStartLocal) {
            tempRanges = listOf(intArrayOf(forcedStartLocal, forcedEndLocal))
        } else {
            val byMarkers = findViewById<RadioButton>(R.id.rbByMarkers).isChecked
            tempRanges = ExportUtils.buildRanges(clip, byMarkers, -1, -1)
        }

        if (tempRanges.isEmpty()) {
            Toast.makeText(this, "Нечего экспортировать", Toast.LENGTH_SHORT).show()
            return
        }

        val ranges = tempRanges
        val saved = intArrayOf(0)
        val failed = intArrayOf(0)

        // D7: исходные параметры — один раз до цикла
        val oldMeta = if (clip.dir != null) AudioMeta.load(clip.dir) else null

        Thread {
            for ((idx, r) in ranges.withIndex()) {
                try {
                    val rangePcm = AudioOps.trim(clip.pcm, r[0], r[1])

                    val baseName = ExportUtils.stripExt(clip.name)
                    val nameNoExt = baseName + "_" + String.format(Locale.US, "%03d", idx + 1)

                    var outFile = File(targetBank.dir, "$nameNoExt.wav")
                    var counter = 1
                    while (outFile.exists()) {
                        outFile = File(targetBank.dir, "${nameNoExt}_$counter.wav")
                        counter++
                    }

                    WavWriter.write(outFile, rangePcm, clip.sampleRate, clip.sampleRate, 16,
                        false, false, false, false, false)

                    // D7: .meta с параметрами ИСХОДНИКА
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

    // ---------- adapter ----------

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