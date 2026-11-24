package com.grinya.voicesampleprep.util

import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.grinya.voicesampleprep.R
import com.grinya.voicesampleprep.model.AudioClip
import com.grinya.voicesampleprep.model.AudioMeta
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import com.grinya.voicesampleprep.util.TimeFormat

object AudioInfoDialog {

    @JvmStatic
    fun show(context: Context, clip: AudioClip?, selStart: Int, selEnd: Int) {
        if (clip == null || clip.pcm.isEmpty()) {
            Toast.makeText(context, "Нет данных", Toast.LENGTH_SHORT).show()
            return
        }

        val fullInfo = AudioInfo.analyze(clip.pcm, clip.sampleRate, clip.channels)

        var selInfo: AudioInfo? = null
        if (selStart >= 0 && selEnd > selStart) {
            val total = clip.frameCount()
            val s = max(0, min(selStart, total))
            val e = max(s, min(selEnd, total))
            if (e > s) {
                val selPcm = AudioOps.trim(clip.pcm, s, e)
                selInfo = AudioInfo.analyze(selPcm, clip.sampleRate, clip.channels)
            }
        }

        val text = buildInfoText(clip, fullInfo, selInfo, selStart, selEnd)
        showDialog(context, "Инфо", text)
    }

    @JvmStatic
    fun showInterval(context: Context, clip: AudioClip?, startFrame: Int, endFrame: Int) {
        if (clip == null || clip.pcm.isEmpty()) {
            Toast.makeText(context, "Нет данных", Toast.LENGTH_SHORT).show()
            return
        }
        // G3: защита от перепутанных границ
        val from = min(startFrame, endFrame)
        val to = max(startFrame, endFrame)

        val range = AudioOps.trim(clip.pcm, from, to)
        val info = AudioInfo.analyze(range, clip.sampleRate, clip.channels)

        val startMarkerNum = clip.markers.indexOf(from) + 1
        val endMarkerNum = clip.markers.indexOf(to) + 1

        val sb = StringBuilder()
        val startMs = from * 1000 / clip.sampleRate
        val endMs = to * 1000 / clip.sampleRate
        val durationMs = endMs - startMs

        sb.append("=== ИНТЕРВАЛ ===\n")
        sb.append("Начало: ").append(TimeFormat.formatMs(startMs)).append("\n")
        sb.append("Конец: ").append(TimeFormat.formatMs(endMs)).append("\n")
        sb.append("Длительность: ").append(TimeFormat.formatMs(durationMs)).append("\n")
        sb.append("Метки: ")
            .append(if (startMarkerNum > 0) startMarkerNum.toString() else "—")
            .append(" → ")
            .append(if (endMarkerNum > 0) endMarkerNum.toString() else "—")
            .append("\n")
        sb.append("\n")

        sb.append("=== АНАЛИЗ СИГНАЛА ===\n")
        sb.append(String.format(Locale.US, "Peak: %.1f dB\n", info.peakDB))
        sb.append(String.format(Locale.US, "RMS:  %.1f dB\n", info.rmsDB))
        sb.append("Клиппинг: ").append(if (info.clipping) "ДА (!)" else "нет").append("\n")

        showDialog(context, "Инфо интервала", sb.toString())
    }

