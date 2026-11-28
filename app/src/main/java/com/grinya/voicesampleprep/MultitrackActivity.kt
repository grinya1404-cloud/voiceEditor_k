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
import android.widget.ImageButton
import android.view.View
import com.grinya.voicesampleprep.audio.MultitrackPlayer
import com.grinya.voicesampleprep.audio.AudioPlayer

class MultitrackActivity : AppCompatActivity(), MultitrackWaveformView.Listener {

    private var wave: MultitrackWaveformView? = null
    private var project: AudioProject? = null
    private var activeTrack: ProjectTrack? = null
    private var isEditMode = false
    private var mtPlayer: MultitrackPlayer? = null
    private var isLeftEnabled = true
    private var isRightEnabled = true

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

        val dir = java.io.File(projectDirPath)
        if (!dir.exists()) {
            Toast.makeText(this, "Папка не найдена", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        try {
            project = com.grinya.voicesampleprep.model.ProjectManager.loadProject(dir)
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка загрузки: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        mtPlayer = MultitrackPlayer()
        mtPlayer?.setListener(object : AudioPlayer.Listener {
                override fun onPosition(positionMs: Int) {
                    val frame = (positionMs * project!!.sampleRate / 1000)
                    wave?.setPlayheadFrame(frame)
                }
                override fun onEnded() {
                    // Сброс иконки Play
                    findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.btnPlay).setImageResource(android.R.drawable.ic_media_play)
                }
                override fun onStateChanged(playing: Boolean) {
                    val icon = if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
                    findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.btnPlay).setImageResource(icon)
                }
        })

        mtPlayer?.prepare(project!!)

        findViewById<View>(R.id.btnPlay).setOnClickListener {
            if (mtPlayer?.isPlaying() == true) {
                mtPlayer?.pause()
            } else {
                mtPlayer?.play()
            }
        }

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        wave = findViewById(R.id.waveMultitrack)
        wave?.setListener(this)
        wave?.setProject(project)

        // --- Кнопка-замок (режим редактирования) ---
        // Замок
        val btnLock = findViewById<ImageButton>(R.id.btnLock)
        btnLock.setOnClickListener {
            isEditMode = !isEditMode
            wave?.setEditMode(isEditMode)
            btnLock.setColorFilter(
                if (isEditMode) 0xFF9C27B0.toInt() // Фиолетовый (открыт)
                else 0xFF808080.toInt()            // Серый (закрыт)
            )
            Toast.makeText(
                this,
                if (isEditMode) "Режим редактирования ВКЛ" else "Режим просмотра",
                Toast.LENGTH_SHORT
            ).show()
        }

        // --- Кнопка добавления трека ---
        findViewById<Button>(R.id.btnAddTrack).setOnClickListener { pickTrackToAdd() }

        findViewById<View>(R.id.btnStop).setOnClickListener {
            mtPlayer?.stop()
            wave?.setPlayheadFrame(0)
        }

        // Каналы L/R
        // Кнопки L/R (теперь Mute/Solo)
        val btnL = findViewById<Button>(R.id.btnL)
        val btnR = findViewById<Button>(R.id.btnR)

        btnL.setOnClickListener {
            val track = activeTrack ?: run {
                Toast.makeText(this, "Сначала выберите трек", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            track.isMuted = !track.isMuted
            btnL.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (track.isMuted) 0xFF555555.toInt() else 0xAAFFB300.toInt()
            )
            refreshPlayer()
            wave?.invalidate() // Перерисовать волну с новым цветом
        }

        btnR.setOnClickListener {
            val track = activeTrack ?: run {
                Toast.makeText(this, "Сначала выберите трек", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            track.isSolo = !track.isSolo
            btnR.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (track.isSolo) 0xFFFF3B30.toInt() else 0xAAFFB300.toInt()
            )
            refreshPlayer()
            wave?.invalidate()
        }

        // Заглушки для верхнего меню
        findViewById<View>(R.id.btnUndo).setOnClickListener { Toast.makeText(this, "Undo (в разработке)", Toast.LENGTH_SHORT).show() }
        findViewById<View>(R.id.btnRedo).setOnClickListener { Toast.makeText(this, "Redo (в разработке)", Toast.LENGTH_SHORT).show() }
        findViewById<View>(R.id.btnInfo).setOnClickListener { Toast.makeText(this, "Инфо (в разработке)", Toast.LENGTH_SHORT).show() }
        findViewById<View>(R.id.btnSave).setOnClickListener {
            project?.let {
                ProjectManager.saveProject(it)
                Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
            }
        }

    }

    private fun showTrackActions(track: ProjectTrack) {
        val items = arrayOf(
            "Редактировать",
            "Инфо",
            "Сдвинуть влево (0.1с)",
            "Сдвинуть вправо (0.1с)",
            "Сдвинуть влево (1с)",
            "Сдвинуть вправо (1с)",
            "Удалить из проекта"
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
        .setTitle(track.displayName)
        .setItems(items) { _, which ->
            when (which) {
                0 -> openEditor(track)
                1 -> showTrackInfo(track)
                2 -> shiftTrack(track, -0.1)
                3 -> shiftTrack(track, 0.1)
                4 -> shiftTrack(track, -1.0)
                5 -> shiftTrack(track, 1.0)
                6 -> deleteTrack(track)
            }
        }.show()
    }

    private fun shiftTrack(track: ProjectTrack, seconds: Double) {
        val p = project ?: return
        val deltaFrames = (seconds * p.sampleRate).toInt()
        track.startOffsetFrames = (track.startOffsetFrames + deltaFrames).coerceAtLeast(0)
        wave?.invalidate() // Перерисовать
        Toast.makeText(this, "Сдвинут на ${seconds}с", Toast.LENGTH_SHORT).show()
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
        // Перезагружаем проект, чтобы увидеть изменения после редактирования
        if (project != null) {
            val dir = project?.dir ?: return
            try {
                val newProject = ProjectManager.loadProject(dir)
                project = newProject
                wave?.setProject(newProject)
            } catch (e: Exception) {
                Toast.makeText(this, "Ошибка обновления: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // override fun onTrackSelected(track: ProjectTrack) {
    //     activeTrack = track
    //     // Toast.makeText(this, "Выбран: ${track.displayName}", Toast.LENGTH_SHORT).show()
    // }

    override fun onTrackSelected(track: ProjectTrack) {
        activeTrack = track
        // Обновляем кнопки
        val btnL = findViewById<Button>(R.id.btnL)
        val btnR = findViewById<Button>(R.id.btnR)
        btnL.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (!track.isMuted) 0xAAFFB300.toInt() else 0xAA1A1A1E.toInt()
        )
        btnR.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (!track.isSolo) 0xAAFFB300.toInt() else 0xAA1A1A1E.toInt()
        )
    }

    override fun onTrackLongPressed(track: ProjectTrack) {
        activeTrack = track
        showTrackActions(track) // Показываем меню (Редактировать, Инфо, Удалить)
    }

    override fun onSeek(frame: Int) {
        mtPlayer?.seekToFrame(frame)
        wave?.setPlayheadFrame(frame)
    }
    private fun refreshPlayer() {
        val p = project ?: return
        val wasPlaying = mtPlayer?.isPlaying() == true
        mtPlayer?.pause()
        mtPlayer?.prepare(p)
        if (wasPlaying) mtPlayer?.play()
        wave?.invalidate()
    }

}
