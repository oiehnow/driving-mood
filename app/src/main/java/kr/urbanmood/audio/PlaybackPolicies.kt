package kr.urbanmood.audio

/** IDs only: exercised on the JVM without mocking Android media playback. */
internal class QueuePolicy {
    private val history = ArrayDeque<String>()
    fun choose(ids: List<String>, current: String?): String? {
        val unique = ids.distinct()
        if (unique.isEmpty()) return null
        val alternatives = unique.filter { it != current }.ifEmpty { unique }
        return alternatives.firstOrNull { it !in history }
            ?: alternatives.minBy { history.indexOf(it) }
    }
    fun played(id: String) { history.remove(id); history.addLast(id); while (history.size > 50) history.removeFirst() }
}

internal class PlaybackIntent {
    var wantsPlay = false; private set
    var resumeAfterFocus = false; private set
    fun play() { wantsPlay = true; resumeAfterFocus = false }
    fun pause() { wantsPlay = false; resumeAfterFocus = false }
    fun loseFocus(transient: Boolean) { resumeAfterFocus = transient && (wantsPlay || resumeAfterFocus); wantsPlay = false }
    fun gainFocus(): Boolean {
        if (!resumeAfterFocus) return false
        wantsPlay = true; resumeAfterFocus = false
        return true
    }
}
