package ai.owlwhisp.mobile

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONObject
import java.util.Locale

/** Compact voice IME controls; system navigation remains outside all touch targets. */
class VoiceInputPanel(
    context: Context,
    private val imeWindow: Window,
    dictate: () -> Unit,
    keyboard: () -> Unit,
    settings: () -> Unit,
    resume: () -> Unit = {}
) : LinearLayout(context) {
    private val headline = text(17f, Palette.text)
    private val detail = text(12f, Palette.dim)
    private val microphone = iconButton(R.drawable.ic_voice_microphone, "Dictate", 34)
    private val keyboardButton = iconButton(R.drawable.ic_voice_keyboard, "Return to keyboard", 23)
    private val settingsButton = iconButton(R.drawable.ic_voice_settings, "Voice keyboard setup", 23)
    private val microphoneLabel = text(13f, Palette.accent)
    private val resumeButton = text(14f, Palette.accent).apply {
        text = "Resume insertion"; minHeight = dp(44); isClickable = true; isFocusable = true
        background = ripple(circle(intArrayOf(Palette.raised))); visibility = GONE
        setOnClickListener { resume() }
    }
    private val halo = FrameLayout(context)
    private val working = ProgressBar(context).apply { indeterminateTintList = ColorStateList.valueOf(Palette.accent); visibility = GONE }
    private var recording = false
    private var startedAt = 0L
    private var latestInsets: WindowInsets? = null
    private var navBottom = 0
    private val updateInsets = Runnable { protectNavigation() }
    private val updateClock = object : Runnable {
        override fun run() {
            if (!recording || !isAttachedToWindow) return
            val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000
            headline.text = String.format(Locale.ROOT, "Listening %d:%02d", seconds / 60, seconds % 60)
            postDelayed(this, 1000)
        }
    }

    init {
        orientation = VERTICAL
        setPadding(dp(20), dp(14), dp(20), dp(16))
        background = GradientDrawable().apply {
            setColor(Palette.bg)
            cornerRadii = floatArrayOf(dp(24).toFloat(), dp(24).toFloat(), dp(24).toFloat(), dp(24).toFloat(), 0f, 0f, 0f, 0f)
            setStroke(dp(1), Color.rgb(42, 48, 63))
        }
        val brand = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        brand.addView(ImageView(context).apply { setImageResource(R.drawable.owlwhisp); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }, LayoutParams(dp(24), dp(24)))
        brand.addView(text(13f, Palette.text).apply { text = "OwlWhisp"; gravity = Gravity.START or Gravity.CENTER_VERTICAL; setTypeface(null, Typeface.BOLD); setPadding(dp(8), 0, 0, 0) }, LayoutParams(0, dp(28), 1f))
        addView(brand, LayoutParams(-1, dp(28)))
        headline.apply { setPadding(0, dp(8), 0, dp(2)); accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE }
        addView(headline, LayoutParams(-1, -2))
        detail.maxLines = 2
        addView(detail, LayoutParams(-1, -2))
        addView(resumeButton, LayoutParams(-1, dp(44)))
        val controls = LinearLayout(context).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) }
        fun side(button: ImageButton, title: String) = LinearLayout(context).apply {
            orientation = VERTICAL; gravity = Gravity.CENTER
            addView(button, LayoutParams(dp(48), dp(48)))
            addView(text(11f, Palette.dim).apply { text = title; setPadding(0, dp(8), 0, 0); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }, LayoutParams(-1, -2))
        }
        controls.addView(side(keyboardButton, "Keyboard"), LayoutParams(0, -2, 1f))
        val center = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.CENTER }
        halo.background = circle(intArrayOf(Color.rgb(24, 45, 65)), Color.rgb(45, 73, 97))
        halo.addView(microphone, FrameLayout.LayoutParams(dp(92), dp(92), Gravity.CENTER))
        halo.addView(working, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
        center.addView(halo, LayoutParams(dp(108), dp(108)))
        center.addView(microphoneLabel.apply { setPadding(0, dp(6), 0, 0); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }, LayoutParams(-1, -2))
        controls.addView(center, LayoutParams(dp(136), -2))
        controls.addView(side(settingsButton, "Settings"), LayoutParams(0, -2, 1f))
        addView(controls, LayoutParams(-1, -2))
        microphone.setOnClickListener { dictate() }
        keyboardButton.setOnClickListener { keyboard() }
        settingsButton.setOnClickListener { settings() }
        setOnApplyWindowInsetsListener { _, insets -> latestInsets = insets; removeCallbacks(updateInsets); post(updateInsets); insets }
    }

    fun render(state: AppState, modelName: String, modelReady: Boolean, microphoneAllowed: Boolean, ownsRecording: Boolean, deliveryHint: String? = null, canResume: Boolean = false, liveText: Boolean = true) {
        val listening = state.phase == Phase.RECORDING && ownsRecording
        val busy = state.phase in setOf(Phase.PROCESSING, Phase.DOWNLOADING, Phase.BENCHMARK) || (state.phase == Phase.RECORDING && !ownsRecording)
        val ready = modelReady && microphoneAllowed
        if (listening != recording) {
            recording = listening
            removeCallbacks(updateClock)
            if (listening) { startedAt = (context.applicationContext as? OwlWhispApp)?.recordingStartedAt?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(); post(updateClock) }
        }
        headline.text = when {
            listening -> "Listening"
            busy -> "Finishing dictation"
            !ready -> "Set up your voice input"
            state.phase == Phase.ERROR -> "Try again"
            else -> "Ready to dictate"
        }
        resumeButton.visibility = if (canResume) VISIBLE else GONE
        detail.text = when {
            deliveryHint != null -> deliveryHint
            listening && !liveText -> "Text will be inserted after Stop."
            listening -> state.transcript.takeLast(180).ifBlank { "Speak. Text appears as you dictate." }
            !microphoneAllowed -> "Allow the microphone in Settings"
            !modelReady -> "Download a model in Settings"
            busy || state.phase == Phase.ERROR -> state.message
            else -> modelName
        }
        microphone.isEnabled = ready && (!busy || listening)
        microphone.contentDescription = if (listening) "Stop dictation" else "Dictate"
        microphoneLabel.text = when { listening -> "Stop dictation"; busy -> "Working"; else -> "Dictate" }
        working.visibility = if (busy) VISIBLE else GONE
        microphone.setImageResource(if (listening) R.drawable.ic_voice_stop else R.drawable.ic_voice_microphone)
        microphone.imageAlpha = if (busy) 0 else 255
        val colors = when {
            listening -> intArrayOf(Color.rgb(255, 159, 169), Color.rgb(226, 102, 132))
            ready && !busy -> intArrayOf(Color.rgb(117, 224, 226), Color.rgb(123, 158, 255))
            else -> intArrayOf(Color.rgb(63, 85, 112), Color.rgb(54, 69, 96))
        }
        microphone.background = ripple(circle(colors))
        microphone.imageTintList = ColorStateList.valueOf(if (ready && !busy) Color.rgb(15, 30, 47) else Color.rgb(163, 184, 210))
        microphoneLabel.setTextColor(if (listening) Color.rgb(255, 169, 185) else Palette.accent)
    }

    private fun text(size: Float, color: Int) = TextView(context).apply {
        textSize = size; setTextColor(color); gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }
    private fun iconButton(icon: Int, description: String, iconSize: Int) = ImageButton(context).apply {
        setImageResource(icon); contentDescription = description; scaleType = ImageView.ScaleType.FIT_CENTER
        // Fixed icon bounds inside a 48 dp or larger circular touch target.
        val inset = if (iconSize > 24) dp((92 - iconSize) / 2) else dp((48 - iconSize) / 2)
        setPadding(inset, inset, inset, inset)
        imageTintList = ColorStateList.valueOf(Palette.text)
        background = ripple(circle(intArrayOf(Palette.raised), Color.rgb(52, 59, 77)))
        isHapticFeedbackEnabled = true
        if (Build.VERSION.SDK_INT >= 26) tooltipText = description
    }
    private fun circle(colors: IntArray, border: Int? = null) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        if (colors.size > 1) { orientation = GradientDrawable.Orientation.TL_BR; this.colors = colors } else setColor(colors[0])
        border?.let { setStroke(dp(1), it) }
    }
    private fun ripple(background: GradientDrawable) = RippleDrawable(ColorStateList.valueOf(0x30FFFFFF), background, circle(intArrayOf(Color.WHITE)))

    override fun onAttachedToWindow() { super.onAttachedToWindow(); requestApplyInsets(); post(updateInsets) }
    override fun onDetachedFromWindow() { removeCallbacks(updateClock); removeCallbacks(updateInsets); super.onDetachedFromWindow() }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // Padding changes must schedule a fresh measure, outside the current layout pass.
        removeCallbacks(updateInsets); post(updateInsets)
    }

    private fun protectNavigation() {
        val decor = imeWindow.decorView
        val insets = decor.rootWindowInsets ?: latestInsets ?: return
        if (width == 0 || height == 0 || decor.height == 0) return
        val bars = if (Build.VERSION.SDK_INT >= 30) {
            val value = insets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout())
            intArrayOf(value.left, value.right, value.bottom)
        } else intArrayOf(maxOf(insets.systemWindowInsetLeft, insets.displayCutout?.safeInsetLeft ?: 0),
            maxOf(insets.systemWindowInsetRight, insets.displayCutout?.safeInsetRight ?: 0), insets.systemWindowInsetBottom)
        val leftInset = bars[0]
        val rightInset = bars[1]
        navBottom = bars[2]
        val location = IntArray(2); getLocationInWindow(location)
        // Some IME hosts already reserve the navigation bar; add only the actual overlap.
        val safeLeft = dp(20) + maxOf(0, leftInset - location[0])
        val safeRight = dp(20) + maxOf(0, location[0] + width - (decor.width - rightInset))
        val safeBottom = dp(16) + maxOf(0, location[1] + height - (decor.height - navBottom))
        if (paddingLeft != safeLeft || paddingRight != safeRight || paddingBottom != safeBottom) {
            setPadding(safeLeft, dp(14), safeRight, safeBottom)
        }
    }

    /** Geometry only: useful for OEM inset reports, without audio or transcript content. */
    fun layoutReport(): JSONObject {
        fun bounds(view: View): String {
            val p = IntArray(2); view.getLocationOnScreen(p)
            return "${p[0]},${p[1]},${p[0] + view.width},${p[1] + view.height}"
        }
        return JSONObject().put("panel", bounds(this)).put("microphone", bounds(microphone))
            .put("keyboard", bounds(keyboardButton)).put("settings", bounds(settingsButton))
            .put("navigation_bottom", navBottom).put("bottom_padding", paddingBottom)
            .put("decor", bounds(imeWindow.decorView)).put("recording", recording)
            .put("microphone_enabled", microphone.isEnabled).put("resume_visible", resumeButton.visibility == VISIBLE).put("resume", bounds(resumeButton))
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
