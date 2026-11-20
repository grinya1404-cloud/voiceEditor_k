package com.grinya.voicesampleprep.model

import java.io.File

class Bank {
    @JvmField var name: String = ""
    @JvmField var dir: File? = null
    @JvmField var clips: MutableList<AudioClip> = ArrayList()
}
