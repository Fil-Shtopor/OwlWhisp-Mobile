package ai.owlwhisp.mobile

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.View
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import java.io.FileDescriptor
import java.io.PrintWriter

class VoiceInputService : InputMethodService() {
    private val app get() = application as OwlWhispApp
    private val delivery get() = app.editorDelivery
    private var panel: VoiceInputPanel? = null
    private var editorVisible = false
    private var delivering = false
    private val observer: (AppState) -> Unit = { render() }
    private fun identity() = currentInputEditorInfo?.let(EditorIdentity::from)

    override fun onCreate() {
        super.onCreate()
        window?.window?.apply {
            navigationBarColor = Palette.bg
            if (Build.VERSION.SDK_INT >= 29) isNavigationBarContrastEnforced = false
            if (Build.VERSION.SDK_INT >= 30) insetsController?.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
            else decorView.systemUiVisibility = decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        }
        app.observe(observer)
    }
    override fun onEvaluateFullscreenMode(): Boolean = false
    override fun onCreateInputView(): View {
        return VoiceInputPanel(this, checkNotNull(window.window),
            dictate = {
                if (app.state.phase == Phase.RECORDING) app.stopCurrent()
                else if (!app.busy) {
                    val connection = currentInputConnection
                    val target = identity()
                    if (connection != null && target != null) {
                        val snapshot = EditorAnchor.read(connection)
                        app.start(delivery, onPartial = { delivery.offer(it) }) { delivery.finish(it) }
                        if (app.isOwner(delivery)) delivery.begin(target, snapshot, app.liveTextInsertion)
                        render()
                    }
                }
            },
            keyboard = {
                pauseDelivery()
                if (!switchToPreviousInputMethod()) getSystemService(InputMethodManager::class.java).showInputMethodPicker()
            },
            settings = {
                pauseDelivery()
                startActivity(Intent(this, MainActivity::class.java).putExtra("tab", 1).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            language = { anchor ->
                if (!app.busy) android.widget.PopupMenu(this, anchor).apply {
                    app.languageAssignments.toSortedMap().forEach { (language, id) ->
                        val repository = app.models.firstOrNull { it.id == id && it.supported && language in it.languages }
                        if (repository != null) {
                            menu.add(ModelGuide.languageName(language) + " - " + app.guide.name(id) +
                                if (repository.present()) "" else " (download first)").apply {
                                isCheckable = true; isChecked = app.routingLanguage == language
                                setOnMenuItemClickListener { app.chooseLanguage(language); true }
                            }
                        }
                    }
                    show()
                }
            },
            resume = {
                if (delivery.confirm(identity(), currentInputConnection?.let(EditorAnchor::read))) render()
            }
        ).also { panel = it; render() }
    }

    private fun syncDelivery() {
        if (delivering || !editorVisible || !isInputViewShown || !delivery.active) return
        val connection = currentInputConnection ?: return
        val target = identity()
        if (target != delivery.target) { delivery.suspend(); return }
        val snapshot = EditorAnchor.read(connection)
        if (delivery.suspended && !delivery.tryResume(target, snapshot)) return
        if (target != delivery.target || (delivery.anchor != null && !delivery.matches(target, snapshot))) {
            delivery.suspend(); return
        }
        val text = delivery.pending
        if (text.isEmpty()) return
        delivering = true
        try {
            if (connection.commitText(text, 1)) delivery.committed(text)
            else delivery.suspend(true)
        } finally { delivering = false }
    }
    private fun render() {
        syncDelivery()
        val waiting = delivery.active && delivery.suspended
        val same = identity() == delivery.target
        val hint = when {
            waiting && same -> "Insertion paused. Resume in this field, or find the original field."
            waiting -> "Recording text is kept for " + (delivery.target?.packageName ?: "the original app")
            !app.liveTextInsertion && app.state.phase == Phase.RECORDING -> "Text will be inserted after Stop."
            else -> null
        }
        panel?.render(app.state, app.model.name, app.model.present(), VoiceKeyboard.microphoneGranted(this),
            app.state.phase == Phase.RECORDING, hint, waiting && same, app.liveTextInsertion,
            if (app.routingEnabled) app.routingLanguage.uppercase(java.util.Locale.ROOT) + " \u2304" else null)
    }
    private fun pauseDelivery() { delivery.suspend() }
    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        editorVisible = false; pauseDelivery()
        super.onStartInput(attribute, restarting)
    }
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting); editorVisible = true
        app.main.post { render(); panel?.requestApplyInsets() }
    }
    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        val anchor = delivery.anchor
        if (!delivering && editorVisible && delivery.active && !delivery.suspended && identity() == delivery.target && anchor != null &&
            (newSelStart != anchor.start || newSelEnd != anchor.end)) {
            // Validate again on the next result; selection notifications can lag our own commit.
            delivery.suspend()
        }
    }
    override fun onFinishInputView(finishingInput: Boolean) {
        editorVisible = false; pauseDelivery()
        super.onFinishInputView(finishingInput)
    }
    override fun onFinishInput() { editorVisible = false; pauseDelivery(); super.onFinishInput() }
    override fun dump(fd: FileDescriptor, fout: PrintWriter, args: Array<out String>) {
        super.dump(fd, fout, args)
        panel?.let { fout.println("OwlWhispPanel=" + it.layoutReport()) }
    }
    override fun onDestroy() {
        editorVisible = false; pauseDelivery(); app.removeObserver(observer); panel = null; super.onDestroy()
    }
}
