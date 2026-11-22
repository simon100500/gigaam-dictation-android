package app.hexforandroid

import android.accessibilityservice.AccessibilityService
import android.annotation.TargetApi
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast

/** Where a finished dictation goes. */
object Delivery {
    fun deliver(context: Context, text: String) {
        val service = HexAccessibilityService.instance
        val outcome = if (service != null) Inserter.insert(service, text, Settings(context).insertMode) else Inserter.copy(context, text)
        val message = when (outcome) {
            Inserter.Outcome.Inserted, Inserter.Outcome.Pasted -> return
            Inserter.Outcome.Refused -> "This field didn't accept the text. It's on the clipboard"
            Inserter.Outcome.Copied ->
                if (service == null) "Copied. Turn on Hex's accessibility service to paste into apps"
                else "Hex couldn't find the text field. It's on the clipboard"
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}

/**
 * Puts text into the focused field of another app through the accessibility
 * service. "auto" types it like a keyboard (Android 13+), or edits the field's
 * text at the caret where it reports its text and caret; both leave the
 * clipboard alone. Anything else, and the "paste" mode, goes through the
 * clipboard and a paste action.
 */
object Inserter {
    /** Copied: no field found. Refused: a field was found but took neither an edit nor a paste. */
    enum class Outcome { Inserted, Pasted, Copied, Refused }

    private const val TAG = "HexInsert"

    fun insert(service: HexAccessibilityService, text: String, mode: String): Outcome {
        // Type it the way a keyboard does: works in browsers, web views and
        // Compose fields that offer no set-text action, and keeps the clipboard.
        if (mode == "auto" && Build.VERSION.SDK_INT >= 33 && type(service, text)) return Outcome.Inserted
        val node = focusedField(service)
        if (node == null) {
            Log.i(TAG, "no field found; ${describe(service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT))}")
            return copy(service, text)
        }
        Log.i(TAG, "field ${describe(node)}")
        if (mode == "auto" && !node.isPassword && accepts(node, AccessibilityNodeInfo.ACTION_SET_TEXT) && splice(node, text)) {
            return Outcome.Inserted
        }
        copy(service, text)
        if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return Outcome.Pasted
        Log.i(TAG, "the field refused both an edit and a paste")
        return Outcome.Refused
    }

    /**
     * Commits [text] through the accessibility service's input connection
     * (Android 13+), spaced against the text around the cursor. False when
     * no editor is connected.
     */
    @TargetApi(33)
    private fun type(service: AccessibilityService, text: String): Boolean {
        val connection = service.inputMethod?.currentInputConnection ?: return false
        val around = runCatching { connection.getSurroundingText(200, 200, 0) }.getOrNull()
        val insert = around?.let {
            val all = it.text.toString()
            val start = minOf(it.selectionStart, it.selectionEnd)
            val end = maxOf(it.selectionStart, it.selectionEnd)
            if (start in 0..all.length && end in 0..all.length) spaced(all.substring(0, start), all.substring(end), text) else text
        } ?: text
        connection.commitText(insert, 1, null)
        // Lengths only, never the text: whether the field took it.
        val after = runCatching { connection.getSurroundingText(200, 200, 0)?.text?.length }.getOrNull()
        Log.i(TAG, "typed ${insert.length} chars through the input connection; around the cursor ${around?.text?.length} -> $after")
        return true
    }

    fun copy(context: Context, text: String): Outcome {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Hex", text))
        return Outcome.Copied
    }

    /**
     * The field to write into, in order of trust:
     * 1. the input focus Android reports, if it takes text;
     * 2. a focused node under it that takes text (some editors put input
     *    focus on a container);
     * 3. the last field the user focused, clicked or edited, if it is still
     *    on screen in the app that has input focus;
     * 4. any focused node that takes text in the interactive windows.
     */
    private fun focusedField(service: HexAccessibilityService): AccessibilityNodeInfo? {
        val focus = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focus != null && takesText(focus)) return focus
        focus?.let { find(it) { n -> n.isFocused && takesText(n) } }?.let { return it }
        val app = runCatching { service.windows.firstOrNull { it.isFocused }?.root?.packageName }.getOrNull()
            ?: service.rootInActiveWindow?.packageName
        service.lastField
            ?.takeIf { it.refresh() && it.isVisibleToUser && takesText(it) && (it.isFocused || it.packageName == app) }
            ?.let { return it }
        for (window in runCatching { service.windows }.getOrDefault(emptyList())) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = window.root ?: continue
            find(root) { n -> n.isFocused && takesText(n) }?.let { return it }
        }
        return null
    }

