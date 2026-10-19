package ai.owlwhisp.mobile

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*

class MainActivity : Activity() {
    private val app get() = application as OwlWhispApp
    private lateinit var status: TextView
    private lateinit var download: Button
    private lateinit var record: Button
    private lateinit var progress: ProgressBar
    private lateinit var transcript: EditText
    private val observer: (AppState) -> Unit = { render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(Color.rgb(245, 244, 239))
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(dp(24), insets.systemWindowInsetTop + dp(24), dp(24), insets.systemWindowInsetBottom + dp(24))
            insets
        }
        root.addView(TextView(this).apply { text = getString(R.string.app_name); textSize = 34f; setTextColor(Color.rgb(29, 61, 54)) })
        root.addView(TextView(this).apply { text = "Голос остаётся на устройстве"; textSize = 17f; setPadding(0, dp(4), 0, dp(24)) })
        root.addView(TextView(this).apply { text = getString(R.string.model_caption); textSize = 16f })
        status = TextView(this).apply { textSize = 16f; setPadding(0, dp(20), 0, dp(12)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        root.addView(status)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(progress)
        download = Button(this).apply { text = "Загрузить модель"; setOnClickListener { app.installModel() } }
        root.addView(download)
        record = Button(this).apply {
            text = "Начать диктовку"
            minHeight = dp(76)
            textSize = 19f
            setOnClickListener {
                if (app.state.phase == Phase.RECORDING && app.isOwner(this@MainActivity)) app.stop(this@MainActivity)
                else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
                else startRecording()
            }
        }
        root.addView(record)
        transcript = EditText(this).apply {
            hint = "Здесь появится распознанный текст"
            minLines = 5
            gravity = android.view.Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(savedInstanceState?.getString("transcript").orEmpty())
        }
        root.addView(transcript, LinearLayout.LayoutParams(-1, dp(180)))
        root.addView(Button(this).apply {
            text = "Скопировать текст"
            setOnClickListener {
                if (transcript.text.isNotBlank()) {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("OwlWhisp", transcript.text))
                    Toast.makeText(this@MainActivity, "Текст скопирован", Toast.LENGTH_SHORT).show()
                }
            }
        })
        root.addView(Button(this).apply {
            text = "Включить голосовую клавиатуру"
            setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        })
        root.addView(Button(this).apply {
            text = "Выбрать клавиатуру"
            setOnClickListener { getSystemService(InputMethodManager::class.java).showInputMethodPicker() }
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.privacy_notice)
            textSize = 13f; setPadding(0, dp(16), 0, 0)
        })
        root.addView(Button(this).apply {
            text = "Модель и лицензии"
            setOnClickListener {
                android.app.AlertDialog.Builder(this@MainActivity).setTitle("Parakeet TDT 0.6B v3")
                    .setMessage(R.string.model_attribution).setPositiveButton("Закрыть", null).show()
            }
        })
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
        app.observe(observer)
    }

    private fun startRecording() = app.start(this) { result ->
        if (result.isNotBlank()) {
            if (transcript.text.isNotBlank()) transcript.append(" ")
            transcript.append(result)
        }
    }

    private fun render(state: AppState) {
        status.text = state.message
        progress.visibility = if (state.phase == Phase.DOWNLOADING) View.VISIBLE else View.GONE
        progress.progress = state.progress
        val busy = state.phase in setOf(Phase.DOWNLOADING, Phase.PROCESSING, Phase.RECORDING)
        download.isEnabled = !busy
        download.text = if (app.model.present()) "Проверить модель" else "Загрузить модель · 639 МиБ"
        record.isEnabled = app.model.present() && (state.phase == Phase.IDLE || state.phase == Phase.ERROR || (state.phase == Phase.RECORDING && app.isOwner(this)))
        record.text = if (state.phase == Phase.RECORDING && app.isOwner(this)) "Остановить и распознать" else "Начать диктовку"
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startRecording()
        else Toast.makeText(this, "Для диктовки нужен доступ к микрофону", Toast.LENGTH_LONG).show()
    }
    override fun onResume() { super.onResume(); app.refresh() }
    override fun onPause() { app.cancel(this); super.onPause() }
    override fun onDestroy() { app.removeObserver(observer); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("transcript", transcript.text.toString()); super.onSaveInstanceState(outState) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
