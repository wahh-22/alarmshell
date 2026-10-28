package dev.wahh.alarmshell

import android.annotation.SuppressLint
import android.app.Activity
import android.text.InputFilter
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout

/**
 * Native labeled field with the same secure-editing contract as QuotaShell's input: no autofill,
 * no suggestions, no personalized learning, no saved instance state, and an explicit reveal toggle.
 * Nothing here is persisted by the widget itself.
 */
@SuppressLint("ViewConstructor") // Activity-bound programmatic control, never XML-inflated.
internal class SecureField(
    private val activity: Activity,
    label: String,
    placeholder: String,
    private val secret: Boolean = false,
    kind: Int = InputType.TYPE_CLASS_TEXT,
    maxLength: Int? = null,
    private val helper: String = "",
) : LinearLayout(activity) {
    private val caption = activity.text(label, 13f)
    val edit = EditText(activity)
    private val support = activity.text(helper, 12f)
    private var revealed = false
    private val reveal = if (secret) activity.button("Show") { toggleSecret() } else null

    init {
        orientation = VERTICAL
        setPadding(0, activity.dp(8), 0, activity.dp(8))
        addView(caption)
        edit.apply {
            id = View.generateViewId()
            hint = placeholder
            contentDescription = label
            typeface = HudFont.typeface(activity, false)
            inputType =
                if (secret) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                } else {
                    kind
                }
            isSingleLine = true
            minHeight = activity.dp(48)
            minimumHeight = activity.dp(48)
            imeOptions = EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            if (maxLength != null) filters = arrayOf(InputFilter.LengthFilter(maxLength))
            if (secret) transformationMethod = PasswordTransformationMethod.getInstance()
        }
        caption.labelFor = edit.id
        addView(edit, LayoutParams(-1, -2))
        if (secret) {
            reveal?.contentDescription = "Show $label"
            addView(reveal, LayoutParams(-1, -2))
        }
        support.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        support.visibility = if (helper.isEmpty()) View.GONE else View.VISIBLE
        addView(support)
    }

    fun showMessage(message: String?) {
        support.text = message ?: helper
        support.visibility = if (message == null && helper.isEmpty()) View.GONE else View.VISIBLE
    }

    fun clear() {
        edit.text.clear()
        hideSecret()
    }

    /** Copy without materializing an immutable String for secret values, then wipe the source. */
    fun takeChars(): CharArray {
        val editable = edit.text
        val chars = CharArray(editable.length) { editable[it] }
        editable.clear()
        return chars
    }

    fun hideSecret() {
        if (!secret) return
        revealed = false
        edit.transformationMethod = PasswordTransformationMethod.getInstance()
        reveal?.setText(R.string.input_show)
        reveal?.contentDescription = "Show ${edit.contentDescription}"
    }

    private fun toggleSecret() {
        revealed = !revealed
        edit.transformationMethod = if (revealed) null else PasswordTransformationMethod.getInstance()
        edit.setSelection(edit.length())
        reveal?.setText(if (revealed) R.string.input_hide else R.string.input_show)
        reveal?.contentDescription = "${if (revealed) "Hide" else "Show"} ${edit.contentDescription}"
    }
}