    /** Editable, or a custom editor that offers text actions without saying so. */
    fun takesText(node: AccessibilityNodeInfo) =
        node.isEditable || accepts(node, AccessibilityNodeInfo.ACTION_SET_TEXT) || accepts(node, AccessibilityNodeInfo.ACTION_PASTE)

    private fun accepts(node: AccessibilityNodeInfo, action: Int) = node.actionList.any { it.id == action }

    /** Breadth-first search, bounded so a huge tree can't stall the main thread. */
    private fun find(root: AccessibilityNodeInfo, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var seen = 0
        // Each child is a binder call on the main thread; stop well short of a stall.
        while (queue.isNotEmpty() && seen++ < 400) {
            val node = queue.removeFirst()
            if (match(node)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    /** Structure only, never the field's text: safe to log. */
    private fun describe(node: AccessibilityNodeInfo?): String {
        if (node == null) return "focus=none"
        val actions = node.actionList.joinToString(",") { action ->
            when (action.id) {
                AccessibilityNodeInfo.ACTION_SET_TEXT -> "setText"
                AccessibilityNodeInfo.ACTION_PASTE -> "paste"
                AccessibilityNodeInfo.ACTION_SET_SELECTION -> "setSelection"
                AccessibilityNodeInfo.ACTION_FOCUS -> "focus"
                AccessibilityNodeInfo.ACTION_CLICK -> "click"
                else -> action.id.toString()
            }
        }
        return "pkg=${node.packageName} class=${node.className} id=${node.viewIdResourceName} " +
            "editable=${node.isEditable} focused=${node.isFocused} textLen=${node.text?.length} " +
            "sel=${node.textSelectionStart}..${node.textSelectionEnd} children=${node.childCount} actions=[$actions]"
    }

    private fun splice(node: AccessibilityNodeInfo, text: String): Boolean {
        val raw = node.text?.toString().orEmpty()
        val hint = node.hintText?.toString()
        val current = if (node.isShowingHintText || (hint != null && raw == hint)) "" else raw
        var start = node.textSelectionStart
        var end = node.textSelectionEnd
        if (start < 0 || end < 0) {
            // Without a caret, only an empty field is safe to write.
            if (current.isNotEmpty()) return false
            start = 0
            end = 0
        }
        if (start > end) start = end.also { end = start }
        if (end > current.length) return false
        val insert = spaced(current.substring(0, start), current.substring(end), text)
        // Splice into the field's own text so links, mentions and styles it reports survive.
        val updated = if (current.isEmpty()) SpannableStringBuilder(insert)
        else SpannableStringBuilder(node.text).replace(start, end, insert)
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) return false
        val caret = start + insert.length
        node.performAction(
            AccessibilityNodeInfo.ACTION_SET_SELECTION,
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, caret)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, caret)
            },
        )
        return true
    }

    /** Adds the spaces a dictation needs to sit between the text around the caret. */
    fun spaced(before: String, after: String, text: String): String {
        if (text.isEmpty()) return text
        var result = text
        val first = result.first()
        if (before.isNotEmpty() && !before.last().isWhitespace() && (first.isLetterOrDigit() || first in "\"'(“‘")) {
            result = " $result"
        }
        if (after.isNotEmpty() && !after.first().isWhitespace() && after.first() !in ".,!?;:)]}…'’\"”" && !result.last().isWhitespace()) {
            result = "$result "
        }
        return result
    }
}
