package com.csc.translate.pipeline

/**
 * What a stretch's decodes may change on screen. A partial shows only the prefix two decodes in a
 * row share, and never a shorter one. A sentence, or a two-line cut, stays as it was first shown.
 * The unfinished tail of a final waits, and the next stretch's words append to it.
 */
internal class Hypothesis {
    private var prefix = ""
    private var held = ""
    private var last: List<String>? = null
    private var agreed = emptyList<String>()
    var pending: String = ""
        private set

    val alignPrefix: String get() = prefix

    fun hasTail(): Boolean = held.any { it.isLetterOrDigit() }

    /** The next stretch's audio does not repeat this one's words. The held tail still does. */
    fun resetStretch() {
        last = null
        agreed = emptyList()
        prefix = ""
    }

    /** Empty until a second decode exists. The newest unmatched word waits for the one after. */
    fun settle(text: String): String {
        val cur = textPieces(text)
        val prev = last
        if (prev != null) {
            var n = 0
            val limit = minOf(prev.size, cur.size)
            while (n < limit && prev[n] == cur[n]) n++
            if (n >= agreed.size) agreed = cur.take(n)
        }
        last = cur
        return agreed.joinToString("")
    }

    /**
     * Fold [modelText] into locked lines and [pending]. [align] is the part of this stretch already
     * locked, which a later decode of the same audio repeats. A final that is not [quiet] leaves
     * its unfinished tail in [pending] instead of locking it.
     */
    fun absorb(modelText: String, align: String, final: Boolean, quiet: Boolean): List<String> {
        var h = held
        var t = alignedTail(align, modelText)
        var pfx = align
        val locked = mutableListOf<String>()

        fun take(n: Int) {
            val all = h + t
            if (n <= 0 || all.isEmpty()) return
            val end = minOf(n, all.length)
            val raw = all.substring(0, end)
            if (end <= h.length) {
                h = h.substring(end)
            } else {
                val into = end - h.length
                pfx += t.substring(0, into)
                h = ""
                t = t.substring(into)
            }
            val shown = raw.trim()
            if (shown.any { it.isLetterOrDigit() }) locked += shown
        }

        while (true) {
            val all = h + t
            val before = all.length
            val cut = nextSentence(all, requireAfter = !final)
            if (cut < 0) break
            if (textWidth(all.substring(0, cut)) > MAX_WIDTH) {
                val w = cutWithin(all)
                if (w < 0) break
                take(w)
            } else {
                take(cut)
            }
            if ((h + t).length >= before) break
        }
        while (textWidth(h + t) > MAX_WIDTH) {
            val before = (h + t).length
            val w = cutWithin(h + t)
            if (w < 0) break
            take(w)
            if ((h + t).length >= before) break
        }
        if (final && quiet) {
            take((h + t).length)
            held = ""
            pending = ""
        } else if (final) {
            held = (h + t).trim()
            pending = held
        } else {
            held = h
            pending = (h + t).trim()
            prefix = pfx
        }
        return locked
    }

    /** The speaker stayed quiet: the held tail is a line of its own. */
    fun commitHeld(): List<String> {
        val rest = held.trim()
        held = ""
        pending = ""
        if (!rest.any { it.isLetterOrDigit() }) return emptyList()
        return absorb(rest, align = "", final = true, quiet = true)
    }
}
