// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.latin

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.InputType
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.view.inputmethod.InputContentInfoCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import helium314.keyboard.keyboard.KeyboardTypeface
import helium314.keyboard.compat.ClipboardManagerCompat
import helium314.keyboard.event.Event
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.common.isValidNumber
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.databinding.ClipboardSuggestionBinding
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.InputTypeUtils
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.prefs
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ClipboardHistoryManager(
        private val latinIME: LatinIME
) : ClipboardManager.OnPrimaryClipChangedListener {

    private lateinit var clipboardManager: ClipboardManager
    private var clipboardSuggestionView: View? = null
    private var clipboardDao: ClipboardDao? = null
    private var tempPrimaryClip = false

    private var screenshotObserver: android.database.ContentObserver? = null

    fun onCreate() {
        clipboardManager = latinIME.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.addPrimaryClipChangedListener(this)
        clipboardDao = ClipboardDao.getInstance(latinIME)
        try {
            screenshotObserver = ru.klaviatura.clip.Screenshots.observer(latinIME, android.os.Handler(android.os.Looper.getMainLooper())) { uri, ts, desc ->
                if (latinIME.mSettings.current.mClipboardHistoryEnabled) { clipboardDao?.addClipUri(ts, false, uri, desc, latinIME); refreshStripForImage() } }
        } catch (e: Throwable) { }
        if (latinIME.mSettings.current.mClipboardHistoryEnabled)
            fetchPrimaryClip()
    }

    /** a screenshot arrived while the keyboard is open: show its thumbnail right away (unless a word is being typed) */
    private fun refreshStripForImage() {
        try {
            if (latinIME.isInputViewShown && latinIME.mInputLogic.getComposingLength() == 0) latinIME.setNeutralSuggestionStrip()
        } catch (e: Throwable) { }
    }

    fun onStartInputView() {
        if (!latinIME.mSettings.current.mClipboardHistoryEnabled) return
        fetchPrimaryClip()
        ru.klaviatura.clip.Screenshots.importRecent(latinIME) { uri, ts, desc -> clipboardDao?.addClipUri(ts, false, uri, desc, latinIME) }
    }

    fun onDestroy() {
        clipboardManager.removePrimaryClipChangedListener(this)
        screenshotObserver?.let { runCatching { latinIME.contentResolver.unregisterContentObserver(it) } }
    }

    override fun onPrimaryClipChanged() {
        // Make sure we read clipboard content only if history settings is set
        if (latinIME.mSettings.current.mClipboardHistoryEnabled) {
            fetchPrimaryClip()
            dontShowCurrentSuggestion = false
        }
    }

    // todo for later
    //  setting whether to store sensitive clip data?
    //  care about other clip items than first?
    private fun fetchPrimaryClip() {
        if (tempPrimaryClip) return // avoid updating history
        val clipData = try { clipboardManager.primaryClip } catch (e: Exception) { null } ?: return
        if (clipData.itemCount == 0) return
        val description = clipData.description ?: return
        val timeStamp = ClipboardManagerCompat.getClipTimestamp(clipData)
        // Klaviatura 1.3.1: every item (gallery "copy" may put several images); image URIs are copied into app
        // storage right away, since the grant to read them ends with the clip
        for (i in 0 until clipData.itemCount) {
            val clipItem = clipData.getItemAt(i) ?: continue
            val uri = clipItem.uri
            val uriType = uri?.let { runCatching { latinIME.contentResolver.getType(it) }.getOrNull() }
            if (ru.klaviatura.clip.ClipKind.isFile(uri?.scheme, uriType, description.hasMimeType("text/*"), clipItem.text.isNullOrEmpty())
                    && maySaveFromUri(uri, latinIME)) {
                val desc = if (uriType != null && !description.hasMimeType(uriType))
                    android.content.ClipDescription(description.label, arrayOf(uriType)) else description
                clipboardDao?.addClipUri(timeStamp + i, false, uri!!, desc, latinIME)
            } else if (i == 0 && (description.hasMimeType("text/*") || clipItem.text != null)) {
                val content = clipItem.coerceToText(latinIME)
                if (TextUtils.isEmpty(content)) return
                clipboardDao?.addClip(timeStamp, false, content.toString())
            }
        }
    }

    fun getPrimaryClipIfText(): String? {
        if (tempPrimaryClip) return null // avoid updating history
        val clipData = clipboardManager.primaryClip ?: return null
        if (clipData.itemCount == 0) return null
        val clipItem = clipData.getItemAt(0) ?: return null
        return if (clipData.description?.hasMimeType("text/*") == true)
            clipItem.coerceToText(latinIME).toString().takeIf { it.isNotEmpty() }
        else null
    }

    // fallback method because in some apps there is no supported mime type and commitContend does nothing,
    // but KeyEvent.KEYCODE_PASTE for pasting from primary clip works fine
    // (actually we do change the primary clip, but (try to) revert immediately)
    fun pasteWithoutChangingClips(content: InputContentInfoCompat) {
        Log.d(TAG, "trying fallback pasting with system clipboard")
        val primaryClip = clipboardManager.primaryClip
        val tempClip = ClipData(content.description, ClipData.Item(content.contentUri))
        tempPrimaryClip = true
        clipboardManager.setPrimaryClip(tempClip)
        latinIME.onEvent(Event.createSoftwareKeypressEvent(KeyCode.CLIPBOARD_PASTE, 0,
            Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false))
        tempPrimaryClip = false
        if (primaryClip == null)
            return
        // we need to wait a little before switching back to the original primary clip
        // a. it can happen that we switch back before the pasting has started, in that case we only past the primary clip
        // b. if we switch while the clip is pasted, it might crash the app (tested with joplin and logseq)
        // todo: replacing the current primary clip is far from ideal, try finding a different way
        GlobalScope.launch {
            delay(500)
            try {
                clipboardManager.setPrimaryClip(primaryClip)
            } catch (e: Exception) {
                Log.i(TAG, "could not go back to old primary clip", e)
                // happens wen the clip was a file
                // try to find it in out clipboard entries
                val clip = clipboardDao?.getAll()?.firstOrNull { it.timeStamp == ClipboardManagerCompat.getClipTimestamp(primaryClip) }
                if (clip?.filename != null)
                    clipboardManager.setPrimaryClip(ClipData(
                        ClipDescription(clip.text, clip.mimeTypes?.toTypedArray()),
                        ClipData.Item(clip.getContentUri(latinIME))
                    ))
                else if (clip != null)
                    clipboardManager.setPrimaryClip(ClipData(
                        ClipDescription("", arrayOf("text/*")),
                        ClipData.Item(clip.text)
                    ))
            }
        }
    }

    fun toggleClipPinned(id: Long) {
        clipboardDao?.togglePinned(id)
    }

    fun clearHistory() {
        clipboardDao?.clearNonPinned()
        ClipboardManagerCompat.clearPrimaryClip(clipboardManager)
        removeClipboardSuggestion()
    }

    fun canRemove(index: Int) = clipboardDao?.isPinned(index) == false

    fun removeEntry(index: Int) {
        if (canRemove(index))
            clipboardDao?.deleteClipAt(index)
    }

    fun sortHistoryEntries() {
        clipboardDao?.sort()
    }

    // We do not want to update history while user is visualizing it, so we check retention only
    // when history is about to be shown
    fun prepareClipboardHistory() = clipboardDao?.clearOldClips(true)

    fun getHistorySize() = clipboardDao?.count() ?: 0

    fun getHistoryEntry(position: Int) = clipboardDao?.getAt(position)

    fun getHistoryEntryContent(id: Long) = clipboardDao?.get(id)

    fun setHistoryChangeListener(listener: ClipboardDao.Listener?) {
        clipboardDao?.listener = listener
    }

    private fun isClipSensitive(inputType: Int): Boolean {
        ClipboardManagerCompat.getClipSensitivity(clipboardManager.primaryClip?.description)?.let { return it }
        return InputTypeUtils.isPasswordInputType(inputType)
    }

    fun getClipboardSuggestionView(editorInfo: EditorInfo?, parent: ViewGroup?): View? {
        // maybe no need to create a new view
        // but a cache has to consider a few possible changes, so better don't implement without need
        clipboardSuggestionView = null

        if (!latinIME.mSettings.current.mSuggestClipboardContent) return null
        if (parent == null) return null
        val primary = if (dontShowCurrentSuggestion) null else primaryClipSuggestionView(editorInfo, parent)
        // Klaviatura 1.4.0: fresh screenshots / copied images from the history appear as a thumbnail above the keys
        return primary ?: recentImageSuggestionView(editorInfo, parent) ?: screenshotPermissionView(parent)
    }

    /** newest image clip of the last minutes (screenshots go only into the history, not into the primary clip) */
    fun recentImageEntry(now: Long = System.currentTimeMillis()): ClipboardHistoryEntry? =
        clipboardDao?.getAll()?.filter { it.filename != null && it.mimeTypes?.any { m -> m.startsWith("image/") } == true }
            ?.maxByOrNull { it.timeStamp }
            ?.takeIf { now - it.timeStamp in -60_000L..RECENT_IMAGE_MILLIS && it.id != dismissedImageId }

    private fun recentImageSuggestionView(editorInfo: EditorInfo?, parent: ViewGroup): View? {
        val inputType = editorInfo?.inputType ?: InputType.TYPE_NULL
        if (InputTypeUtils.isNumberInputType(inputType) || InputTypeUtils.isPasswordInputType(inputType)) return null
        val entry = recentImageEntry() ?: return null
        val file = java.io.File(ClipboardDao.clipFilesDir, entry.filename!!)
        if (!file.isFile) return null
        val binding = ClipboardSuggestionBinding.inflate(LayoutInflater.from(latinIME), parent, false)
        val textView = binding.clipboardSuggestionText
        val imageView = binding.clipboardSuggestionImage
        val h = (parent.height.takeIf { it > 0 } ?: (40 * latinIME.resources.displayMetrics.density).toInt())
        val bmp = try {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, o)
            o.inSampleSize = maxOf(1, minOf(o.outWidth, o.outHeight) / maxOf(1, h * 2))
            o.inJustDecodeBounds = false
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, o)
        } catch (e: Throwable) { null } ?: return null
        imageView.isVisible = true
        imageView.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        imageView.adjustViewBounds = false
        imageView.layoutParams = imageView.layoutParams.apply { height = h; width = (h * 1.3f).toInt() }
        imageView.setImageBitmap(bmp)
        imageView.contentDescription = entry.text ?: "image"
        textView.layoutParams = (textView.layoutParams as android.widget.LinearLayout.LayoutParams).apply { width = ViewGroup.LayoutParams.WRAP_CONTENT; weight = 0f }
        textView.text = if (entry.text?.contains("Скрин") == true) "Скриншот" else "Картинка"
        val onClick = View.OnClickListener {
            dismissedImageId = entry.id
            try { latinIME.mKeyboardActionListener.onContent(entry.getContentInfo(latinIME)) } catch (e: Exception) { Log.w(TAG, "paste image", e) }
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, it, HapticEvent.KEY_PRESS)
            binding.root.isGone = true
            latinIME.setNeutralSuggestionStrip()
        }
        imageView.setOnClickListener(onClick)
        textView.setOnClickListener(onClick)
        styleSuggestion(binding, textView) { dismissedImageId = entry.id; removeClipboardSuggestion(suppressPrimary = false) }
        clipboardSuggestionView = binding.root
        return clipboardSuggestionView
    }

    /** an IME can't ask for permissions itself: offer a one-tap way to allow screenshots in the clipboard */
    private fun screenshotPermissionView(parent: ViewGroup): View? {
        if (!ru.klaviatura.clip.Screenshots.shouldOfferPermission(latinIME)) return null
        val binding = ClipboardSuggestionBinding.inflate(LayoutInflater.from(latinIME), parent, false)
        val textView = binding.clipboardSuggestionText
        textView.text = "📷 Разрешить скриншоты в буфере"
        textView.setOnClickListener {
            ru.klaviatura.clip.Screenshots.markOffered(latinIME)
            binding.root.isGone = true
            try { latinIME.startActivity(android.content.Intent(latinIME, ru.klaviatura.clip.MediaPermissionActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
        }
        styleSuggestion(binding, textView) { ru.klaviatura.clip.Screenshots.markOffered(latinIME); removeClipboardSuggestion(suppressPrimary = false) }
        clipboardSuggestionView = binding.root
        return clipboardSuggestionView
    }

    private fun styleSuggestion(binding: ClipboardSuggestionBinding, textView: android.widget.TextView, onClose: () -> Unit) {
        KeyboardTypeface.applyToTextView(textView)
        val closeButton = binding.clipboardSuggestionClose
        closeButton.setImageDrawable(KeyboardIconsSet.instance.getIconDrawable(ToolbarKey.CLOSE_HISTORY.name.lowercase()))
        closeButton.layoutParams.width = textView.lineHeight
        closeButton.layoutParams.height = textView.lineHeight
        closeButton.setOnClickListener { onClose() }
        val colors = latinIME.mSettings.current.mColors
        textView.setTextColor(colors.get(ColorType.KEY_TEXT))
        colors.setColor(closeButton, ColorType.REMOVE_SUGGESTION_ICON)
        colors.setBackground(binding.root, ColorType.CLIPBOARD_SUGGESTION_BACKGROUND)
    }

    private fun primaryClipSuggestionView(editorInfo: EditorInfo?, parent: ViewGroup): View? {
        val clipData = try { clipboardManager.primaryClip } catch (e: Exception) { null } ?: return null
        if (clipData.itemCount == 0) return null
        val clipItem = clipData.getItemAt(0) ?: return null
        val hasText = clipData.description?.hasMimeType("text/*") == true
        val hasImage = clipData.description?.hasMimeType("image/*") == true && clipItem.uri != null
        if (!hasText && !hasImage) return null
        val timeStamp = ClipboardManagerCompat.getClipTimestamp(clipData)
        if (System.currentTimeMillis() - timeStamp > RECENT_TIME_MILLIS) return null
        val content = clipItem.coerceToText(latinIME)

        // create the view
        val binding = ClipboardSuggestionBinding.inflate(LayoutInflater.from(latinIME), parent, false)
        val textView = binding.clipboardSuggestionText
        val clipIcon = KeyboardIconsSet.instance.getIconDrawable(ToolbarKey.PASTE.name.lowercase())
        clipIcon?.setBounds(0, 0, textView.lineHeight, textView.lineHeight) // scale the icon to the text
        textView.setCompoundDrawablesRelative(clipIcon, null, null, null)
        val inputType = editorInfo?.inputType ?: InputType.TYPE_NULL
        if (hasText) {
            if (TextUtils.isEmpty(content)) return null
            if (InputTypeUtils.isNumberInputType(inputType) && !content.isValidNumber()) return null
            val shortenedContent = content.take(200) // for display only
            KeyboardTypeface.applyToTextView(textView)
            textView.text = (if (isClipSensitive(inputType)) "*".repeat(shortenedContent.length) else shortenedContent)
        }
        val onClickListener = View.OnClickListener {
            dontShowCurrentSuggestion = true
            if (hasText) latinIME.onTextInput(content.toString())
            else latinIME.onEvent(Event.createSoftwareKeypressEvent(KeyCode.CLIPBOARD_PASTE, 0,
                Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false))
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, it, HapticEvent.KEY_PRESS)
            binding.root.isGone = true
        }
        textView.setOnClickListener(onClickListener)

        if (hasImage) {
            if (InputTypeUtils.isNumberInputType(inputType)) return null
            val imageView = binding.clipboardSuggestionImage
            imageView.isVisible = true
            try {
                imageView.setImageURI(clipItem.uri)
            } catch (e: Exception) {
                Log.w(TAG, "error setting clipboard image", e) // happens with SecurityException: Permission Denial
                return null
            }
            imageView.setOnClickListener(onClickListener)
        }

        val closeButton = binding.clipboardSuggestionClose
        closeButton.setImageDrawable(KeyboardIconsSet.instance.getIconDrawable(ToolbarKey.CLOSE_HISTORY.name.lowercase()))
        closeButton.layoutParams.width = textView.lineHeight // scale the icon to the text
        closeButton.layoutParams.height = textView.lineHeight
        closeButton.setOnClickListener { removeClipboardSuggestion() }

        val colors = latinIME.mSettings.current.mColors
        textView.setTextColor(colors.get(ColorType.KEY_TEXT))
        clipIcon?.let { colors.setColor(it, ColorType.CLIPBOARD_SUGGESTION_ICON) }
        colors.setColor(closeButton, ColorType.REMOVE_SUGGESTION_ICON)
        colors.setBackground(binding.root, ColorType.CLIPBOARD_SUGGESTION_BACKGROUND)

        clipboardSuggestionView = binding.root
        return clipboardSuggestionView
    }

    private fun removeClipboardSuggestion(suppressPrimary: Boolean = true) {
        if (suppressPrimary) dontShowCurrentSuggestion = true
        val csv = clipboardSuggestionView ?: return
        if (csv.parent != null && !csv.isGone) {
            // clipboard view is shown ->
            latinIME.setNeutralSuggestionStrip()
            latinIME.mHandler.postResumeSuggestions(false)
        }
        csv.isGone = true
    }

    companion object {
        private val TAG = "ClipboardHistoryManager"

        // avoid showing the current suggestion because it has been dismissed or pasted
        private var dontShowCurrentSuggestion: Boolean = false
        /** image suggestion that was pasted or dismissed */
        private var dismissedImageId: Long = -1
        const val RECENT_IMAGE_MILLIS = 10 * 60 * 1000L

        const val RECENT_TIME_MILLIS = 3 * 60 * 1000L // 3 minutes (for clipboard suggestions)

        private fun maySaveFromUri(uri: Uri?, context: Context): Boolean {
            val maxSize = context.prefs().getInt(Settings.PREF_CLIPBOARD_FILES_SIZE_LIMIT, Defaults.PREF_CLIPBOARD_FILES_SIZE_LIMIT)
            val saveUriData = context.prefs().getBoolean(Settings.PREF_CLIPBOARD_USE_FILES, Defaults.PREF_CLIPBOARD_USE_FILES)
            if (uri == null || !saveUriData) return false
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null).use {
                    // size unknown (some screenshot/gallery providers): allow, the size limit is enforced after copying
                    if (it?.moveToFirst() != true || it.isNull(0)) return true
                    val size = it.getLong(0)
                    return size <= maxSize * 1000000L // maxSize is megabytes
                }
            } catch (e: Exception) {
                Log.w(TAG, "error checking clip size", e) // SecurityException on some providers (ColorOS gallery):
                return true // still try to copy, the copy itself is guarded and the size limit applies afterwards
            }
        }
    }
}