    /** G4: единый inflate. */
    private fun showDialog(context: Context, title: String, text: String) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_info, null)
        view.findViewById<TextView>(R.id.txtInfo).text = text
        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(view)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun buildInfoText(
        clip: AudioClip,
        fullInfo: AudioInfo,
        selInfo: AudioInfo?,
        selStart: Int,
        selEnd: Int,
    ): String {
        val sb = StringBuilder()

        if (selInfo != null) {
            val selMs = (selEnd - selStart) * 1000 / clip.sampleRate
            val mm = selMs / 60000
            val ss = (selMs % 60000) / 1000
            val rr = selMs % 1000

            sb.append("=== ВЫДЕЛЕНИЕ (")
                .append(String.format(Locale.US, "%02d:%02d.%03d", mm, ss, rr))
                .append(") ===\n")
            sb.append(String.format(Locale.US, "Peak: %.1f dB\n", selInfo.peakDB))
            sb.append(String.format(Locale.US, "RMS:  %.1f dB\n", selInfo.rmsDB))
            sb.append("Клиппинг: ").append(if (selInfo.clipping) "ДА (!)" else "нет").append("\n")
            sb.append("\n")
        }

        sb.append("=== ФАЙЛ ===\n")
        sb.append("Имя: ").append(clip.name).append("\n")
        val dir = clip.dir
        if (dir != null) {
            sb.append("Путь: ").append(dir.parent).append("\n")
            sb.append("Размер: ").append(String.format(Locale.US, "%.1f КБ", dir.length() / 1024.0))
                .append("\n")
        }
        sb.append("\n")

        sb.append("=== ПАРАМЕТРЫ ===\n")
        sb.append("Sample Rate: ").append(clip.sampleRate).append(" Гц\n")
        sb.append("Каналы: ").append(if (clip.channels == 2) "Stereo" else "Mono").append("\n")
        sb.append("Фреймов: ").append(String.format(Locale.US, "%,d", fullInfo.totalFrames))
            .append("\n")

        val totalMs = fullInfo.totalFrames * 1000 / clip.sampleRate
        val m = totalMs / 60000
        val s = (totalMs % 60000) / 1000
        val r = totalMs % 1000
        sb.append("Длительность: ").append(String.format(Locale.US, "%02d:%02d.%03d", m, s, r))
            .append("\n")
        sb.append("\n")

        sb.append("=== АНАЛИЗ СИГНАЛА (ВЕСЬ ФАЙЛ) ===\n")
        sb.append(String.format(Locale.US, "Peak: %.1f dB\n", fullInfo.peakDB))
        sb.append(String.format(Locale.US, "RMS:  %.1f dB\n", fullInfo.rmsDB))
        sb.append("Клиппинг: ").append(if (fullInfo.clipping) "ДА (!)" else "нет").append("\n")
        sb.append(String.format(Locale.US, "Баланс L/R: %+.1f dB\n", fullInfo.balanceLR))
        sb.append("Тишина в начале: ").append(fullInfo.silenceStartMs).append(" мс\n")
        sb.append("Тишина в конце: ").append(fullInfo.silenceEndMs).append(" мс\n")
        sb.append("\n")

        sb.append("=== МАРКЕРЫ ===\n")
        sb.append("Всего: ").append(clip.markers.size).append("\n")
        val show = min(10, clip.markers.size)
        for (i in 0 until show) {
            val markerFrame = clip.markers[i]
            val markerMs = markerFrame * 1000 / clip.sampleRate
            val mm = markerMs / 60000
            val ss = (markerMs % 60000) / 1000
            val rr = markerMs % 1000
            sb.append(String.format(Locale.US, "  %d: %02d:%02d.%03d\n", i + 1, mm, ss, rr))
        }
        if (clip.markers.size > 10) {
            sb.append("  ... и ещё ").append(clip.markers.size - 10).append("\n")
        }

        if (clip.exportIntervals.isNotEmpty()) {
            sb.append("=== ИНТЕРВАЛЫ ЭКСПОРТА ===\n")
            for (i in clip.exportIntervals.indices) {
                val interval = clip.exportIntervals[i]
                val startMs = interval[0] * 1000 / clip.sampleRate
                val endMs = interval[1] * 1000 / clip.sampleRate

                val mm1 = startMs / 60000
                val ss1 = (startMs % 60000) / 1000
                val rr1 = startMs % 1000
                val mm2 = endMs / 60000
                val ss2 = (endMs % 60000) / 1000
                val rr2 = endMs % 1000

                sb.append(
                    String.format(
                        Locale.US, "  %d: %02d:%02d.%03d – %02d:%02d.%03d\n",
                        i + 1, mm1, ss1, rr1, mm2, ss2, rr2,
                    )
                )
            }
            sb.append("\n")
        }

        if (dir != null) {
            val meta: AudioMeta = AudioMeta.load(dir)
            sb.append("=== ИСХОДНЫЙ ФАЙЛ ===\n")
            if (meta.sampleRate > 0 || meta.channels > 0) {
                if (meta.sampleRate > 0)
                    sb.append("Sample Rate: ").append(meta.sampleRate).append(" Гц\n")
                if (meta.channels > 0)
                    sb.append("Каналы: ").append(if (meta.channels == 1) "Mono" else "Stereo")
                        .append("\n")
                if (meta.bits > 0)
                    sb.append("Битность: ").append(meta.bits).append("-bit\n")
                val format = meta.format
                if (format != null && format.isNotEmpty())
                    sb.append("Формат: ").append(format).append("\n")
            } else {
                sb.append("Нет данных об исходнике\n")
            }
            sb.append("\n")
        }
        return sb.toString()
    }

}
