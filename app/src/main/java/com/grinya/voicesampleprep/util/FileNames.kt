package com.grinya.voicesampleprep.util

object FileNames {

    @JvmStatic
    fun sanitize(name: String?): String {
        val base: String = name?.takeIf { it.trim().isNotEmpty() } ?: "untitled"
        var s = base.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim()
        while (s.endsWith(".")) s = s.substring(0, s.length - 1)
        if (s.length > 120) s = s.substring(0, 120)
        return if (s.isEmpty()) "untitled" else s
    }
}
