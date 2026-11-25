package com.grinya.voicesampleprep.model

import java.io.File
import java.util.UUID

class AudioProject(
    @JvmField var name: String,
    @JvmField var dir: File? = null
) {
    @JvmField var id: String = UUID.randomUUID().toString()
    @JvmField var tracks: MutableList<ProjectTrack> = ArrayList()
    @JvmField var sampleRate: Int = 48000

    /** Длина всего проекта в фреймах (с учетом смещений). */
    fun totalFrames(): Int {
        if (tracks.isEmpty()) return 0
        return tracks.maxOf { it.startOffsetFrames + it.clip.frameCount() }
    }

    /** Количество уникальных Lane'ов (строк). */
    fun laneCount(): Int {
        if (tracks.isEmpty()) return 1
        return tracks.maxOf { it.laneIndex } + 1
    }

    /** Найти трек по клипу. */
    fun findTrack(clip: AudioClip?): ProjectTrack? {
        if (clip == null) return null
        return tracks.firstOrNull { it.clip === clip }
    }
}