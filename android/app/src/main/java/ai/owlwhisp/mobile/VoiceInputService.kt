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
    private var panel: VoiceInputPanel? = null
    private var editorGeneration = 0L
    private var composingTarget: android.view.inputmethod.InputConnection? = null
    private var hasComposition = false
    private val observer: (AppState) -> Unit = { state ->
        panel?.render(state, app.model.name, app.model.present(), VoiceKeyboard.microphoneGranted(this), state.phase == Phase.RECORDING)
    }
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
                else {
                    val target = currentInputConnection
                    if (target != null) {
                        val generation = editorGeneration
                        composingTarget = target
                        fun update(text: String) {
                            if (generation == editorGeneration && isInputViewShown && text.isNotBlank()) {
                                if (runCatching { target.setComposingText(text, 1) }.getOrDefault(false)) hasComposition = true
                                else detachEditor()
                            }
                        }
                        app.start(this, onPartial = { update(it) }) { result ->
                            update(result)
                            if (generation == editorGeneration) runCatching { target.finishComposingText() }
                        }
                    }
                }
            },
            keyboard = {
                detachEditor()
                if (!switchToPreviousInputMethod()) getSystemService(InputMethodManager::class.java).showInputMethodPicker()
            },
            settings = {
                detachEditor()
                startActivity(Intent(this, MainActivity::class.java).putExtra("tab", 1).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        ).also { panel = it; observer(app.state) }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        detachEditor()
        super.onStartInput(attribute, restarting); observer(app.state)
    }
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting); observer(app.state); panel?.requestApplyInsets()
    }
    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (composingTarget == null || !app.isOwner(this)) return
        val moved = if (hasComposition) candidatesEnd < 0 || newSelStart != candidatesEnd || newSelEnd != candidatesEnd
            else newSelStart != oldSelStart || newSelEnd != oldSelEnd
        if (moved) detachEditor()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        detachEditor()
        super.onFinishInputView(finishingInput)
    }
    override fun dump(fd: FileDescriptor, fout: PrintWriter, args: Array<out String>) {
        super.dump(fd, fout, args)
        panel?.let { fout.println("OwlWhispPanel=" + it.layoutReport()) }
    }
    private fun detachEditor() {
        editorGeneration++
        runCatching { composingTarget?.finishComposingText() }
        composingTarget = null; hasComposition = false; app.detach(this)
    }
    override fun onDestroy() { detachEditor(); app.removeObserver(observer); panel = null; super.onDestroy() }
}
