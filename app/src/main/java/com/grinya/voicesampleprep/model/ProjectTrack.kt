package com.grinya.voicesampleprep.model

import java.io.File

class ProjectTrack(
    @JvmField var clip: AudioClip,
    @JvmField var startOffsetFrames: Int = 0,
    @JvmField var laneIndex: Int = 0,
    @JvmField var color: Int = 0xFFFFB300.toInt() // По умолчанию янтарный
) {
    /** Имя файла без расширения (например, "тест_9") */
    val displayName: String
        get() {
            val name = clip.name
            val dot = name.lastIndexOf('.')
            return if (dot > 0) name.substring(0, dot) else name
        }
}