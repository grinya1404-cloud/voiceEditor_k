package com.grinya.voicesampleprep.model

import android.content.Context
import com.grinya.voicesampleprep.audio.AudioDecoder
import com.grinya.voicesampleprep.audio.WavReader
import com.grinya.voicesampleprep.audio.WavWriter
import com.grinya.voicesampleprep.util.FileNames
import java.io.File
import java.io.FileFilter
import java.io.FilenameFilter
import java.io.IOException

class BankManager(c: Context) {

    val banksRoot: File

    init {
        val external = c.applicationContext.getExternalFilesDir(null)
        banksRoot = File(external, "banks")
        if (!banksRoot.exists()) banksRoot.mkdirs()
    }

    /** Список банков (папок первого уровня). E1: читает .meta для SR/каналов/битности. */
    fun listBanks(): List<Bank> {
        val result = ArrayList<Bank>()
        val dirs = banksRoot.listFiles(FileFilter { it.isDirectory }) ?: return result
        dirs.sortWith { a, b -> a.name.compareTo(b.name, ignoreCase = true) }
        for (d in dirs) {
            val b = Bank()
            b.name = d.name
            b.dir = d
            val files = d.listFiles(FilenameFilter { _, n -> n.lowercase().endsWith(".wav") })
            if (files != null) {
                files.sortWith { a, c -> a.name.compareTo(c.name, ignoreCase = true) }
                for (f in files) {
                    val meta = AudioMeta.load(f)
                    val sr = if (meta.sampleRate > 0) meta.sampleRate else AudioClip.SAMPLE_RATE
                    val clip = AudioClip(f.name, FloatArray(0), sr)
                    clip.dir = f
                    b.clips.add(clip)
                }
            }
            result.add(b)
        }
        return result
    }

    /**
     * E2: если банк с таким именем уже есть — возвращаем его (не создаём дубль). Если имя пустое/невалидное — sanitize → "untitled".
     */
    fun createBank(name: String?): Bank {
        val safe = sanitize(name)
        val dir = File(banksRoot, safe)
        if (!dir.exists()) dir.mkdirs()
        val b = Bank()
        b.name = safe
        b.dir = dir
        return b
    }

    /**
     * R: переименовать банк (папку). Возвращает true, если удалось. Если новое имя занято — false. Если пустое — sanitize → "untitled".
     */
    fun renameBank(bank: Bank?, newName: String?): Boolean {
        if (bank == null) return false
        val oldDir = bank.dir ?: return false
        val safe = sanitize(newName)
        if (safe == bank.name) return true
        val newDir = File(banksRoot, safe)
        if (newDir.exists()) return false
        if (!oldDir.renameTo(newDir)) return false
        bank.name = safe
        bank.dir = newDir
        return true
    }

    /** E3: возвращает true, если папка удалена (или уже не существует). */
    fun deleteBank(b: Bank?): Boolean {
        val dir = b?.dir ?: return false
        if (!dir.exists()) return true
        return deleteRecursive(dir)
    }

    /** Сохранить клип в банк как WAV (16-bit, 48к, stereo). Возвращает файл. */
    @Throws(IOException::class)
    fun saveClipToBank(bank: Bank, clip: AudioClip, baseName: String?): File {
        val safe = sanitize(baseName)
        var f = File(bank.dir, "$safe.wav")
        var counter = 1
        while (f.exists()) {
            f = File(bank.dir, "${safe}_$counter.wav")
            counter++
        }
        WavWriter.write(f, clip.pcm, clip.sampleRate, clip.sampleRate, 16, false, false, false, false, false)
        return f
    }

    /** Загрузить PCM из файла в AudioClip. */
    @Throws(IOException::class)
    fun loadClip(wavFile: File): AudioClip {
        val r = WavReader.read(wavFile)
        val c = AudioClip(wavFile.name, r.pcm, r.sampleRate)
        c.dir = wavFile

        val meta = AudioMeta.load(wavFile)
        c.markers = meta.markers
        c.exportIntervals = meta.exportIntervals

        return c
    }

    /** Импортировать любой файл → сохранить как WAV в банк → вернуть клип. */
    @Throws(IOException::class)
    fun importFile(bank: Bank, src: File): AudioClip {
        val r = AudioDecoder.decode(src)
        var base = src.name
        val dot = base.lastIndexOf('.')
        if (dot > 0) base = base.substring(0, dot)
        val safe = sanitize(base)
        var out = File(bank.dir, "$safe.wav")
        var counter = 1
        while (out.exists()) {
            out = File(bank.dir, "${safe}_$counter.wav")
            counter++
        }
        WavWriter.write(out, r.pcm, r.sampleRate, r.sampleRate, 16, false, false, false, false, false)

        // Сохраняем .meta с параметрами ИСХОДНИКА (до конвертации)
        val meta = AudioMeta()
        meta.sampleRate = r.originalSampleRate
        meta.channels = r.originalChannels
        meta.bits = r.originalBits
        meta.format = r.originalFormat
        AudioMeta.save(out, meta)

        val c = AudioClip(out.name, r.pcm, r.sampleRate)
        c.dir = out
        return c
    }

    private fun sanitize(name: String?): String = FileNames.sanitize(name)

    /** E3: рекурсивное удаление с проверкой результата. */
    private fun deleteRecursive(f: File): Boolean {
        var ok = true
        if (f.isDirectory) {
            f.listFiles()?.forEach { kid ->
                if (!deleteRecursive(kid)) ok = false
            }
        }
        val deleted = f.delete()
        return ok && deleted
    }
}
