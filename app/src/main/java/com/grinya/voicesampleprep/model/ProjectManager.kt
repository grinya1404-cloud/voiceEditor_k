package com.grinya.voicesampleprep.model

import com.grinya.voicesampleprep.audio.WavReader
import java.io.File
import java.io.IOException

object ProjectManager {

    /** Загрузить проект из папки. Все WAV-файлы становятся треками. */
    @Throws(IOException::class)
    fun loadProject(dir: File, name: String = dir.name): AudioProject {
        val project = AudioProject(name, dir)
        val files = dir.listFiles { f -> f.isFile && f.name.lowercase().endsWith(".wav") } ?: return project
        files.sortBy { it.name }

        val laneMap = mutableMapOf<String, Int>()
        var maxLane = -1
        var currentOffset = 0

        for (f in files) {
            val r = WavReader.read(f)
            val clip = AudioClip(f.name, r.pcm, r.sampleRate)
            clip.dir = f

            // Пытаемся понять, на какой Lane положить трек.
            // Логика: "тест_9.wav" -> префикс "тест_", он же Lane.
            val baseName = f.nameWithoutExtension
            val prefix = baseName.substringBeforeLast('_', baseName)

            val lane = laneMap.getOrPut(prefix) {
                maxLane++
                maxLane
            }

            // Смещение: если мы кладем на тот же Lane, что и предыдущий,
            // то ставим встык (или в 0, если это первый трек на Lane).
            // Пока для простоты ставим 0 для всех.
            val offset = 0

            val track = ProjectTrack(clip, offset, lane, colorForLane(lane))
            project.tracks.add(track)
        }

        project.sampleRate = project.tracks.firstOrNull()?.clip?.sampleRate ?: 48000
        return project
    }

    private fun colorForLane(lane: Int): Int {
        val colors = intArrayOf(
            0xFFFFB300.toInt(), // Янтарный
            0xFF4FC3F7.toInt(), // Голубой
            0xFF4CAF50.toInt(), // Зеленый
            0xFFF44336.toInt(), // Красный
            0xFF9C27B0.toInt()  // Фиолетовый
        )
        return colors[lane % colors.size]
    }
    /** Превращает одиночный клип в проект (создает папку и перемещает туда WAV). */
    @Throws(IOException::class)
    fun createProjectFromClip(clip: AudioClip, projectName: String): AudioProject {
        val srcWav = clip.dir ?: throw IOException("Нет файла у клипа")
        val parentDir = srcWav.parentFile ?: throw IOException("Нет родительской папки")

        val projectDir = File(parentDir, "${projectName}_Project")
        android.util.Log.d("PROJECT", "Создаем папку: ${projectDir.absolutePath}")

        if (!projectDir.exists()) {
            val created = projectDir.mkdirs()
            android.util.Log.d("PROJECT", "mkdirs result: $created")
            if (!created) throw IOException("Не удалось создать папку")
        }

        // Перемещаем WAV в папку проекта
        val dstWav = File(projectDir, srcWav.name)
        android.util.Log.d("PROJECT", "Перемещаем: ${srcWav.absolutePath} -> ${dstWav.absolutePath}")

        var success = srcWav.renameTo(dstWav)
        if (!success) {
            android.util.Log.d("PROJECT", "renameTo не сработал, копируем...")
            try {
                srcWav.copyTo(dstWav, overwrite = true)
                srcWav.delete()
                success = true
            } catch (e: Exception) {
                android.util.Log.e("PROJECT", "Ошибка копирования", e)
            }
        }

        if (!success) throw IOException("Не удалось переместить WAV")

        // Перемещаем .meta
        val srcMeta = File(srcWav.absolutePath + ".meta")
        if (srcMeta.exists()) {
            val dstMeta = File(projectDir, srcMeta.name)
            srcMeta.renameTo(dstMeta)
        }

        // Создаем project.json
        val jsonFile = File(projectDir, "project.json")
        jsonFile.writeText("{\n  \"name\": \"$projectName\",\n  \"version\": 1\n}")
        android.util.Log.d("PROJECT", "project.json создан: ${jsonFile.exists()}")

        // Создаем AudioProject
        val project = AudioProject(projectName, projectDir)
        val r = WavReader.read(dstWav)
        val newClip = AudioClip(dstWav.name, r.pcm, r.sampleRate)
        newClip.dir = dstWav

        val track = ProjectTrack(newClip, 0, 0, 0xFFFFB300.toInt())
        project.tracks.add(track)
        project.sampleRate = newClip.sampleRate

        return project
    }

}
