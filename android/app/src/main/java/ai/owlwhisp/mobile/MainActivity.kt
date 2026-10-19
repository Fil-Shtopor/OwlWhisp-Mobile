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
    private var savedText = ""
    private var currentTab = 0
    private val actions = mutableListOf<Button>()
    private val tabs = listOf("Диктовка", "Настройки", "Модели", "Бенчмарк", "Диагностика")
    private var wasBusy = false
    private val observer: (AppState) -> Unit = {
        val finished = wasBusy && !app.busy
        wasBusy = app.busy
        if (finished && currentTab in 2..4) showTab(currentTab) else render(it)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedText = savedInstanceState?.getString("transcript").orEmpty()
        currentTab = savedInstanceState?.getInt("tab") ?: 0
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
        root.addView(label("Голос остаётся на устройстве", true))
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
        transcript?.let { savedText = it.text.toString() }; transcript = null; record = null
        currentTab = index; content.removeAllViews(); actions.clear(); title(tabs[index])
        when (index) {
            0 -> {
                content.addView(label(app.model.name, true))
                record = button("Начать диктовку") {
                    if (app.state.phase == Phase.RECORDING && app.isOwner(this)) app.stop(this)
                    else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
                    else startRecording()
                }.apply { minHeight = dp(64); textSize = 18f }
                transcript = EditText(this).apply {
                    hint = "Здесь появится распознанный текст"; setHintTextColor(Palette.dim); setTextColor(Palette.text)
                    setBackgroundColor(Palette.inset); setPadding(dp(12), dp(12), dp(12), dp(12))
                    minLines = 5; gravity = Gravity.TOP
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    setText(savedText)
                }.also { content.addView(it, LinearLayout.LayoutParams(-1, dp(200))) }
                button("Скопировать текст") { copy(transcript?.text.toString()) }
                button("Очистить") { savedText = ""; transcript?.setText("") }
                content.addView(label("Запись ограничена 60 секундами. Аудио удаляется после обработки.", true))
            }
            1 -> {
                content.addView(label("Память"))
                content.addView(label("По умолчанию модель выгружается после каждой диктовки. Для Parakeet можно оставить её готовой на короткое время.", true))
                listOf(0 to "Выгружать сразу", 30 to "Parakeet: хранить 30 секунд", 300 to "Parakeet: хранить 5 минут").forEach { (seconds, text) ->
                    button((if (app.warmSeconds == seconds) "✓ " else "") + text) { app.setWarmSeconds(seconds); showTab(1) }
                }
                button("Выгрузить сейчас") { app.unload() }
                title("Голосовая клавиатура")
                button("Включить OwlWhisp в настройках Android") { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
                button("Выбрать клавиатуру") { getSystemService(InputMethodManager::class.java).showInputMethodPicker() }
                content.addView(label("На Samsung выберите OwlWhisp через системное переключение клавиатур. Кнопка микрофона Samsung может вызывать только сервисы, разрешённые Samsung; её переназначение не гарантируется.", true))
            }
            2 -> {
                app.models.forEach { repository ->
                    content.addView(label(repository.name).apply { setTypeface(null, Typeface.BOLD) })
                    val languages = if (repository.languages.size > 10) "25 языков, включая русский" else repository.languages.joinToString(", ")
                    content.addView(label(languages + " · загрузка " + repository.totalBytes / 1024 / 1024 + " МиБ\n" +
                        repository.manifest.getString("license") + " · " + if (repository.present()) "на устройстве" else "не загружена", true))
                    if (repository.supported) {
                        button(if (repository.id == app.model.id) "✓ Выбрана" else "Выбрать") { app.selectModel(repository.id); showTab(2) }
                        button(if (repository.present()) "Проверить / восстановить" else "Загрузить") { app.installModel(repository) }
                        if (repository.present()) button("Удалить с устройства") {
                            AlertDialog.Builder(this).setTitle("Удалить " + repository.name + "?")
                                .setMessage("Модель можно загрузить снова.").setNegativeButton("Отмена", null)
                                .setPositiveButton("Удалить") { _, _ -> app.deleteModel(repository) }.show()
                        }
                    } else content.addView(label("Доступна на ARM64-телефоне; этот эмулятор x86_64.", true))
                    content.addView(label(repository.manifest.getString("attribution"), true))
                }
            }
            3 -> {
                content.addView(label(app.model.name))
                content.addView(label("6 коротких образцов FLEURS: русский и английский. Для модели выбираются только её языки. Каждый образец запускается с холодной загрузкой. WER показывает ошибки, RTF — время обработки / длину аудио. Меньше — лучше.", true))
                button("Запустить бенчмарк") { app.runBenchmark() }
                val report = File(filesDir, "benchmark.json")
                if (report.isFile) {
                    val json = org.json.JSONObject(report.readText())
                    content.addView(label("Последний результат: " + json.getString("model")))
                    val rows = json.getJSONArray("rows")
                    for (i in 0 until rows.length()) {
                        val row = rows.getJSONObject(i)
                        content.addView(label(row.getString("file") + "\n" + String.format(Locale.ROOT, "WER %.1f%% · RTF %.2f · %.2f c", row.getDouble("wer") * 100, row.getDouble("rtf"), row.getLong("elapsed_ms") / 1000.0) + " · PSS max " + row.optInt("sampled_peak_pss_kib") / 1024 + " МиБ"))
                        content.addView(label(row.getString("transcript"), true))
                    }
                    button("Поделиться JSON-отчётом") {
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_TEXT, report.readText()), "Отчёт OwlWhisp"))
                    }
                    button("Скопировать JSON-отчёт") { copy(report.readText()) }
                }
                content.addView(label("FLEURS: Conneau et al., 2022 · google/fleurs · CC-BY-4.0. Это небольшой сравнительный набор, а не оценка качества на всей выборке.", true))
            }
            4 -> {
                val memory = Debug.MemoryInfo(); Debug.getMemoryInfo(memory)
                content.addView(label("OwlWhisp Mobile 0.1.0\nAndroid " + Build.VERSION.RELEASE + " · " + Build.MODEL + "\n" + Build.SUPPORTED_ABIS.joinToString() +
                    "\nPSS интерфейса: " + memory.totalPss / 1024 + " МиБ\nПроцесс модели: " + if (app.client.loaded) "запущен" else "выгружен"))
                content.addView(label(app.lastMetrics, true))
                button("Обновить") { showTab(4) }
                button("Выгрузить модель") { app.unload() }
                button("Лицензии и авторство") {
                    val notices = assets.open("THIRD_PARTY_NOTICES.md").bufferedReader().use { it.readText() }
                    AlertDialog.Builder(this).setTitle("Лицензии").setMessage(notices).setPositiveButton("Закрыть", null).show()
                }
                content.addView(label("Распознавание выполняется локально. Сеть нужна для загрузки моделей. Аудио и результаты не отправляются на сервер. Автозапуска и постоянного фонового сервиса нет.", true))
            }
        }
        render(app.state)
    }
    private fun copy(text: String) {
        if (text.isNotBlank()) { getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("OwlWhisp", text)); Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show() }
    }
    private fun startRecording() {
        savedText = transcript?.text?.toString() ?: savedText
        app.start(this) { result ->
        if (result.isNotBlank()) { savedText = (savedText + " " + result).trim(); transcript?.setText(savedText) }
        }
    }
    private fun render(state: AppState) {
        status.text = state.message
        progress.visibility = if (state.phase == Phase.DOWNLOADING) View.VISIBLE else View.GONE; progress.progress = state.progress
        actions.forEach { it.isEnabled = !app.busy }
        record?.isEnabled = app.model.present() && (!app.busy || (state.phase == Phase.RECORDING && app.isOwner(this)))
        record?.text = if (state.phase == Phase.RECORDING && app.isOwner(this)) "Остановить и распознать" else "Начать диктовку"
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && hasWindowFocus()) startRecording()
    }
    override fun onResume() { super.onResume(); app.refresh() }
    override fun onPause() { app.cancel(this); if (!app.busy) app.unload(); super.onPause() }
    override fun onDestroy() { app.removeObserver(observer); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("transcript", transcript?.text?.toString() ?: savedText); outState.putInt("tab", currentTab); super.onSaveInstanceState(outState)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
