package com.grinya.voicesampleprep.edit

import com.grinya.voicesampleprep.model.AudioClip

object EditActivityHolder {
    @JvmField
    var current: AudioClip? = null

    @JvmStatic
    fun clear() {
        current = null
    }
}
