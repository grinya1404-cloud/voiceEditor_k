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

        // Пытаемся прочитать сохранённые смещения
        val savedOffsets = readOffsetsFromJson(File(dir, "project.json"))

        val laneMap = mutableMapOf<String, Int>()
        var maxLane = -1
        val laneOffsets = mutableMapOf<Int, Int>()

        for (f in files) {
            val r = WavReader.read(f)
            val clip = AudioClip(f.name, r.pcm, r.sampleRate)
            clip.dir = f

            val baseName = f.nameWithoutExtension
            val prefix = baseName.substringBeforeLast('_', baseName)

            val lane = laneMap.getOrPut(prefix) {
                maxLane++
                maxLane
            }

            // Если есть сохранённое смещение — берем его, иначе 0
            val offset = savedOffsets[f.name] ?: 0

            val track = ProjectTrack(clip, offset, lane, colorForLane(lane))
            project.tracks.add(track)
        }

        project.sampleRate = project.tracks.firstOrNull()?.clip?.sampleRate ?: 48000
        return project
    }

    private fun readOffsetsFromJson(jsonFile: File): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        if (!jsonFile.exists()) return map
        try {
            val text = jsonFile.readText()
            // Очень простой парсер: ищем "fileName": "...", "startOffsetFrames": N
            val regex = "\"fileName\":\\s*\"([^\"]+)\".*?\"startOffsetFrames\":\\s*(\\d+)".toRegex()
            regex.findAll(text).forEach { match ->
                val fileName = match.groupValues[1]
                val offset = match.groupValues[2].toIntOrNull() ?: 0
                map[fileName] = offset
            }
        } catch (e: Exception) {
            android.util.Log.e("PROJECT", "Ошибка чтения JSON", e)
        }
        return map
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

    fun saveProject(project: AudioProject) {
        val dir = project.dir ?: return
        val jsonFile = File(dir, "project.json")
        try {
            jsonFile.writeText(buildString {
                    append("{\n")
                    append("  \"name\": \"${project.name}\",\n")
                    append("  \"sampleRate\": ${project.sampleRate},\n")
                    append("  \"tracks\": [\n")
                    project.tracks.forEachIndexed { index, track ->
                        append("    {")
                        append("\"fileName\": \"${track.clip.name}\", ")
                        append("\"startOffsetFrames\": ${track.startOffsetFrames}, ")
                        append("\"laneIndex\": ${track.laneIndex}")
                        append("}")
                        if (index < project.tracks.size - 1) append(",")
                        append("\n")
                    }
                    append("  ]\n")
                    append("}\n")
            })
        } catch (e: Exception) {
            android.util.Log.e("PROJECT", "Ошибка сохранения", e)
        }
    }

}
