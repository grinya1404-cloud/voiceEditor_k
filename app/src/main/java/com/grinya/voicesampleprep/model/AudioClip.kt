package com.grinya.voicesampleprep.model

import com.grinya.voicesampleprep.audio.WavWriter
import com.grinya.voicesampleprep.edit.UndoStack
import com.grinya.voicesampleprep.util.AudioOps
import java.io.File
import java.io.IOException
import java.util.TreeSet
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class AudioClip(
    @JvmField var name: String,
    @JvmField var pcm: FloatArray, // interleaved stereo L,R,L,R...
    @JvmField var sampleRate: Int,
) {
    @JvmField var dir: File? = null
    @JvmField var channels: Int = CHANNELS
    @JvmField var markers: MutableList<Int> = ArrayList() // индексы фреймов
    @JvmField var exportIntervals: MutableList<IntArray> = ArrayList()

    private val history = UndoStack()

    companion object {
        const val CHANNELS = 2
        const val SAMPLE_RATE = 48000
    }

    fun frameCount(): Int = pcm.size / CHANNELS

    fun durationSec(): Double = frameCount() / sampleRate.toDouble()

    // ---------- операции ----------

    fun trim(startFrame: Int, endFrame: Int) {
        pushHistory()
        pcm = AudioOps.trim(pcm, startFrame, endFrame)
        fixMarkersAfterTrim(startFrame, endFrame)
        fixExportIntervalsAfterTrim(startFrame, endFrame)
    }

    fun autoTrim(startFrame: Int, endFrame: Int, threshold: Float, applyL: Boolean, applyR: Boolean) {
        if (pcm.isEmpty()) return

        val total = frameCount()
        val s = max(0, min(startFrame, total))
        val e = max(s, min(endFrame, total))
        if (e <= s) return

        val range = AudioOps.trim(pcm, s, e)
        val rangeFrames = range.size / CHANNELS

        val trimmed = AudioOps.trimSilence(range, threshold, CHANNELS)
        val trimmedFrames = trimmed.size / CHANNELS
        if (trimmed.isEmpty() || trimmed.size == range.size) return

        var startSilence = 0
        for (i in 0 until rangeFrames) {
            var silent = true
            for (c in 0 until CHANNELS) {
                if (abs(range[i * CHANNELS + c]) >= threshold) {
                    silent = false
                    break
                }
            }
            if (!silent) break
            startSilence++
        }
        var endSilence = rangeFrames - trimmedFrames - startSilence
        if (endSilence < 0) endSilence = 0

        val newPcm = FloatArray(pcm.size - range.size + trimmed.size)
        System.arraycopy(pcm, 0, newPcm, 0, s * CHANNELS)
        System.arraycopy(trimmed, 0, newPcm, s * CHANNELS, trimmed.size)
        System.arraycopy(pcm, e * CHANNELS, newPcm, s * CHANNELS + trimmed.size, (total - e) * CHANNELS)

        pushHistory()
        pcm = newPcm

        val newStart = s
        var newEnd = s + trimmedFrames - 1
        if (newEnd < newStart) newEnd = newStart

        val outMarkers = TreeSet<Int>()
        for (m in markers) {
            outMarkers.add(remapFrame(m, s, e, startSilence, endSilence, rangeFrames, newStart, newEnd))
        }
        markers = ArrayList(outMarkers)

        val seen = TreeSet<String>()
        val outIntervals = ArrayList<IntArray>()
        for (iv in exportIntervals) {
            val a = remapFrame(iv[0], s, e, startSilence, endSilence, rangeFrames, newStart, newEnd)
            val b = remapFrame(iv[1], s, e, startSilence, endSilence, rangeFrames, newStart, newEnd)
            if (b <= a) continue
            val key = "$a:$b"
            if (seen.add(key)) outIntervals.add(intArrayOf(a, b))
        }
        exportIntervals = outIntervals
    }

    private fun remapFrame(
        m: Int, s: Int, e: Int, startSilence: Int, endSilence: Int,
        rangeFrames: Int, newStart: Int, newEnd: Int,
    ): Int {
        if (m < s) return m
        if (m >= e) return m - (startSilence + endSilence)
        val local = m - s
        if (local < startSilence) return newStart
        if (local >= rangeFrames - endSilence) return newEnd
        return s + local - startSilence
    }

    fun delete(startFrame: Int, endFrame: Int) {
        pushHistory()
        pcm = AudioOps.deleteRange(pcm, startFrame, endFrame)
        fixMarkersAfterDelete(startFrame, endFrame)
        fixExportIntervalsAfterDelete(startFrame, endFrame)
    }

    fun gain(dB: Float) {
        pushHistory()
        pcm = AudioOps.gain(pcm, dB)
    }

    fun silence(startFrame: Int, endFrame: Int, applyL: Boolean, applyR: Boolean) {
        pushHistory()
        pcm = AudioOps.silenceRange(pcm, startFrame, endFrame, applyL, applyR)
    }

    fun gainRange(startFrame: Int, endFrame: Int, dB: Float, applyL: Boolean, applyR: Boolean) {
        pushHistory()
        pcm = AudioOps.gainRange(pcm, startFrame, endFrame, dB, applyL, applyR)
    }

    fun normalizeRangePeak(startFrame: Int, endFrame: Int, target: Float, applyL: Boolean, applyR: Boolean) {
        pushHistory()
        pcm = AudioOps.normalizeRangePeak(pcm, startFrame, endFrame, target, applyL, applyR)
    }

    fun normalizePeak(target: Float, applyL: Boolean, applyR: Boolean) {
        pushHistory()
        if (applyL && applyR) {
            pcm = AudioOps.normalizePeak(pcm, target)
        } else {
            pcm = AudioOps.normalizeRangePeak(pcm, 0, frameCount(), target, applyL, applyR)
        }
    }

    fun fadeIn(fadeFrames: Int, applyL: Boolean, applyR: Boolean) {
        pushHistory()
        pcm = AudioOps.fadeIn(pcm, fadeFrames, applyL, applyR)
    }

    fun fadeOut(fadeFrames: Int, applyL: Boolean, applyR: Boolean) {
        pushHistory()
        pcm = AudioOps.fadeOut(pcm, fadeFrames, applyL, applyR)
    }

    fun reverse(applyL: Boolean, applyR: Boolean) {
        pushHistory()
        pcm = AudioOps.reverse(pcm, applyL, applyR)
        val total = frameCount()
        val newMarkers = ArrayList<Int>()
        for (m in markers) newMarkers.add(total - 1 - m)
        newMarkers.sort()
        markers = newMarkers
    }

    fun reverseRange(startFrame: Int, endFrame: Int, applyL: Boolean, applyR: Boolean) {
        pushHistory()
        pcm = AudioOps.reverseRange(pcm, startFrame, endFrame, applyL, applyR)

        val s = max(0, min(startFrame, frameCount()))
        val e = max(s, min(endFrame, frameCount()))
        val newMarkers = ArrayList<Int>()
        for (m in markers) {
            if (m >= s && m < e) {
                newMarkers.add(s + (e - 1 - m))
            } else {
                newMarkers.add(m)
            }
        }
        newMarkers.sort()
        markers = newMarkers
    }

    fun invertPhase(startFrame: Int, endFrame: Int, applyL: Boolean, applyR: Boolean) {
        pushHistory()
        pcm = AudioOps.invertPhase(pcm, startFrame, endFrame, applyL, applyR)
    }

    fun invertPhaseRange(startFrame: Int, endFrame: Int, applyL: Boolean, applyR: Boolean) {
        invertPhase(startFrame, endFrame, applyL, applyR)
    }

    fun setPcm(newPcm: FloatArray) {
        pushHistory()
        pcm = newPcm
    }

    fun fadeInRange(startFrame: Int, endFrame: Int, fadeFrames: Int, applyL: Boolean, applyR: Boolean) {
        pushHistory()
        pcm = AudioOps.fadeInRange(pcm, startFrame, endFrame, fadeFrames, applyL, applyR)
    }

    fun fadeOutRange(startFrame: Int, endFrame: Int, fadeFrames: Int, applyL: Boolean, applyR: Boolean) {
        pushHistory()
        pcm = AudioOps.fadeOutRange(pcm, startFrame, endFrame, fadeFrames, applyL, applyR)
    }

    fun highPass(startFrame: Int, endFrame: Int, cutoffHz: Float, applyL: Boolean, applyR: Boolean) {
        pushHistory()
        val total = frameCount()
        val s = max(0, min(startFrame, total))
        val e = max(s, min(endFrame, total))
        if (e <= s) return
        var range = AudioOps.trim(pcm, s, e)
        range = AudioOps.highPass(range, sampleRate, cutoffHz, applyL, applyR)
        System.arraycopy(range, 0, pcm, s * CHANNELS, range.size)
    }

    fun mixToMonoRange(startFrame: Int, endFrame: Int) {
        pushHistory()
        val total = frameCount()
        val s = max(0, min(startFrame, total))
        val e = max(s, min(endFrame, total))
        if (e <= s) return

        val range = AudioOps.trim(pcm, s, e)
        val mono = AudioOps.toMonoSmart(range)

        for (i in mono.indices) {
            pcm[(s + i) * CHANNELS] = mono[i]
            pcm[(s + i) * CHANNELS + 1] = mono[i]
        }
    }

    fun swapChannels(startFrame: Int, endFrame: Int) {
        pushHistory()
        val total = frameCount()
        val s = max(0, min(startFrame, total))
        val e = max(s, min(endFrame, total))
        for (i in s until e) {
            val idx = i * CHANNELS
            val tmp = pcm[idx]
            pcm[idx] = pcm[idx + 1]
            pcm[idx + 1] = tmp
        }
    }

    /** Заменить PCM в диапазоне [startFrame, endFrame) без изменения длины и маркеров. */
    fun replaceRangePcm(newPcm: FloatArray, startFrame: Int, endFrame: Int) {
        val total = frameCount()
        val s = max(0, min(startFrame, total))
        val e = max(s, min(endFrame, total))
        val expected = (e - s) * CHANNELS
        if (newPcm.size != expected) return
        pushHistory()
        System.arraycopy(newPcm, 0, pcm, s * CHANNELS, expected)
    }

    fun copyLtoR(startFrame: Int, endFrame: Int) {
        pushHistory()
        val total = frameCount()
        val s = max(0, min(startFrame, total))
        val e = max(s, min(endFrame, total))
        for (i in s until e) {
            pcm[i * CHANNELS + 1] = pcm[i * CHANNELS]
        }
    }

    fun copyRtoL(startFrame: Int, endFrame: Int) {
        pushHistory()
        val total = frameCount()
        val s = max(0, min(startFrame, total))
        val e = max(s, min(endFrame, total))
        for (i in s until e) {
            pcm[i * CHANNELS] = pcm[i * CHANNELS + 1]
        }
    }

    // ---------- undo/redo ----------

    private fun pushHistory() {
        history.push(pcm.copyOf(), markers, exportIntervals)
    }

    fun canUndo(): Boolean = history.canUndo()

    fun canRedo(): Boolean = history.canRedo()

    fun undo() {
        val s = history.undo(pcm, markers, exportIntervals)
        if (s != null) {
            pcm = s.pcm
            markers = s.markers
            exportIntervals = s.exportIntervals
        }
    }

    fun redo() {
        val s = history.redo(pcm, markers, exportIntervals)
        if (s != null) {
            pcm = s.pcm
            markers = s.markers
            exportIntervals = s.exportIntervals
        }
    }

    // ---------- маркеры ----------

    fun addMarker(frame: Int) {
        val f = max(0, min(frameCount() - 1, frame))
        if (!markers.contains(f)) {
            pushHistory()
            markers.add(f)
            markers.sort()
        }
    }

    fun removeMarker(frame: Int) {
        if (markers.contains(frame)) {
            pushHistory()
            markers.remove(frame)
        }
    }

    fun clearMarkers() {
        if (markers.isNotEmpty()) {
            pushHistory()
            markers.clear()
        }
    }

    /**
     * F11: переместить маркер oldFrame → newFrame. Обновляет интервалы, где участвовал oldFrame (концы заменяются на newFrame). Если newFrame занят другим маркером — не двигаем (возвращаем false). Схлопнувшиеся интервалы (a >= b) удаляются.
     */
    fun moveMarker(oldFrame: Int, newFrame: Int): Boolean {
        if (oldFrame == newFrame) return false
        if (!markers.contains(oldFrame)) return false
        val target = max(0, min(frameCount() - 1, newFrame))
        if (markers.contains(target)) return false

        pushHistory()

        markers.remove(oldFrame)
        markers.add(target)
        markers.sort()

        val out = ArrayList<IntArray>()
        val seen = TreeSet<String>()
        for (iv in exportIntervals) {
            val a = if (iv[0] == oldFrame) target else iv[0]
            val b = if (iv[1] == oldFrame) target else iv[1]
            if (b <= a) continue
            val key = "$a:$b"
            if (seen.add(key)) out.add(intArrayOf(a, b))
        }
        exportIntervals = out
        return true
    }

    // ---------- коррекция маркеров ----------

    private fun fixMarkersAfterTrim(startFrame: Int, endFrame: Int) {
        val span = endFrame - startFrame
        val out = ArrayList<Int>()
        for (m in markers) {
            if (m >= startFrame && m < endFrame) out.add(m - startFrame)
        }
        out.sort()
        markers = out
        val maxFrame = max(0, span - 1)
        for (i in markers.indices) {
            if (markers[i] > maxFrame) markers[i] = maxFrame
        }
    }

    private fun fixMarkersAfterDelete(startFrame: Int, endFrame: Int) {
        val removed = endFrame - startFrame
        val out = ArrayList<Int>()
        for (m in markers) {
            if (m < startFrame) {
                out.add(m)
            } else if (m >= endFrame) {
                out.add(m - removed)
            }
        }
        out.sort()
        markers = out
    }

    private fun fixExportIntervalsAfterTrim(startFrame: Int, endFrame: Int) {
        val span = endFrame - startFrame
        val out = ArrayList<IntArray>()
        for (iv in exportIntervals) {
            var a = max(iv[0], startFrame) - startFrame
            var b = min(iv[1], endFrame) - startFrame
            if (b > a) {
                if (b > span) b = span
                if (a < 0) a = 0
                out.add(intArrayOf(a, b))
            }
        }
        exportIntervals = out
    }

    private fun fixExportIntervalsAfterDelete(startFrame: Int, endFrame: Int) {
        val removed = endFrame - startFrame
        val out = ArrayList<IntArray>()
        for (iv in exportIntervals) {
            val a = iv[0]
            val b = iv[1]
            if (b <= startFrame) {
                out.add(intArrayOf(a, b))
            } else if (a >= endFrame) {
                out.add(intArrayOf(a - removed, b - removed))
            } else {
                val na = if (a < startFrame) a else startFrame
                val nb = if (b > endFrame) b - removed else startFrame
                if (nb > na) out.add(intArrayOf(na, nb))
            }
        }
        exportIntervals = out
    }

    // ---------- интервалы экспорта ----------

    fun addExportInterval(startMarker: Int, endMarker: Int): Boolean {
        if (startMarker >= endMarker) return false
        for (i in exportIntervals) {
            if (i[0] == startMarker && i[1] == endMarker) return false
        }
        pushHistory()
        exportIntervals.add(intArrayOf(startMarker, endMarker))
        return true
    }

    fun removeExportInterval(startMarker: Int, endMarker: Int) {
        val changed = exportIntervals.removeAll { it[0] == startMarker && it[1] == endMarker }
        if (changed) pushHistory()
    }

    fun removeExportIntervalsWithMarker(marker: Int) {
        val changed = exportIntervals.removeAll { it[0] == marker || it[1] == marker }
        if (changed) pushHistory()
    }

    fun clearExportIntervals() {
        if (exportIntervals.isNotEmpty()) {
            pushHistory()
            exportIntervals.clear()
        }
    }

    // ---------- вставка ----------

    fun insert(newPcm: FloatArray, atFrame: Int, selStart: Int, selEnd: Int) {
        pushHistory()

        val totalFrames = frameCount()
        val newFrames = newPcm.size / CHANNELS

        val startReplace: Int
        val endReplace: Int
        if (selStart >= 0 && selEnd > selStart) {
            startReplace = max(0, min(selStart, totalFrames))
            endReplace = max(startReplace, min(selEnd, totalFrames))
        } else {
            startReplace = max(0, min(atFrame, totalFrames))
            endReplace = startReplace
        }

        val removedFrames = endReplace - startReplace
        val newTotalFrames = totalFrames - removedFrames + newFrames
        val out = FloatArray(newTotalFrames * CHANNELS)

        System.arraycopy(pcm, 0, out, 0, startReplace * CHANNELS)
        System.arraycopy(newPcm, 0, out, startReplace * CHANNELS, newPcm.size)
        System.arraycopy(
            pcm, endReplace * CHANNELS, out, (startReplace + newFrames) * CHANNELS,
            (totalFrames - endReplace) * CHANNELS,
        )

        pcm = out

        val delta = newFrames - removedFrames
        val newMarkers = ArrayList<Int>()
        for (m in markers) {
            if (m < startReplace) {
                newMarkers.add(m)
            } else if (m >= endReplace) {
                newMarkers.add(m + delta)
            }
        }
        newMarkers.sort()
        markers = newMarkers

        val newIntervals = ArrayList<IntArray>()
        for (iv in exportIntervals) {
            val a = iv[0]
            val b = iv[1]
            val na = when {
                a < startReplace -> a
                a >= endReplace -> a + delta
                else -> startReplace
            }
            val nb = when {
                b < startReplace -> b
                b >= endReplace -> b + delta
                else -> startReplace + newFrames
            }
            if (nb > na) newIntervals.add(intArrayOf(na, nb))
        }
        exportIntervals = newIntervals
    }

    // ---------- сохранение ----------

    @Throws(IOException::class)
    fun save() {
        val d = dir ?: throw IOException("No file")
        WavWriter.write(d, pcm, sampleRate, sampleRate, 16, false, false, false, false, false)

        val meta = AudioMeta.load(d)
        meta.markers = ArrayList(markers)
        meta.exportIntervals = exportIntervals.map { intArrayOf(it[0], it[1]) }.toMutableList()
        AudioMeta.save(d, meta)
    }
}
