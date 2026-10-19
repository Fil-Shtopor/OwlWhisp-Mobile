package ai.owlwhisp.mobile

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class VoiceInputService : InputMethodService() {
    private val app get() = application as OwlWhispApp
    private var status: TextView? = null
    private var microphone: Button? = null
    private var editorGeneration = 0L
    private val observer: (AppState) -> Unit = { state ->
        status?.text = when {
            !VoiceKeyboard.microphoneGranted(this) -> "Allow microphone access in Voice keyboard setup."
            !app.model.present() -> "Download a model in Voice keyboard setup."
            else -> state.message
        }
        microphone?.isEnabled = VoiceKeyboard.microphoneGranted(this) && app.model.present() && (state.phase in setOf(Phase.IDLE, Phase.ERROR) || (state.phase == Phase.RECORDING && app.isOwner(this)))
        microphone?.text = if (state.phase == Phase.RECORDING && app.isOwner(this)) "Stop and insert" else "Dictate"
    }
    override fun onCreate() { super.onCreate(); app.observe(observer) }
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Palette.bg)
        }
        status = TextView(this).apply { textSize = 16f; setTextColor(Palette.text) }
        root.addView(status)
        microphone = Button(this).apply {
            minHeight = (72 * resources.displayMetrics.density).toInt()
            setOnClickListener {
                if (app.state.phase == Phase.RECORDING && app.isOwner(this@VoiceInputService)) app.stop(this@VoiceInputService)
                else {
                    val target = currentInputConnection ?: return@setOnClickListener
                    val generation = editorGeneration
                    app.start(this@VoiceInputService) { result ->
                        if (generation == editorGeneration && isInputViewShown && result.isNotBlank()) target.commitText(result, 1)
                    }
                }
            }
        }
        root.addView(microphone)
        root.addView(Button(this).apply {
            text = "Return to keyboard"
            setOnClickListener {
                app.cancel(this@VoiceInputService)
                if (!switchToPreviousInputMethod()) getSystemService(InputMethodManager::class.java).showInputMethodPicker()
            }
        })
        root.addView(Button(this).apply {
            text = "Voice keyboard setup"
            setOnClickListener {
                app.cancel(this@VoiceInputService)
                startActivity(Intent(this@VoiceInputService, KeyboardSetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        })
        observer(app.state)
        return root
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        editorGeneration++; app.cancel(this)
        super.onStartInput(attribute, restarting)
        observer(app.state)
    }
    override fun onFinishInputView(finishingInput: Boolean) {
        editorGeneration++; app.cancel(this)
        super.onFinishInputView(finishingInput)
    }
    override fun onDestroy() { app.cancel(this); app.removeObserver(observer); super.onDestroy() }
}
