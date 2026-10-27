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
    private lateinit var updateBanner: Button
    private var updateStatus: TextView? = null
    private var routingSwitch: Switch? = null
    private val updateObserver: () -> Unit = { renderUpdates() }
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
    private val actionConditions = mutableMapOf<Button, () -> Boolean>()
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
        updateBanner = Button(this).apply {
            isAllCaps = false; setTextColor(Palette.accent); visibility = View.GONE
            setOnClickListener { showUpdate() }
        }
        root.addView(updateBanner)
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
        app.updates.observe(updateObserver)
    }
    private fun label(value: String, dim: Boolean = false) = TextView(this).apply {
        text = value; textSize = if (dim) 14f else 16f; setTextColor(if (dim) Palette.dim else Palette.text)
        setPadding(0, dp(8), 0, dp(8))
    }
    private fun title(value: String) { content.addView(label(value).apply { textSize = 23f; setTypeface(null, Typeface.BOLD) }) }
    private fun metric(name: String, value: String) {
        content.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(label(name, true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(label(value).apply { setTypeface(null, Typeface.BOLD) })
        })
    }
    private fun button(value: String, action: () -> Unit): Button = Button(this).apply {
        text = value; isAllCaps = false; setTextColor(Palette.accent); setOnClickListener { action() }; actions.add(this)
    }.also { content.addView(it, LinearLayout.LayoutParams(-1, -2)) }
    private fun showTab(index: Int) {
        transcript?.let { if (!app.busy) app.draftText = it.text.toString() }; savedText = app.draftText
        transcript = null; record = null; cancelRecord = null; liveInsertionSwitch = null
        currentTab = index; content.removeAllViews(); actions.clear(); actionConditions.clear(); updateStatus = null; routingSwitch = null; title(tabs[index])
        when (index) {
            0 -> {
                content.addView(label(app.guide.name(app.model.id), true))
                if (app.routingEnabled) button("Language: " + ModelGuide.languageName(app.routingLanguage)) { chooseLanguage() }
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
                title("Language models")
                content.addView(Switch(this).apply {
                    text = "Experimental language switch"; setTextColor(Palette.text); isChecked = app.routingEnabled
                    setOnCheckedChangeListener { _, checked -> app.setRoutingEnabled(checked); showTab(1) }
                }.also { routingSwitch = it })
                if (app.routingEnabled) {
                    app.languageAssignments.toSortedMap().forEach { (language, id) ->
                        button(ModelGuide.languageName(language) + ": " + app.guide.name(id)) { assignLanguage(language) }
                    }
                    button("Add language") { addLanguage() }
                    content.addView(label("Choose a model per language. Switch in the voice panel between recordings. Multilingual models may still detect the spoken language.", true))
                }
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
                title("App updates")
                content.addView(label("Installed version: " + packageManager.getPackageInfo(packageName, 0).versionName, true))
                content.addView(Switch(this).apply {
                    text = "Check for updates on launch"; setTextColor(Palette.text); isChecked = app.updates.automatic
                    setOnCheckedChangeListener { _, checked -> app.updates.automatic = checked }
                })
                updateStatus = label(app.updates.status, true).also { content.addView(it) }
                button("Check for updates") { app.updates.check() }
                button("View releases") { openURL(AppRelease.PAGE) }
                title("Voice keyboard")
                content.addView(label(if (VoiceKeyboard.enabled(this)) "OwlWhisp keyboard is enabled" else "OwlWhisp keyboard is not enabled", true))
                button("Set up voice keyboard") { startActivity(Intent(this, KeyboardSetupActivity::class.java)) }
                button("Choose keyboard") { getSystemService(InputMethodManager::class.java).showInputMethodPicker() }
                content.addView(label("On Samsung, select OwlWhisp with the system keyboard picker. Samsung controls which providers its microphone button can open; remapping that button is not guaranteed.", true))
            }
            2 -> {
                button("Find a model for your phone and language") { showModelGuide() }
                app.models.forEach { repository -> modelCard(repository) }
            }
            3 -> {
                content.addView(label(app.model.name))
                content.addView(label(Benchmark.clipDescription(Benchmark.fixtures(this, app.model.languages)), true))
                button("Run benchmark") { app.runBenchmark() }
                val report = File(filesDir, "benchmark.json")
                if (report.isFile) {
                    val json = org.json.JSONObject(report.readText())
                    val summary = Benchmark.summary(json.getJSONArray("rows"))
                    title("Latest result")
                    val modelID = json.getString("model")
                    content.addView(label(app.models.firstOrNull { it.id == modelID }?.name ?: modelID))
                    content.addView(label(String.format(Locale.ROOT, "%d clips · %.2f s audio", summary.clipCount, summary.audioSeconds), true))
                    metric("Word error (WER)", String.format(Locale.ROOT, "%.1f%%", summary.wordErrorRate * 100))
                    metric("Speed (RTF)", String.format(Locale.ROOT, "%.2f", summary.rtf))
                    metric("Processing time", String.format(Locale.ROOT, "%.2f s", summary.elapsedSeconds))
                    button("Share report") {
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_TEXT, report.readText()), "OwlWhisp report"))
                    }
                    button("Copy report") { copy(report.readText()) }
                }
            }
            4 -> {
                val memory = Debug.MemoryInfo(); Debug.getMemoryInfo(memory)
                content.addView(label("OwlWhisp Mobile 0.1.6\nAndroid " + Build.VERSION.RELEASE + " · " + Build.MODEL + "\n" + Build.SUPPORTED_ABIS.joinToString() +
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
    private fun chooseLanguage() {
        val assignments = app.languageAssignments.toSortedMap().entries.toList()
        val labels = assignments.map { (language, id) ->
            ModelGuide.languageName(language) + " - " + app.guide.name(id) +
                if (app.models.firstOrNull { it.id == id }?.present() == true) "" else " (download first)"
        }
        AlertDialog.Builder(this).setTitle("Dictation language").setItems(labels.toTypedArray()) { _, index ->
            if (app.chooseLanguage(assignments[index].key)) showTab(currentTab)
        }.setNegativeButton("Cancel", null).show()
    }
    private fun assignLanguage(language: String) {
        val models = app.models.filter { it.supported && language in it.languages }
        val labels = models.map { app.guide.name(it.id) + if (it.present()) "" else " (not downloaded)" }
        AlertDialog.Builder(this).setTitle(ModelGuide.languageName(language))
            .setItems(labels.toTypedArray()) { _, index -> app.assignLanguage(language, models[index].id); showTab(1) }
            .setNeutralButton("Remove language") { _, _ -> app.assignLanguage(language, null); showTab(1) }
            .setNegativeButton("Cancel", null).show()
    }
    private fun addLanguage() {
        val languages = app.models.filter { it.supported }.flatMap { it.languages }.distinct()
            .filter { it !in app.languageAssignments }.sortedBy(ModelGuide::languageName)
        AlertDialog.Builder(this).setTitle("Add language")
            .setItems(languages.map(ModelGuide::languageName).toTypedArray()) { _, index -> assignLanguage(languages[index]) }
            .setNegativeButton("Cancel", null).show()
    }
    private fun modelCard(repository: ModelRepository) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Palette.raised); cornerRadius = dp(12).toFloat() }
        }
        val active = repository.id == app.model.id
        card.addView(label(app.guide.name(repository.id) + if (active) " \u00b7 Active" else "").apply { setTypeface(null, Typeface.BOLD) })
        val languages = if (repository.languages.size > 5) repository.languages.size.toString() + " languages" else repository.languages.joinToString(", ") { it.uppercase(Locale.ROOT) }
        card.addView(label(languages + " \u00b7 " + repository.totalBytes / 1024 / 1024 + " MiB \u00b7 " +
            if (repository.present()) "Downloaded" else "Not downloaded", true).apply { setPadding(0, 0, 0, dp(4)) })
        card.addView(label(app.benchmarks.metrics(repository), true).apply { setPadding(0, dp(2), 0, dp(4)) })
        val row = LinearLayout(this)
        val use = Button(this).apply {
            text = if (!repository.supported) "Unavailable" else if (!repository.present()) "Download" else if (app.routingEnabled) "Use for " + app.routingLanguage.uppercase(Locale.ROOT) else "Use model"
            isAllCaps = false; setTextColor(Palette.accent)
            setOnClickListener {
                if (!repository.supported) return@setOnClickListener
                if (!repository.present()) app.installModel(repository) else { app.selectModel(repository.id); showTab(2) }
            }
        }
        val more = Button(this).apply {
            text = "Details"; isAllCaps = false; setTextColor(Palette.accent)
            setOnClickListener { modelDetails(repository) }
        }
        actions.add(use); actions.add(more)
        actionConditions[use] = { repository.supported && (!repository.present() || !app.routingEnabled || app.routingLanguage in repository.languages) }
        row.addView(use, LinearLayout.LayoutParams(0, dp(48), 1f)); row.addView(more, LinearLayout.LayoutParams(dp(96), dp(48)))
        card.addView(row)
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
    }
    private fun modelDetails(repository: ModelRepository) {
        val report = app.benchmarks.report(repository)
        val measured = report?.let { "\n\nDevice benchmark\n" + app.benchmarks.metrics(repository) + "\nApp version: " + it.optString("app_version") } ?: "\n\nNo benchmark yet. Select this model and run Benchmark."
        val details = repository.name + "\n\n" + app.guide.purpose(repository.id) + "\n" +
            repository.languages.joinToString(", ") { ModelGuide.languageName(it) } + measured +
            "\n\n" + repository.manifest.getString("license") + "\n" + repository.manifest.getString("attribution") +
            if (repository.supported) "" else "\n\nRequires an ARM64 phone."
        val items = mutableListOf("Close")
        if (repository.supported) items.add(if (repository.present()) "Verify / repair" else "Download")
        if (repository.present()) items.add("Remove model")
        AlertDialog.Builder(this).setTitle(app.guide.name(repository.id)).setMessage(details)
            .setPositiveButton(items[0], null)
            .apply {
                if (items.size > 1) setNeutralButton(items[1]) { _, _ -> app.installModel(repository) }
                if (items.size > 2) setNegativeButton("Remove") { _, _ ->
                    AlertDialog.Builder(this@MainActivity).setTitle("Remove " + app.guide.name(repository.id) + "?")
                        .setNegativeButton("Cancel", null).setPositiveButton("Remove") { _, _ -> app.deleteModel(repository) }.show()
                }
            }.show()
    }
    private fun showModelGuide() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(8)) }
        val languages = app.models.filter { it.supported }.flatMap { it.languages }.distinct().sortedBy(ModelGuide::languageName)
        val language = Spinner(this)
        language.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, languages.map(ModelGuide::languageName))
        language.setSelection(languages.indexOf(app.preferences.getString("guide_language", Locale.getDefault().language)).takeIf { it >= 0 } ?: languages.indexOf("en").coerceAtLeast(0))
        val profile = Spinner(this)
        val profileLabels = listOf("This phone (" + String.format(Locale.ROOT, "%.1f GiB RAM", app.guide.memoryGiB) + ")") +
            app.guide.profiles.map { it.getString("name") + " - " + it.getString("examples") }
        profile.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, profileLabels)
        val goal = Spinner(this)
        goal.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Everyday dictation", "Smallest download", "Multilingual dictation"))
        box.addView(label("Language")); box.addView(language)
        box.addView(label("Phone profile")); box.addView(profile)
        box.addView(label("Priority")); box.addView(goal)
        box.addView(label(app.guide.data.getString("basis"), true))
        AlertDialog.Builder(this).setTitle("Model guide").setView(box).setNegativeButton("Cancel", null)
            .setPositiveButton("Show suggestions") { _, _ ->
                val code = languages[language.selectedItemPosition]
                app.preferences.edit().putString("guide_language", code).apply()
                val tier = if (goal.selectedItemPosition == 1) "compact" else if (profile.selectedItemPosition == 0) app.guide.deviceTier else app.guide.profiles[profile.selectedItemPosition - 1].getString("tier")
                val suggestions = app.guide.recommend(code, tier, goal.selectedItemPosition == 2, app.models, goal.selectedItemPosition == 1)
                AlertDialog.Builder(this).setTitle(ModelGuide.languageName(code) + " - starting points")
                    .setItems(suggestions.map { app.guide.name(it.id) + "\n" + app.guide.purpose(it.id) }.toTypedArray()) { _, index -> modelDetails(suggestions[index]) }
                    .setNegativeButton("Close", null).show()
            }.show()
    }
    private fun openURL(url: String) { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
    private fun renderUpdates() {
        val updates = app.updates
        val release = updates.available
        val show = release != null && (updates.dismissedVersion != release.version.toString() || updates.readyFile != null)
        updateBanner.visibility = if (show) View.VISIBLE else View.GONE
        updateBanner.text = when {
            updates.downloading -> updates.status
            updates.readyFile != null -> "Install OwlWhisp " + release?.version
            else -> "OwlWhisp " + release?.version + " is available"
        }
        updateStatus?.text = updates.status
    }
    private fun showUpdate() {
        val updates = app.updates
        val release = updates.available ?: return
        val dialog = AlertDialog.Builder(this).setTitle("OwlWhisp " + release.version)
            .setMessage(updates.status + "\n\n" + release.notes)
            .setNegativeButton("Later") { _, _ -> updates.dismiss() }
            .setNeutralButton("Release page") { _, _ -> openURL(release.page) }
            .setPositiveButton(if (updates.downloading) "Cancel download" else if (updates.readyFile != null) "Install" else "Download update") { _, _ ->
                if (updates.downloading) updates.cancelDownload()
                else if (updates.readyFile != null) installUpdate()
                else updates.download()
            }.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = updates.downloading || (!app.busy && release.asset != null && !updates.checking)
        }
        dialog.show()
    }
    private fun installUpdate() {
        if (app.busy) { Toast.makeText(this, "Finish dictation before updating", Toast.LENGTH_SHORT).show(); return }
        val file = app.updates.readyFile?.takeIf { it.isFile } ?: return
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "Allow updates from OwlWhisp, then return and tap Install", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:" + packageName))); return
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(this, packageName + ".updates", file)
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure { Toast.makeText(this, "No Android package installer is available", Toast.LENGTH_LONG).show() }
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
        actions.forEach { it.isEnabled = !app.busy && actionConditions[it]?.invoke() != false }
        liveInsertionSwitch?.isEnabled = !app.busy
        routingSwitch?.isEnabled = !app.busy
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
    override fun onResume() {
        super.onResume(); app.refresh()
        if (currentTab in 0..2) showTab(currentTab)
        app.updates.onLaunch(); renderUpdates()
    }
    override fun onPause() { if (!app.busy) app.unload(); super.onPause() }
    override fun onDestroy() { app.detach(this); app.removeObserver(observer); app.updates.removeObserver(updateObserver); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("transcript", transcript?.text?.toString() ?: savedText); outState.putInt("tab", currentTab); super.onSaveInstanceState(outState)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
