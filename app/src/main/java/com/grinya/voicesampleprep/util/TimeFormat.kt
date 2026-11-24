package com.grinya.voicesampleprep.util

import java.util.Locale

object TimeFormat {
    @JvmStatic
    fun formatMs(ms: Int): String {
        val m = ms / 60000
        val s = (ms % 60000) / 1000
        val r = ms % 1000
        return String.format(Locale.US, "%02d:%02d.%03d", m, s, r)
    }

    @JvmStatic
    fun formatMsShort(ms: Int): String {
        val m = ms / 60000
        val s = (ms % 60000) / 1000
        return String.format(Locale.US, "%02d:%02d", m, s)
    }
}