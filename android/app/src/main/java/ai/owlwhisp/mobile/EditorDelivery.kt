package ai.owlwhisp.mobile

import android.os.Build
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

/** An editor fingerprint is checked together with the saved cursor context, never alone. */
data class EditorIdentity(val packageName: String, val fieldId: Int, val fieldName: String?, val inputType: Int,
    val imeOptions: Int, val privateOptions: String?, val hint: String?) {
    companion object {
        fun from(info: EditorInfo) = EditorIdentity(info.packageName.orEmpty(), info.fieldId, info.fieldName,
            info.inputType, info.imeOptions, info.privateImeOptions, info.hintText?.toString())
    }
}

data class EditorAnchor(val before: String, val selected: String, val after: String, val start: Int, val end: Int) {
    // Empty or very short contexts cannot distinguish chats sharing the same editor widget.
    val distinctive get() = (before + selected + after).count { it.isLetterOrDigit() } >= 12
    fun inserting(text: String) = EditorAnchor((before + text).takeLast(128), "", after, start + text.length, start + text.length)
    companion object {
        fun read(connection: InputConnection): EditorAnchor? = runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                connection.getSurroundingText(128, 128, 0)?.let { value ->
                    val text = value.text.toString()
                    val start = value.selectionStart; val end = value.selectionEnd
                    if (value.offset >= 0 && start in 0..end && end <= text.length && end - start <= 4096)
                        return@runCatching EditorAnchor(text.substring(0, start).takeLast(128), text.substring(start, end),
                            text.substring(end).take(128), value.offset + start, value.offset + end)
                }
            }
            val value = connection.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 4096; hintMaxLines = 100 }, 0)
                ?: return@runCatching null
            val text = value.text?.toString() ?: return@runCatching null
            val start = value.selectionStart; val end = value.selectionEnd
            if (start !in 0..end || end > text.length || end - start > 4096) return@runCatching null
            EditorAnchor(text.substring(0, start).takeLast(128), text.substring(start, end), text.substring(end).take(128),
                value.startOffset + start, value.startOffset + end)
        }.getOrNull()
    }
}

/** Main-thread, in-memory delivery state. No InputConnection survives a focus change. */
class EditorDelivery {
    var target: EditorIdentity? = null
        private set
    var anchor: EditorAnchor? = null
        private set
    var latest = ""
        private set
    var inserted = ""
        private set
    var finished = false
        private set
    var live = true
        private set
    var suspended = true
        private set
    private var confirmationRequired = false
    val active get() = target != null && (!finished || latest.length > inserted.length)
    val pending get() = if ((live || finished) && latest.startsWith(inserted)) latest.substring(inserted.length) else ""

    fun begin(identity: EditorIdentity, snapshot: EditorAnchor?, live: Boolean) {
        clear(); target = identity; anchor = snapshot; this.live = live; suspended = false
    }
    fun clear() { target = null; anchor = null; latest = ""; inserted = ""; finished = false; live = true; suspended = true; confirmationRequired = false }
    fun offer(text: String) {
        latest = text
        if (!text.startsWith(inserted)) suspend(true)
    }
    fun finish(text: String) { offer(text); finished = true }
    fun suspend(confirm: Boolean = false) { suspended = true; confirmationRequired = confirmationRequired || confirm }
    fun matches(identity: EditorIdentity?, snapshot: EditorAnchor?) = target == identity && anchor != null && anchor == snapshot
    fun tryResume(identity: EditorIdentity?, snapshot: EditorAnchor?): Boolean {
        if (!active || confirmationRequired || anchor?.distinctive != true || !matches(identity, snapshot)) return false
        suspended = false; return true
    }
    fun confirm(identity: EditorIdentity?, snapshot: EditorAnchor?): Boolean {
        if (!active || target != identity || identity == null) return false
        anchor = snapshot; confirmationRequired = false; suspended = false; return true
    }
    fun committed(text: String) {
        check(text == pending)
        inserted += text
        anchor = anchor?.inserting(text)
    }
}
