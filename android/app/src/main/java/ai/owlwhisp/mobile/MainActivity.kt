package ai.owlwhisp.mobile

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.*
import android.provider.Settings
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import java.io.File
import java.util.Locale

object Palette {
    val bg = Color.rgb(17, 19, 26)
    val raised = Color.rgb(26, 29, 39)
    val inset = Color.rgb(12, 14, 20)
    val text = Color.rgb(229, 231, 238)
    val dim = Color.rgb(154, 160, 176)
    val accent = Color.rgb(137, 180, 250)
}

class MainActivity : Activity() {
    private val app get() = application as OwlWhispApp
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var content: LinearLayout
    private var transcript: EditText? = null
    private var record: Button? = null
    private var cancelRecord: Button? = null
    private var liveInsertionSwitch: Switch? = null
    private var savedText = ""
    private var currentTab = 0
    private val actions = mutableListOf<Button>()
    private val tabs = listOf("Dictate", "Settings", "Models", "Benchmark", "Diagnostics")
    private var wasBusy = false
    private val observer: (AppState) -> Unit = {
        val finished = wasBusy && !app.busy
        wasBusy = app.busy
        if (finished && currentTab in 2..4) showTab(currentTab) else render(it)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedText = savedInstanceState?.getString("transcript").orEmpty()
        if (app.draftText.isEmpty()) app.draftText = savedText
        currentTab = savedInstanceState?.getInt("tab") ?: intent.getIntExtra("tab", 0).coerceIn(0, 4)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(16)); setBackgroundColor(Palette.bg)
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(dp(20), bars.top + dp(12), dp(20), bars.bottom + dp(12))
            } else view.setPadding(dp(20), insets.systemWindowInsetTop + dp(12), dp(20), insets.systemWindowInsetBottom + dp(12))
            insets
        }
        val brand = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        brand.addView(ImageView(this).apply { setImageResource(R.drawable.owlwhisp); contentDescription = "OwlWhisp" }, LinearLayout.LayoutParams(dp(52), dp(52)))
        brand.addView(TextView(this).apply { text = "OwlWhisp"; textSize = 30f; setTextColor(Palette.text); setPadding(dp(12), 0, 0, 0); setTypeface(null, Typeface.BOLD) })
        root.addView(brand)
        val navigation = LinearLayout(this)
        tabs.forEachIndexed { index, name ->
            navigation.addView(Button(this).apply { text = name; isAllCaps = false; textSize = 11f; minWidth = 0; minimumWidth = 0; setPadding(dp(2), 0, dp(2), 0); setTextColor(Palette.accent); setOnClickListener { showTab(index) } }, LinearLayout.LayoutParams(0, dp(60), 1f))
        }
        status = label("").apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        root.addView(status)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(progress)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(navigation, LinearLayout.LayoutParams(-1, dp(60)))
        setContentView(root); showTab(currentTab); app.observe(observer)
    }
    private fun label(value: String, dim: Boolean = false) = TextView(this).apply {
        text = value; textSize = if (dim) 14f else 16f; setTextColor(if (dim) Palette.dim else Palette.text)
        setPadding(0, dp(8), 0, dp(8))
    }
    private fun title(value: String) { content.addView(label(value).apply { textSize = 23f; setTypeface(null, Typeface.BOLD) }) }
    private fun button(value: String, action: () -> Unit): Button = Button(this).apply {
        text = value; isAllCaps = false; setTextColor(Palette.accent); setOnClickListener { action() }; actions.add(this)
    }.also { content.addView(it, LinearLayout.LayoutParams(-1, -2)) }
    private fun showTab(index: Int) {
        transcript?.let { if (!app.busy) app.draftText = it.text.toString() }; savedText = app.draftText
        transcript = null; record = null; cancelRecord = null; liveInsertionSwitch = null
        currentTab = index; content.removeAllViews(); actions.clear(); title(tabs[index])
        when (index) {
            0 -> {
                content.addView(label(app.model.name, true))
                record = button("Start dictation") {
                    if (app.state.phase == Phase.RECORDING) app.stopCurrent()
                    else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
                    else startRecording()
                }.apply { minHeight = dp(64); textSize = 18f }
                transcript = EditText(this).apply {
                    hint = "Your transcript will appear here"; setHintTextColor(Palette.dim); setTextColor(Palette.text)
                    setBackgroundColor(Palette.inset); setPadding(dp(12), dp(12), dp(12), dp(12))
                    minLines = 5; gravity = Gravity.TOP
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    setText(app.draftText)
                    addTextChangedListener(object : android.text.TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                        override fun afterTextChanged(s: android.text.Editable?) { if (!app.busy) app.draftText = s.toString() }
                    })
                }.also { content.addView(it, LinearLayout.LayoutParams(-1, dp(200))) }
                cancelRecord = button("Cancel recording") { app.cancelCurrent() }
                button("Set up voice keyboard") { startActivity(Intent(this, KeyboardSetupActivity::class.java)) }
                button("Copy text") { copy(transcript?.text.toString()) }
                button("Clear") { savedText = ""; app.draftText = ""; transcript?.setText("") }
                content.addView(label("Recording limit: " + RecordingPolicy.label(app.recordingLimitSeconds), true))
            }
            1 -> {
                title("Recording")
                content.addView(label("Choose when recording stops automatically.", true))
                content.addView(Switch(this).apply {
                    text = "Live text insertion"; setTextColor(Palette.text); isChecked = app.liveTextInsertion
                    isEnabled = !app.busy
                    setOnCheckedChangeListener { _, checked -> app.setLiveTextInsertion(checked) }
                }.also { liveInsertionSwitch = it })
                content.addView(label("On: insert phrases while dictating. Off: insert the result after Stop. Returning to a matching editor resumes insertion; ambiguous fields require confirmation.", true))
                button("Manage speech models") { showTab(2) }
                RecordingPolicy.limits.forEach { seconds ->
                    button((if (app.recordingLimitSeconds == seconds) "Selected: " else "") + RecordingPolicy.label(seconds)) {
                        app.setRecordingLimit(seconds); showTab(1)
                    }
                }
                content.addView(label("Memory"))
                content.addView(label("Models unload after each dictation by default. Parakeet can stay ready for a short time.", true))
                listOf(0 to "Unload immediately", 30 to "Parakeet: keep ready for 30 seconds", 300 to "Parakeet: keep ready for 5 minutes").forEach { (seconds, text) ->
                    button((if (app.warmSeconds == seconds) "✓ " else "") + text) { app.setWarmSeconds(seconds); showTab(1) }
                }
                button("Unload now") { app.unload() }
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    button("Allow recording notifications") { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3) }
                }
                title("Voice keyboard")
                content.addView(label(if (VoiceKeyboard.enabled(this)) "OwlWhisp keyboard is enabled" else "OwlWhisp keyboard is not enabled", true))
                button("Set up voice keyboard") { startActivity(Intent(this, KeyboardSetupActivity::class.java)) }
                button("Choose keyboard") { getSystemService(InputMethodManager::class.java).showInputMethodPicker() }
                content.addView(label("On Samsung, select OwlWhisp with the system keyboard picker. Samsung controls which providers its microphone button can open; remapping that button is not guaranteed.", true))
            }
            2 -> {
                app.models.forEach { repository ->
                    content.addView(label(repository.name).apply { setTypeface(null, Typeface.BOLD) })
                    val languages = if (repository.languages.size > 10) repository.languages.size.toString() + " languages" + (if ("ru" in repository.languages) ", including Russian" else "") else repository.languages.joinToString(", ")
                    content.addView(label(languages + " · download " + repository.totalBytes / 1024 / 1024 + " MiB\n" +
                        repository.manifest.getString("license") + " · " + if (repository.present()) "downloaded" else "not downloaded", true))
                    if (repository.supported) {
                        button(if (repository.id == app.model.id) "✓ Selected" else "Select") { app.selectModel(repository.id); showTab(2) }
                        button(if (repository.present()) "Verify / repair" else "Download") { app.installModel(repository) }
                        if (repository.present()) button("Remove from device") {
                            AlertDialog.Builder(this).setTitle("Remove " + repository.name + "?")
                                .setMessage("You can download the model again.").setNegativeButton("Cancel", null)
                                .setPositiveButton("Remove") { _, _ -> app.deleteModel(repository) }.show()
                        }
                    } else content.addView(label("Available on ARM64 phones; this emulator uses x86_64.", true))
                    content.addView(label(repository.manifest.getString("attribution"), true))
                }
            }
            3 -> {
                content.addView(label(app.model.name))
                content.addView(label("Six short English and Russian FLEURS clips. Only languages supported by the model are tested. Each clip uses a cold model load. WER measures errors; RTF is processing time divided by audio duration. Lower is better.", true))
                button("Run benchmark") { app.runBenchmark() }
                val report = File(filesDir, "benchmark.json")
                if (report.isFile) {
                    val json = org.json.JSONObject(report.readText())
                    content.addView(label("Latest result: " + json.getString("model")))
                    val rows = json.getJSONArray("rows")
                    for (i in 0 until rows.length()) {
                        val row = rows.getJSONObject(i)
                        content.addView(label(row.getString("file") + "\n" + String.format(Locale.ROOT, "WER %.1f%% · RTF %.2f · %.2f s", row.getDouble("wer") * 100, row.getDouble("rtf"), row.getLong("elapsed_ms") / 1000.0) + " · PSS max " + row.optInt("sampled_peak_pss_kib") / 1024 + " MiB"))
                        content.addView(label(row.getString("transcript"), true))
                    }
                    button("Share JSON report") {
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_TEXT, report.readText()), "OwlWhisp report"))
                    }
                    button("Copy JSON report") { copy(report.readText()) }
                }
                content.addView(label("FLEURS: Conneau et al., 2022 · google/fleurs · CC-BY-4.0. A small comparison set, not a full-dataset accuracy evaluation.", true))
            }
            4 -> {
                val memory = Debug.MemoryInfo(); Debug.getMemoryInfo(memory)
                content.addView(label("OwlWhisp Mobile 0.1.4\nAndroid " + Build.VERSION.RELEASE + " · " + Build.MODEL + "\n" + Build.SUPPORTED_ABIS.joinToString() +
                    "\nUI PSS: " + memory.totalPss / 1024 + " MiB\nModel process: " + if (app.client.loaded) "running" else "unloaded"))
                content.addView(label(app.lastMetrics, true))
                button("Refresh") { showTab(4) }
                button("Unload model") { app.unload() }
                button("Licenses and attribution") {
                    val notices = assets.open("THIRD_PARTY_NOTICES.md").bufferedReader().use { it.readText() }
                    AlertDialog.Builder(this).setTitle("Licenses").setMessage(notices).setPositiveButton("Close", null).show()
                }
            }
        }
        render(app.state)
    }
    private fun copy(text: String) {
        if (text.isNotBlank()) { getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("OwlWhisp", text)); Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show() }
    }
    private fun startRecording() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !app.preferences.getBoolean("notification_prompted", false)) {
            app.preferences.edit().putBoolean("notification_prompted", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            return
        }
        beginDictation()
    }
    private fun beginDictation() {
        app.draftText = transcript?.text?.toString() ?: app.draftText
        app.start(this) { }
    }
    private fun render(state: AppState) {
        status.text = state.message
        savedText = app.draftText
        transcript?.let { editor ->
            if (editor.text.toString() != app.draftText) { editor.setText(app.draftText); editor.setSelection(editor.length()) }
            editor.isEnabled = !app.busy
        }
        progress.visibility = if (state.phase == Phase.DOWNLOADING) View.VISIBLE else View.GONE; progress.progress = state.progress
        actions.forEach { it.isEnabled = !app.busy }
        liveInsertionSwitch?.isEnabled = !app.busy
        cancelRecord?.visibility = if (app.recordingActive) View.VISIBLE else View.GONE
        cancelRecord?.isEnabled = app.recordingActive
        record?.isEnabled = app.model.present() && (!app.busy || state.phase == Phase.RECORDING)
        record?.text = if (state.phase == Phase.RECORDING) "Stop dictation" else "Start dictation"
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2 && !isFinishing) beginDictation()
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && hasWindowFocus()) startRecording()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        if (intent.hasExtra("tab")) showTab(intent.getIntExtra("tab", 0).coerceIn(0, 4))
    }
    override fun onResume() { super.onResume(); app.refresh(); if (currentTab == 1) showTab(1) }
    override fun onPause() { if (!app.busy) app.unload(); super.onPause() }
    override fun onDestroy() { app.detach(this); app.removeObserver(observer); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("transcript", transcript?.text?.toString() ?: savedText); outState.putInt("tab", currentTab); super.onSaveInstanceState(outState)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
