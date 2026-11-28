package com.grinya.voicesampleprep.audio

import android.util.Log
import com.grinya.voicesampleprep.model.AudioProject
import kotlin.math.max

class MultitrackPlayer {
    private val player = AudioPlayer()
    private var isPrepared = false

    fun setListener(l: AudioPlayer.Listener) {
        player.setListener(l)
    }

    fun prepare(project: AudioProject) {
        val totalFrames = project.totalFrames()
        if (totalFrames <= 0) return

        val mixed = FloatArray(totalFrames * 2)

        // Проверяем, есть ли Solo треки
        val hasSolo = project.tracks.any { it.isSolo }

        for (track in project.tracks) {
            // Если включен Solo у кого-то, играем только Solo треки
            // Если Solo ни у кого нет, играем все, кроме Muted
            if (hasSolo && !track.isSolo) continue
            if (track.isMuted && !track.isSolo) continue

            val pcm = track.clip.pcm
            val start = track.startOffsetFrames
            val length = pcm.size

            for (i in 0 until length step 2) {
                val destIdx = (start * 2) + i
                if (destIdx + 1 < mixed.size) {
                    mixed[destIdx] += pcm[i]
                    mixed[destIdx + 1] += pcm[i + 1]
                }
            }
        }

        // Нормализация
        var maxAmp = 0f
        for (v in mixed) {
            val a = kotlin.math.abs(v)
            if (a > maxAmp) maxAmp = a
        }
        if (maxAmp > 1.0f) {
            val mul = 1.0f / maxAmp
            for (i in mixed.indices) mixed[i] *= mul
        }

        player.setPcm(mixed)
        player.setPlayRange(0, totalFrames)
        isPrepared = true
    }

    fun play() {
        if (isPrepared) player.play()
    }

    fun pause() {
        player.pause()
    }

    fun stop() {
        player.stop()
    }

    fun seekToFrame(frame: Int) {
        player.seekToFrame(frame)
    }

    fun getCurrentFrame(): Int = player.getCurrentFrame()
    fun isPlaying(): Boolean = player.isPlaying()
    fun release() = player.release()
}
