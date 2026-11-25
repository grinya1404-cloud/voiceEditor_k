package com.grinya.voicesampleprep

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.grinya.voicesampleprep.edit.EditActivity
import com.grinya.voicesampleprep.edit.EditActivityHolder
import com.grinya.voicesampleprep.model.AudioClip
import com.grinya.voicesampleprep.model.Bank
import com.grinya.voicesampleprep.model.BankManager
import com.grinya.voicesampleprep.util.AudioInfoDialog
import com.grinya.voicesampleprep.util.FileNames
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import com.grinya.voicesampleprep.edit.MultitrackActivity
import com.grinya.voicesampleprep.model.ProjectManager

class BankActivity : AppCompatActivity() {

    companion object {
        @JvmStatic
        var currentBank: Bank? = null
    }

    private lateinit var bankManager: BankManager
    private lateinit var rv: RecyclerView
    private lateinit var txtEmpty: TextView
    private lateinit var adapter: ClipAdapter

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            importFromUri(result.data?.data)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bank)

        // E11: восстановить currentBank из Intent extra
        bankManager = BankManager(this)
        val bankName = intent.getStringExtra("bankName")
        if (bankName != null) {
            for (b in bankManager.listBanks()) {
                if (b.name == bankName) {
                    currentBank = b
                    break
                }
            }
        }

        val bank = currentBank
        if (bank == null) {
            finish()
            return
        }

        rv = findViewById(R.id.rvClips)
        txtEmpty = findViewById(R.id.txtEmpty)
        adapter = ClipAdapter()
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        findViewById<TextView>(R.id.txtBankTitle).text = bank.name
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<View>(R.id.fabRecord).setOnClickListener {
            RecordActivity.currentBank = bank
            startActivity(Intent(this, RecordActivity::class.java))
        }

        findViewById<View>(R.id.btnExportBank).setOnClickListener {
            val cb = currentBank
            if (cb == null || cb.clips.isEmpty()) {
                Toast.makeText(this, "Банк пуст", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ExportActivity.setBankSource(cb.clips, cb.name)
            startActivity(Intent(this, ExportActivity::class.java))
        }

        findViewById<View>(R.id.fabImport).setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                        "audio/wav", "audio/x-wav", "audio/mpeg", "audio/mp4",
                        "audio/aac", "audio/flac", "audio/x-flac", "audio/*"
                ))
            }
            importLauncher.launch(i)
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    /** E7: если банк удалён/переименован — finish(). */
    private fun reload() {
        val name = currentBank?.name ?: run { finish(); return }
        val found = bankManager.listBanks().firstOrNull { it.name == name }
        if (found == null) {
            finish()
            return
        }
        currentBank = found
        adapter.setData(found.clips)
        txtEmpty.visibility = if (found.clips.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openClip(clip: AudioClip) {
        Thread {
            try {
                val full = bankManager.loadClip(clip.dir!!)
                runOnUiThread {
                    EditActivityHolder.current = full
                    startActivity(Intent(this, EditActivity::class.java))
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** E12: только IOException. */
    @Throws(IOException::class)
    private fun copyFile(src: File, dst: File) {
        FileInputStream(src).use { input ->
            FileOutputStream(dst).use { output ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                }
            }
        }
    }

    /** E8: обновляет clip.dir/clip.name и EditActivityHolder. */
    /** E8: обновляет clip.dir/clip.name и EditActivityHolder. */
    private fun renameClip(clip: AudioClip) {
        val input = EditText(this)
        val nameOnly = if (clip.name.endsWith(".wav"))
        clip.name.substring(0, clip.name.length - 4) else clip.name
        input.setText(nameOnly)
        input.setSelection(input.text.length)

        AlertDialog.Builder(this).setTitle("Новое имя").setView(input)
        .setPositiveButton("OK") { _, _ ->
            val newName = input.text.toString().trim()
            if (newName.isEmpty()) return@setPositiveButton

            val curDir = clip.dir ?: return@setPositiveButton
            val safe = FileNames.sanitize(newName)
            val newFile = File(curDir.parentFile, "$safe.wav")

            if (newFile.exists()) {
                Toast.makeText(this, "Файл с таким именем уже есть", Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }

            if (curDir.renameTo(newFile)) {
                val oldMeta = File(curDir.absolutePath + ".meta")
                val newMeta = File(newFile.absolutePath + ".meta")
                if (oldMeta.exists()) oldMeta.renameTo(newMeta)

                // E8: если открыт в редакторе — обновить путь/имя
                val cur = EditActivityHolder.current
                if (cur != null && cur.dir != null && cur.dir == curDir) {
                    cur.dir = newFile
                    cur.name = newFile.name
                }
                clip.dir = newFile
                clip.name = newFile.name
                reload()
            } else {
                Toast.makeText(this, "Ошибка переименования", Toast.LENGTH_SHORT).show()
            }
        }.setNegativeButton("Отмена", null).show()
    }

    private fun moveClip(clip: AudioClip) {
        showBankPicker("Переместить в банк") { targetBank ->
            if (currentBank?.name == targetBank.name) {
                Toast.makeText(this, "Это текущий банк", Toast.LENGTH_SHORT).show()
                return@showBankPicker
            }
            Thread {
                try {
                    val srcWav = clip.dir ?: return@Thread
                    val srcMeta = File(srcWav.absolutePath + ".meta")
                    val dstWav = File(targetBank.dir, srcWav.name)
                    val dstMeta = File(targetBank.dir, srcMeta.name)

                    if (srcWav.renameTo(dstWav)) {
                        if (srcMeta.exists()) srcMeta.renameTo(dstMeta)
                        runOnUiThread {
                            Toast.makeText(this, "Перемещено в ${targetBank.name}",
                                Toast.LENGTH_SHORT).show()
                            reload()
                        }
                    } else {
                        runOnUiThread {
                            Toast.makeText(this, "Ошибка перемещения", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }
    }

    private fun copyClip(clip: AudioClip) {
        showBankPicker("Копировать в банк") { targetBank ->
            Thread {
                try {
                    val srcWav = clip.dir ?: return@Thread
                    val srcMeta = File(srcWav.absolutePath + ".meta")

                    var baseName = srcWav.name
                    if (baseName.lowercase().endsWith(".wav")) {
                        baseName = baseName.substring(0, baseName.length - 4)
                    }

                    val isSameBank = currentBank?.name == targetBank.name
                    var newName = baseName
                    if (isSameBank) {
                        var counter = 1
                        var testFile = File(targetBank.dir, "${newName}_copy.wav")
                        while (testFile.exists()) {
                            counter++
                            testFile = File(targetBank.dir, "${newName}_copy$counter.wav")
                        }
                        newName = newName + "_copy" + (if (counter > 1) counter.toString() else "")
                    }

                    var dstWav = File(targetBank.dir, "$newName.wav")
                    var dstMeta = File(targetBank.dir, "$newName.wav.meta")

                    if (!isSameBank && dstWav.exists()) {
                        var counter = 1
                        var testFile = File(targetBank.dir, "${baseName}_$counter.wav")
                        while (testFile.exists()) {
                            counter++
                            testFile = File(targetBank.dir, "${baseName}_$counter.wav")
                        }
                        dstWav = testFile
                        dstMeta = File(targetBank.dir, testFile.name + ".meta")
                    }

                    copyFile(srcWav, dstWav)
                    if (srcMeta.exists()) copyFile(srcMeta, dstMeta)

                    val finalName = dstWav.name
                    runOnUiThread {
                        Toast.makeText(this, "Скопировано: $finalName", Toast.LENGTH_SHORT).show()
                        reload()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }
    }

    private fun showClipInfo(clip: AudioClip) {
        Thread {
            try {
                val full = bankManager.loadClip(clip.dir!!)
                runOnUiThread { AudioInfoDialog.show(this, full, -1, -1) }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Ошибка загрузки", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun deleteClip(clip: AudioClip) {
        AlertDialog.Builder(this).setTitle("Удалить клип?").setMessage(clip.name)
        .setPositiveButton("Удалить") { _, _ ->
            val dir = clip.dir
            if (dir != null) {
                dir.delete()
                val meta = File(dir.absolutePath + ".meta")
                if (meta.exists()) meta.delete()
            }
            reload()
        }.setNegativeButton("Отмена", null).show()
    }

    private fun showBankPicker(title: String, callback: BankPickerCallback) {
        val allBanks = bankManager.listBanks()
        if (allBanks.isEmpty()) {
            Toast.makeText(this, "Нет банков", Toast.LENGTH_SHORT).show()
            return
        }
        val names = allBanks.map { b ->
            b.name + (if (currentBank?.name == b.name) " (текущий)" else "")
        }.toTypedArray()

        AlertDialog.Builder(this).setTitle(title)
        .setItems(names) { _, which -> callback.onBankSelected(allBanks[which]) }
        .setNegativeButton("Отмена", null).show()
    }

    /** E6: проверка renameTo; E13: без ProgressDialog. */
    private fun importFromUri(uri: Uri?) {
        uri ?: return
        Toast.makeText(this, "Импорт...", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val tmp = File(cacheDir, "import_tmp")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tmp).use { output ->
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                        }
                    }
                }

                var displayName = "imported"
                contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (c.moveToFirst() && idx >= 0) displayName = c.getString(idx)
                }

                // E6: уникальное имя
                var renamed = File(cacheDir, displayName)
                if (renamed.exists()) {
                    var base = displayName
                    var ext = ""
                    val dot = base.lastIndexOf('.')
                    if (dot > 0) {
                        ext = base.substring(dot)
                        base = base.substring(0, dot)
                    }
                    var counter = 1
                    while (renamed.exists()) {
                        renamed = File(cacheDir, "${base}_$counter$ext")
                        counter++
                    }
                }
                if (!tmp.renameTo(renamed)) {
                    copyFile(tmp, renamed)
                    tmp.delete()
                }

                bankManager.importFile(currentBank!!, renamed)
                renamed.delete()

                runOnUiThread {
                    reload()
                    Toast.makeText(this, "Импортировано", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ---------- adapter ----------

    private inner class ClipAdapter : RecyclerView.Adapter<ClipAdapter.VH>() {
        private var data: List<AudioClip> = emptyList()

        fun setData(d: List<AudioClip>) {
            data = d
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_clip, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, pos: Int) {
            val c = data[pos]
            h.name.text = c.name
            val dir = c.dir
            h.info.text = if (dir != null) "${dir.length() / 1024} KB" else ""
            h.itemView.setOnClickListener { openClip(c) }
            h.itemView.setOnLongClickListener {
                AlertDialog.Builder(this@BankActivity).setTitle(c.name)
                .setItems(arrayOf("Переименовать", "Переместить в банк",
                        "Копировать в банк", "Инфо", "Удалить", "Открыть как проект",
                "Преобразовать в проект")) { _, which ->
                    when (which) {
                        0 -> renameClip(c)
                        1 -> moveClip(c)
                        2 -> copyClip(c)
                        3 -> showClipInfo(c)
                        4 -> deleteClip(c)
                        5 -> openAsProject(c) // Старый вариант (открывает папку как проект)
                        6 -> convertToProject(c) // Новый вариант (создает папку)
                    }
                }.show()
                true
            }
        }

        override fun getItemCount(): Int = data.size

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.txtClipName)
            val info: TextView = v.findViewById(R.id.txtClipInfo)
        }
    }

    fun interface BankPickerCallback {
        fun onBankSelected(bank: Bank)
    }
    private fun openAsProject(clip: AudioClip) {
        val dir = clip.dir?.parentFile
        if (dir == null) {
            Toast.makeText(this, "Ошибка: нет папки", Toast.LENGTH_SHORT).show()
            return
        }
        // Открываем MultitrackActivity, передавая путь к папке
        val intent = Intent(this, MultitrackActivity::class.java)
        intent.putExtra("projectDir", dir.absolutePath)
        startActivity(intent)
    }
    private fun convertToProject(clip: AudioClip) {
        val name = clip.name.substringBeforeLast('.')
        android.util.Log.d("PROJECT", "Старт конвертации: $name, dir=${clip.dir}")
        try {
            val project = ProjectManager.createProjectFromClip(clip, name)
            android.util.Log.d("PROJECT", "Успех: ${project.dir?.absolutePath}")
            Toast.makeText(this, "Проект создан: ${project.name}", Toast.LENGTH_SHORT).show()
            reload()
        } catch (e: Exception) {
            android.util.Log.e("PROJECT", "Ошибка", e)
            Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
