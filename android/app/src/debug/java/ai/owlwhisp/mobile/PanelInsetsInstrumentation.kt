package ai.owlwhisp.mobile

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowInsets
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Debug-only host reproduces content behind system navigation and hosts that already inset it. */
class PanelPreviewActivity : Activity() {
    lateinit var panel: VoiceInputPanel
    private lateinit var frame: FrameLayout
    var hostReservesNavigation = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        frame = FrameLayout(this).apply { setBackgroundColor(Color.rgb(8, 10, 16)) }
        panel = VoiceInputPanel(this, window, {}, {}, {})
        frame.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        frame.setOnApplyWindowInsetsListener { view, insets ->
            val bottom = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.navigationBars()).bottom else insets.systemWindowInsetBottom
            view.setPadding(0, 0, 0, if (hostReservesNavigation) bottom else 0)
            insets
        }
        setContentView(frame)
    }
    fun show(reserved: Boolean, recording: Boolean) {
        hostReservesNavigation = reserved
        panel.render(AppState(if (recording) Phase.RECORDING else Phase.IDLE, "Ready"), "Parakeet TDT 0.6B v3", true, true, recording)
        frame.requestApplyInsets(); frame.requestLayout()
    }
}

/** Tests actual Android window/inset layout; it does not run or mock speech recognition. */
class PanelInsetsInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        var activity: PanelPreviewActivity? = null
        try {
            activity = startActivitySync(Intent(targetContext, PanelPreviewActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PanelPreviewActivity
            val host = activity
            val rows = JSONArray()
            for (reserved in listOf(false, true)) {
                for (recording in listOf(false, true)) {
                    runOnMainSync { host.show(reserved, recording) }
                    waitForIdleSync(); Thread.sleep(700); waitForIdleSync()
                    lateinit var report: JSONObject
                    var nav = 0
                    var density = 1f
                    runOnMainSync {
                        report = host.panel.layoutReport()
                        val insets = host.window.decorView.rootWindowInsets
                        nav = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.navigationBars()).bottom else insets.systemWindowInsetBottom
                        density = host.resources.displayMetrics.density
                    }
                    fun rect(key: String) = report.getString(key).split(',').map { it.toInt() }
                    val decor = rect("decor"); val safeBottom = decor[3] - nav
                    val mic = rect("microphone"); val keyboard = rect("keyboard"); val settings = rect("settings")
                    check(nav > 0) { "The navigation bar must be visible during this test" }
                    for (key in listOf("microphone", "keyboard", "settings")) {
                        val box = rect(key)
                        check(box[3] <= safeBottom && box[2] > box[0] && box[3] > box[1]) { "$key overlaps system navigation: $report" }
                        check(box[2] - box[0] >= (48 * density).toInt()) { "$key touch target is too small" }
                    }
                    check(mic[2] - mic[0] > keyboard[2] - keyboard[0])
                    check(keyboard[2] < mic[0] && mic[2] < settings[0])
                    check(kotlin.math.abs((mic[0] + mic[2]) - (decor[0] + decor[2])) <= 2)
                    val expectedPadding = (16 * density).toInt() + if (reserved) 0 else nav
                    check(report.getInt("bottom_padding") == expectedPadding) { "Navigation was double-counted or ignored: $report" }
                    val name = "panel-" + (if (reserved) "reserved" else "edge") + (if (recording) "-recording" else "-ready") + ".png"
                    uiAutomation.takeScreenshot().let { bitmap ->
                        File(targetContext.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                    rows.put(report.put("host_reserves_navigation", reserved).put("test_recording_state", recording))
                }
            }
            result.putString("layout", rows.toString())
            finish(Activity.RESULT_OK, result)
        } catch (failure: Throwable) {
            result.putString("failure", failure.stackTraceToString()); finish(Activity.RESULT_CANCELED, result)
        } finally { activity?.let { runOnMainSync { it.finish() } } }
    }
}
