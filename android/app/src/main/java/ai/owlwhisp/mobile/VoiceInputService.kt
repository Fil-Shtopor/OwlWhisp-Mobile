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
    private val observer: (AppState) -> Unit = { state ->
        panel?.render(state, app.model.name, app.model.present(), VoiceKeyboard.microphoneGranted(this), app.isOwner(this))
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
                if (app.state.phase == Phase.RECORDING && app.isOwner(this)) app.stop(this)
                else {
                    val target = currentInputConnection
                    if (target != null) {
                        val generation = editorGeneration
                        app.start(this) { result ->
                            if (generation == editorGeneration && isInputViewShown && result.isNotBlank()) target.commitText(result, 1)
                        }
                    }
                }
            },
            keyboard = {
                app.cancel(this)
                if (!switchToPreviousInputMethod()) getSystemService(InputMethodManager::class.java).showInputMethodPicker()
            },
            settings = {
                app.cancel(this)
                startActivity(Intent(this, KeyboardSetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        ).also { panel = it; observer(app.state) }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        editorGeneration++; app.cancel(this)
        super.onStartInput(attribute, restarting); observer(app.state)
    }
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting); observer(app.state); panel?.requestApplyInsets()
    }
    override fun onFinishInputView(finishingInput: Boolean) {
        editorGeneration++; app.cancel(this)
        super.onFinishInputView(finishingInput)
    }
    override fun dump(fd: FileDescriptor, fout: PrintWriter, args: Array<out String>) {
        super.dump(fd, fout, args)
        panel?.let { fout.println("OwlWhispPanel=" + it.layoutReport()) }
    }
    override fun onDestroy() { app.cancel(this); app.removeObserver(observer); panel = null; super.onDestroy() }
}
