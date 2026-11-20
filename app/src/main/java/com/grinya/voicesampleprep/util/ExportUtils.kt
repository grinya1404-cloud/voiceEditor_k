package com.grinya.voicesampleprep.util

import com.grinya.voicesampleprep.model.AudioClip
import java.util.Locale

object ExportUtils {

    @JvmStatic
    fun buildRanges(
        clip: AudioClip,
        byMarkers: Boolean,
        selStart: Int,
        selEnd: Int,
    ): MutableList<IntArray> {
        val ranges = ArrayList<IntArray>()
        if (byMarkers) {
            if (clip.exportIntervals.isNotEmpty()) {
                for (i in clip.exportIntervals) {
                    ranges.add(intArrayOf(i[0], i[1]))
                }
                return ranges
            }

            if (clip.markers.size < 2) return ranges
            // D9: markers уже отсортированы (Collections.sort в addMarker)
            for (i in 0 until clip.markers.size - 1) {
                val a = clip.markers[i]
                val b = clip.markers[i + 1]
                if (b > a) ranges.add(intArrayOf(a, b))
            }
        } else {
            if (selStart >= 0 && selEnd > selStart) {
                ranges.add(intArrayOf(selStart, selEnd))
            } else {
                ranges.add(intArrayOf(0, clip.frameCount()))
            }
        }
        return ranges
    }

    @JvmStatic
    fun buildName(template: String, base: String, index: Int): String {
        val out = template.replace("{name}", base)
            .replace("{index}", String.format(Locale.US, "%03d", index))
        return sanitize(out)
    }

    @JvmStatic
    fun stripExt(s: String?): String {
        if (s == null) return "clip"
        val dot = s.lastIndexOf('.')
        return if (dot > 0) s.substring(0, dot) else s
    }

    /** D8: единый sanitize. */
    @JvmStatic
    fun sanitize(s: String?): String = FileNames.sanitize(s)
}
