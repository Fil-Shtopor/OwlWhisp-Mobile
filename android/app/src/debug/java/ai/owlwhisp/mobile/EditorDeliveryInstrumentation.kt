package ai.owlwhisp.mobile

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import org.json.JSONObject

/** Two actual editors in one app, for cursor/field matching and manual UI regression checks. */
open class EditorTestActivity : Activity() {
    lateinit var original: EditText
    lateinit var other: EditText
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 64, 24, 24) }
        original = EditText(this).apply { id = R.id.test_original_editor; hint = "Original editor"; setText("Original conversation draft: "); minLines = 3 }
        other = EditText(this).apply { id = R.id.test_other_editor; hint = "Other editor"; setText("Other conversation draft: "); minLines = 3 }
        root.addView(original, LinearLayout.LayoutParams(-1, -2)); root.addView(other, LinearLayout.LayoutParams(-1, -2))
        setContentView(root); focus(intent.getIntExtra("field", 1))
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); focus(intent.getIntExtra("field", 1)) }
    private fun focus(field: Int) {
        val view = if (field == 1) original else other
        view.requestFocus(); view.setSelection(view.length())
        view.post { getSystemService(InputMethodManager::class.java).showSoftInput(view, InputMethodManager.SHOW_IMPLICIT) }
    }
}

/** Separate editor process for end-to-end IME IPC checks. */
class RemoteEditorTestActivity : EditorTestActivity()

class EditorDeliveryInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        var activity: EditorTestActivity? = null
        try {
            activity = startActivitySync(Intent(targetContext, EditorTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as EditorTestActivity
            runOnMainSync {
                val view = activity!!.original
                val info = EditorInfo()
                val connection = checkNotNull(view.onCreateInputConnection(info))
                val identity = EditorIdentity.from(info)
                val session = EditorDelivery()
                val initial = checkNotNull(EditorAnchor.read(connection))
                fun insert() {
                    val text = session.pending
                    check(connection.commitText(text, 1)); session.committed(text)
                    check(EditorAnchor.read(connection) == session.anchor)
                }
                session.begin(identity, initial, true)
                session.offer("First dictated phrase."); insert()
                session.suspend(); session.offer("First dictated phrase. Second dictated phrase.")
                check(!session.tryResume(identity.copy(packageName = "another.app"), session.anchor))
                check(!session.tryResume(identity.copy(fieldId = identity.fieldId + 1), session.anchor))
                check(!session.tryResume(identity, initial))
                check(session.tryResume(identity, EditorAnchor.read(connection))); insert()
                val once = view.text.toString()
                check(session.pending.isEmpty())
                session.finish(session.latest); check(!session.active)
                check(view.text.toString() == once && once.endsWith("First dictated phrase. Second dictated phrase."))

                session.begin(identity, EditorAnchor.read(connection), false)
                session.offer("Only after Stop.")
                check(session.pending.isEmpty() && view.text.toString() == once)
                session.suspend(); session.finish(session.latest)
                check(session.active && session.tryResume(identity, EditorAnchor.read(connection)))
                insert(); check(!session.active && view.text.toString().endsWith("Only after Stop."))

                view.setText(""); view.setSelection(0)
                session.begin(identity, EditorAnchor.read(connection), true)
                session.suspend(); session.offer("Pending text")
                check(!session.tryResume(identity, EditorAnchor.read(connection)))
                check(session.confirm(identity, EditorAnchor.read(connection))); insert()

                session.suspend()
                view.setText("Changed to a different conversation"); view.setSelection(view.length())
                check(!session.tryResume(identity, EditorAnchor.read(connection)))
                check(!session.confirm(identity.copy(packageName = "another.app"), EditorAnchor.read(connection)))
                check(session.confirm(identity, EditorAnchor.read(connection)))

                view.setText("prefix selected suffix"); view.setSelection(7, 15)
                session.begin(identity, EditorAnchor.read(connection), true)
                session.offer("replacement"); insert()
                check(view.text.toString() == "prefix replacement suffix")
                session.suspend(true)
                check(!session.tryResume(identity, EditorAnchor.read(connection)))
                result.putString("delivery", JSONObject().put("live_append", true).put("same_app_other_field_blocked", true)
                    .put("other_app_blocked", true).put("return_resumes_only_pending", true).put("after_stop_mode", true)
                    .put("empty_context_confirmation", true).put("changed_context_blocked", true).put("selection_replacement", true).toString())
            }
            finish(Activity.RESULT_OK, result)
        } catch (failure: Throwable) {
            result.putString("failure", failure.stackTraceToString()); finish(Activity.RESULT_CANCELED, result)
        } finally { activity?.let { runOnMainSync { it.finish() } } }
    }
}
