package ai.owlwhisp.mobile

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.WindowInsets
import android.os.Build
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

object VoiceKeyboard {
    fun enabled(context: Context) = context.getSystemService(InputMethodManager::class.java)
        .enabledInputMethodList.any { it.packageName == context.packageName }
    fun selected(context: Context): Boolean {
        val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        return id?.substringBefore('/') == context.packageName
    }
    fun microphoneGranted(context: Context) = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}

/** Used both by the app and the system IME settings shortcut. Never starts recording. */
class KeyboardSetupActivity : Activity() {
    private val app get() = application as OwlWhispApp
    private lateinit var modelStatus: TextView
    private lateinit var microphoneStatus: TextView
    private lateinit var keyboardStatus: TextView
    private lateinit var grantMicrophone: Button
    private lateinit var chooseKeyboard: Button
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24)); setBackgroundColor(Palette.bg)
        }
        fun label(value: String, size: Float = 16f) = TextView(this).apply {
            text = value; textSize = size; setTextColor(Palette.text); setPadding(0, dp(10), 0, dp(10))
        }.also { content.addView(it) }
        fun button(value: String, action: () -> Unit) = Button(this).apply {
            text = value; isAllCaps = false; setTextColor(Palette.accent); setOnClickListener { action() }
        }.also { content.addView(it, LinearLayout.LayoutParams(-1, -2)) }
        label("Voice keyboard setup", 26f)
        label("Use OwlWhisp to dictate into a text field in another app. Return to your typing keyboard with the Return to keyboard button.")
        modelStatus = label("")
        button("1. Download or choose a model") { openMain(2) }
        microphoneStatus = label("")
        grantMicrophone = button("2. Allow microphone access") {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
        }
        button("Open app permissions") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        keyboardStatus = label("")
        button("3. Enable OwlWhisp in keyboard settings") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        chooseKeyboard = button("4. Choose OwlWhisp") {
            getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        }
        label("Samsung: Settings > General management > Keyboard list and default. Enable OwlWhisp and enable the keyboard button on the navigation bar. While a text field is focused, use that keyboard button to switch to OwlWhisp.")
        label("Samsung Keyboard's Voice input page may offer only Samsung and Google. Choose OwlWhisp from the system keyboard list instead.")
        label("Try it here")
        content.addView(EditText(this).apply {
            hint = "Tap here, then switch to OwlWhisp"; setHintTextColor(Palette.dim); setTextColor(Palette.text)
            minLines = 3; gravity = Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }, LinearLayout.LayoutParams(-1, dp(130)))
        button("Back to dictation") { openMain(0) }
        val scroll = ScrollView(this).apply { setBackgroundColor(Palette.bg); addView(content) }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(0, bars.top, 0, bars.bottom)
            } else view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContentView(scroll); refresh()
    }
    private fun openMain(tab: Int) {
        startActivity(Intent(this, MainActivity::class.java).putExtra("tab", tab)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
    private fun refresh() {
        if (!::modelStatus.isInitialized) return
        modelStatus.text = "Model: ${app.model.name} - " + if (app.model.present()) "downloaded" else "download required"
        val allowed = VoiceKeyboard.microphoneGranted(this)
        microphoneStatus.text = "Microphone: " + if (allowed) "allowed" else "permission required"
        grantMicrophone.isEnabled = !allowed
        val enabled = VoiceKeyboard.enabled(this)
        keyboardStatus.text = "Keyboard: " + when {
            VoiceKeyboard.selected(this) -> "enabled and selected"
            enabled -> "enabled; choose OwlWhisp to dictate"
            else -> "not enabled"
        }
        chooseKeyboard.isEnabled = enabled
    }
    override fun onResume() { super.onResume(); refresh() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) refresh() }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults); refresh()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
