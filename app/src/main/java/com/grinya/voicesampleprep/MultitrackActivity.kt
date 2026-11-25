package com.grinya.voicesampleprep.edit

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.grinya.voicesampleprep.R
import com.grinya.voicesampleprep.audio.AudioDecoder
import com.grinya.voicesampleprep.audio.WavWriter
import com.grinya.voicesampleprep.model.AudioProject
import com.grinya.voicesampleprep.model.ProjectManager
import com.grinya.voicesampleprep.model.ProjectTrack
import com.grinya.voicesampleprep.util.AudioInfoDialog
import java.io.File
import java.io.FileOutputStream

class MultitrackActivity : AppCompatActivity(), MultitrackWaveformView.Listener {

    private var wave: MultitrackWaveformView? = null
    private var project: AudioProject? = null
    private var activeTrack: ProjectTrack? = null

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri -> importTrackToProject(uri) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_multitrack)

        val projectDirPath = intent.getStringExtra("projectDir")
        if (projectDirPath == null) {
            finish()
            return
        }

        val dir = File(projectDirPath)
        if (!dir.exists()) {
            Toast.makeText(this, "Папка не найдена", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        try {
            project = ProjectManager.loadProject(dir)
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка загрузки: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        wave = findViewById(R.id.waveMultitrack)
        wave?.setListener(this)
        wave?.setProject(project)

        findViewById<Button>(R.id.btnAddTrack).setOnClickListener { pickTrackToAdd() }
    }

    

    private fun showTrackActions(track: ProjectTrack) {
        AlertDialog.Builder(this)
        .setTitle(track.displayName)
        .setItems(arrayOf("Редактировать", "Инфо", "Удалить из проекта")) { _, which ->
            when (which) {
                0 -> openEditor(track)
                1 -> showTrackInfo(track)
                2 -> deleteTrack(track)
            }
        }.show()
    }

    private fun openEditor(track: ProjectTrack) {
        // Передаем клип в EditActivity
        EditActivityHolder.current = track.clip
        startActivity(Intent(this, EditActivity::class.java))
    }

    private fun showTrackInfo(track: ProjectTrack) {
        AudioInfoDialog.show(this, track.clip, -1, -1)
    }

    private fun deleteTrack(track: ProjectTrack) {
        AlertDialog.Builder(this)
        .setTitle("Удалить трек?")
        .setMessage(track.displayName)
        .setPositiveButton("Удалить") { _, _ ->
            val file = track.clip.dir
            if (file != null && file.exists()) {
                file.delete()
                val meta = File(file.absolutePath + ".meta")
                if (meta.exists()) meta.delete()
            }
            reloadProject()
        }.setNegativeButton("Отмена", null).show()
    }

    private fun pickTrackToAdd() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/wav", "audio/x-wav",
                    "audio/mpeg", "audio/mp4", "audio/aac", "audio/flac", "audio/x-flac", "audio/*"))
        }
        importLauncher.launch(i)
    }

    private fun importTrackToProject(uri: Uri) {
        val p = project ?: return
        val projectDir = p.dir ?: return

        Toast.makeText(this, "Импорт трека...", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val tmp = File(cacheDir, "track_import_tmp")
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

                val r = AudioDecoder.decode(tmp)
                tmp.delete()

                var name = "track_${System.currentTimeMillis()}"
                var out = File(projectDir, "$name.wav")
                var counter = 1
                while (out.exists()) {
                    out = File(projectDir, "${name}_$counter.wav")
                    counter++
                }

                WavWriter.write(out, r.pcm, r.sampleRate, r.sampleRate, 16, false, false, false, false, false)

                runOnUiThread {
                    reloadProject()
                    Toast.makeText(this, "Трек добавлен", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Ошибка импорта: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun reloadProject() {
        val dir = project?.dir ?: return
        try {
            project = ProjectManager.loadProject(dir)
            wave?.setProject(project)
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка перезагрузки: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // Если мы возвращаемся из EditActivity, перезагружаем проект, чтобы увидеть изменения
        if (project != null) {
            reloadProject()
        }
    }
    override fun onTrackSelected(track: ProjectTrack) {
        activeTrack = track
        Toast.makeText(this, "Выбран: ${track.displayName}", Toast.LENGTH_SHORT).show()
    }

    override fun onTrackLongPressed(track: ProjectTrack) {
        activeTrack = track
        showTrackActions(track) // Показываем меню (Редактировать, Инфо, Удалить)
    }
}
