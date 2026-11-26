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
import android.widget.Toast
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
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
import java.util.concurrent.atomic.AtomicBoolean

class ClipboardHistoryManager(
        private val latinIME: LatinIME
) : ClipboardManager.OnPrimaryClipChangedListener {

    private lateinit var clipboardManager: ClipboardManager
    private var clipboardSuggestionView: View? = null
    private var screenshotSuggestionView: View? = null
    private var screenshotSuggestionFilename: String? = null
    /** The image stays selected until the user explicitly keeps it or advances the queue. */
    private var screenshotQueuePinnedEntry: ClipboardHistoryEntry? = null
    /** A small action bar inside the IME suggestion strip; never opens a separate window. */
    private var screenshotOcrPromptEntry: ClipboardHistoryEntry? = null
    private var screenshotOcrPromptView: View? = null
    private val screenshotOcrBusy = AtomicBoolean(false)
    private var clipboardDao: ClipboardDao? = null
    private var tempPrimaryClip = false

    private var screenshotObserver: android.database.ContentObserver? = null
    @Volatile private var destroyed = false
    @Volatile private var clearingHistory = false
    @Volatile private var historyClearGeneration = 0L
    // MediaStore can be slow on vendor ROMs; never scan it on the keyboard's UI thread.
    private val screenshotWorker = android.os.HandlerThread("KlaviaturaScreenshotImport").apply { start() }
    private val screenshotHandler = android.os.Handler(screenshotWorker.looper)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val screenshotResumeImportToken = Any()

    fun onCreate() {
        clipboardManager = latinIME.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.addPrimaryClipChangedListener(this)
        clipboardDao = ClipboardDao.getInstance(latinIME)
        try {
            screenshotObserver = ru.klaviatura.clip.Screenshots.observer(latinIME, screenshotHandler) { uri, ts, desc ->
                // Observer callbacks run on screenshotWorker: don't copy/hash images on the UI thread.
                if (!destroyed && !clearingHistory && latinIME.mSettings.current.mClipboardHistoryEnabled) {
                    val generation = historyClearGeneration
                    val saved = clipboardDao?.addClipUri(ts, false, uri, desc, latinIME) == true
                    if (saved) {
                        reactivateScreenshotQueueForNewCapture()
                        mainHandler.post {
                        if (!destroyed && !clearingHistory && generation == historyClearGeneration
                                && latinIME.mSettings.current.mClipboardHistoryEnabled)
                            latinIME.refreshScreenshotSuggestion()
                        }
                    }
                    saved
                } else false
            }
        } catch (e: Throwable) { }
        if (latinIME.mSettings.current.mClipboardHistoryEnabled)
            fetchPrimaryClip()
    }

    fun onStartInputView() {
        if (!latinIME.mSettings.current.mClipboardHistoryEnabled || clearingHistory) return
        fetchPrimaryClip()
        if (getPrimaryClipIfText() != null) mainHandler.post {
            if (!destroyed && !clearingHistory && latinIME.mSettings.current.mClipboardHistoryEnabled)
                latinIME.tryShowClipboardSuggestion()
        }
        scheduleScreenshotRefresh("start-input-view")
    }

    /**
     * InputMethodService may hide and re-show the existing IME window when switching apps
     * without a new editor/input-view start callback. Treat actual window visibility as a
     * separate resume signal for screenshot imports.
     */
    fun onWindowShown() {
        if (!latinIME.mSettings.current.mClipboardHistoryEnabled || clearingHistory) return
        scheduleScreenshotRefresh("window-shown")
    }

    private fun scheduleScreenshotRefresh(trigger: String) {
        if (destroyed || clearingHistory || !latinIME.mSettings.current.mClipboardHistoryEnabled) return
        val generation = historyClearGeneration
        // Some vendor galleries publish the screenshot row/file after the IME has already
        // resumed. Retry on both input-view start and actual IME-window visibility.
        screenshotHandler.removeCallbacksAndMessages(screenshotResumeImportToken)
        val resumeStartedAt = android.os.SystemClock.uptimeMillis()
        android.util.Log.i(TAG, "scheduling screenshot scan: trigger=$trigger")
        for (delay in longArrayOf(0L, 700L, 1800L, 4000L)) {
            screenshotHandler.postAtTime({
                if (destroyed || clearingHistory || generation != historyClearGeneration
                        || !latinIME.mSettings.current.mClipboardHistoryEnabled) return@postAtTime
                val importedCount = ru.klaviatura.clip.Screenshots.importRecent(latinIME) { uri, ts, desc ->
                    !destroyed && !clearingHistory && generation == historyClearGeneration
                        && latinIME.mSettings.current.mClipboardHistoryEnabled
                        && clipboardDao?.addClipUri(ts, false, uri, desc, latinIME) == true
                }
                if (importedCount > 0) {
                    reactivateScreenshotQueueForNewCapture(importedCount)
                    screenshotHandler.removeCallbacksAndMessages(screenshotResumeImportToken)
                }
                if (importedCount > 0 || delay == 4000L) {
                    android.util.Log.i(TAG, "screenshot scan completed: trigger=$trigger delay=$delay imported=$importedCount")
                    mainHandler.post {
                        if (!destroyed && !clearingHistory && generation == historyClearGeneration
                                && latinIME.mSettings.current.mClipboardHistoryEnabled)
                            latinIME.refreshScreenshotSuggestion()
                    }
                }
            }, screenshotResumeImportToken, resumeStartedAt + delay)
        }
    }

    fun onDestroy() {
        destroyed = true
        screenshotOcrPromptEntry = null
        screenshotOcrPromptView = null
        screenshotQueuePinnedEntry = null
        clipboardManager.removePrimaryClipChangedListener(this)
        screenshotObserver?.let { runCatching { latinIME.contentResolver.unregisterContentObserver(it) } }
        screenshotHandler.removeCallbacksAndMessages(null)
        mainHandler.removeCallbacksAndMessages(null)
        screenshotWorker.quitSafely()
    }

    override fun onPrimaryClipChanged() {
        // Ignore the system callback produced by our own clear operation.
        if (clearingHistory) return
        // Make sure we read clipboard content only if history settings is set
        if (latinIME.mSettings.current.mClipboardHistoryEnabled) {
            fetchPrimaryClip()
            dontShowCurrentSuggestion = false
            // Copied text takes precedence over the screenshot queue chip.
            if (getPrimaryClipIfText() != null) mainHandler.post {
                if (!destroyed && !clearingHistory && latinIME.mSettings.current.mClipboardHistoryEnabled)
                    latinIME.tryShowClipboardSuggestion()
            }
        }
    }

    // todo for later
    //  setting whether to store sensitive clip data?
    //  care about other clip items than first?
    private fun fetchPrimaryClip() {
        if (tempPrimaryClip || clearingHistory) return // avoid updating history while swapping/clearing clips
        val clipData = try { clipboardManager.primaryClip } catch (e: Exception) { null } ?: return
        if (clipData.itemCount == 0) return
        val description = clipData.description ?: return
        val timeStamp = ClipboardManagerCompat.getClipTimestamp(clipData)
        var importedImageClip = false
        // Klaviatura 1.3.1: every item (gallery "copy" may put several images); image URIs are copied into app
        // storage right away, since the grant to read them ends with the clip.
        // Unlike MediaStore screenshot imports, this path used to save an image without notifying the
        // screenshot suggestion queue. On Android/vendor screenshot UIs that place the capture directly
        // on the primary clipboard, it appeared in History but never in the compact keyboard chip.
        for (i in 0 until clipData.itemCount) {
            val clipItem = clipData.getItemAt(i) ?: continue
            val uri = clipItem.uri
            val uriType = uri?.let { runCatching { latinIME.contentResolver.getType(it) }.getOrNull() }
            if (ru.klaviatura.clip.ClipKind.isFile(uri?.scheme, uriType, description.hasMimeType("text/*"), clipItem.text.isNullOrEmpty())
                    && maySaveFromUri(uri, latinIME)) {
                val desc = if (uriType != null && !description.hasMimeType(uriType))
                    android.content.ClipDescription(description.label, arrayOf(uriType)) else description
                val itemTimestamp = timeStamp + i
                val isImage = ru.klaviatura.clip.ClipKind.isImage(
                    uriType,
                    (0 until desc.mimeTypeCount).map { desc.getMimeType(it) }
                )
                val alreadyStoredAtTimestamp = isImage && clipboardDao?.getAll().orEmpty().any { entry ->
                    entry.timeStamp == itemTimestamp && entry.filename != null &&
                        entry.mimeTypes?.any { it.startsWith("image/") } == true
                }
                val saved = clipboardDao?.addClipUri(itemTimestamp, false, uri!!, desc, latinIME) == true
                if (saved && isImage && !alreadyStoredAtTimestamp) {
                    importedImageClip = true
                    Log.i(TAG, "saved image from primary clipboard: timestamp=$itemTimestamp mime=" + (uriType ?: "unknown"))
                }
            } else if (i == 0 && (description.hasMimeType("text/*") || clipItem.text != null)) {
                val content = clipItem.coerceToText(latinIME)
                if (TextUtils.isEmpty(content)) return
                clipboardDao?.addClip(timeStamp, false, content.toString())
            }
        }
        if (importedImageClip) {
            reactivateScreenshotQueueForNewCapture()
            mainHandler.post {
                if (!destroyed && !clearingHistory && latinIME.mSettings.current.mClipboardHistoryEnabled)
                    latinIME.refreshScreenshotSuggestion()
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

    // Keep the no-argument API for existing Java callers.
    fun clearHistory() = clearHistory(null)

    fun clearHistory(onComplete: (() -> Unit)?) {
        // Screenshot imports share one worker. Clear behind an import already in progress,
        // and cancel queued retries so an old MediaStore scan cannot repopulate the buffer.
        val generation = ++historyClearGeneration
        clearingHistory = true
        screenshotHandler.removeCallbacksAndMessages(null)
        screenshotHandler.post {
            val clearResult = runCatching { clipboardDao?.clear() }
            mainHandler.post {
                if (destroyed || generation != historyClearGeneration) return@post
                try {
                    if (clearResult.isFailure)
                        Log.w(TAG, "could not clear clipboard database", clearResult.exceptionOrNull())
                    screenshotSuggestionView = null
                    screenshotSuggestionFilename = null
                    screenshotOcrPromptEntry = null
                    screenshotOcrPromptView = null
                    screenshotQueuePinnedEntry = null
                    ClipboardManagerCompat.clearPrimaryClip(clipboardManager)
                    removeClipboardSuggestion()
                    latinIME.refreshScreenshotSuggestion()
                } catch (e: Throwable) {
                    Log.w(TAG, "clipboard clear completion failed", e)
                } finally {
                    clearingHistory = false
                    runCatching { onComplete?.invoke() }
                }
            }
        }
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

    fun getAllHistoryEntries() = clipboardDao?.getAll().orEmpty()

    fun deleteHistoryEntry(id: Long) = clipboardDao?.deleteClip(id)

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
        // Screenshots stay available in clipboard history; automatically showing an image card here
        // used to replace the live word predictions whenever a screenshot was imported.
        return primary ?: screenshotPermissionView(parent)
    }

    /** A recent screenshot is rendered as a compact extra item beside the live word suggestions. */
    fun getRecentScreenshotSuggestionView(editorInfo: EditorInfo?, parent: ViewGroup?): View? {
        if (!latinIME.mSettings.current.mSuggestClipboardContent || parent == null) return null
        val pendingPrompt = screenshotOcrPromptEntry
        if (pendingPrompt != null) {
            val promptFile = pendingPrompt.filename?.let { java.io.File(ClipboardDao.clipFilesDir, it) }
            if (promptFile?.isFile == true) {
                screenshotOcrPromptView?.let { return it }
                return createScreenshotOcrPromptView(pendingPrompt)
            }
            screenshotOcrPromptEntry = null
            screenshotOcrPromptView = null
        }
        if (latinIME.prefs().getBoolean(PREF_SCREENSHOT_QUEUE_DISMISSED, false)) {
            screenshotSuggestionView = null
            screenshotSuggestionFilename = null
            return null
        }
        val pinned = screenshotQueuePinnedEntry
        val entry = if (pinned?.filename?.let { java.io.File(ClipboardDao.clipFilesDir, it).isFile } == true) {
            pinned
        } else {
            screenshotQueuePinnedEntry = null
            recentImageEntry()
        }
        if (entry == null) {
            screenshotSuggestionView = null
            screenshotSuggestionFilename = null
            return null
        }
        if (entry.filename == screenshotSuggestionFilename) {
            screenshotSuggestionView?.let { return it }
        }
        return recentImageSuggestionView(editorInfo, parent, entry)
    }

    /** newest image clip of the last minutes (screenshots go only into the history, not into the primary clip) */
    fun recentImageEntry(now: Long = System.currentTimeMillis()): ClipboardHistoryEntry? {
        val prefs = latinIME.prefs()
        val dismissedFiles = prefs.getStringSet(PREF_DISMISSED_IMAGE_FILENAMES, emptySet()).orEmpty()
        val legacyDismissedTimestamp = prefs.getLong(PREF_DISMISSED_IMAGE_TIMESTAMP, 0L)
        return clipboardDao?.getAll()
            ?.asSequence()
            ?.filter { entry ->
                val filename = entry.filename
                filename != null &&
                    filename !in dismissedFiles &&
                    entry.mimeTypes?.any { it.startsWith("image/") } == true &&
                    entry.timeStamp > legacyDismissedTimestamp &&
                    now - entry.timeStamp in -60_000L..RECENT_IMAGE_MILLIS
            }
            // LIFO: offer the newest pending capture first; IDs order same-millisecond imports.
            ?.maxWithOrNull { first, second ->
                ru.klaviatura.clip.Screenshots.compareCaptureOrder(
                    first.timeStamp, first.id, second.timeStamp, second.id
                )
            }
    }

    private fun recentImageSuggestionView(
        editorInfo: EditorInfo?, parent: ViewGroup, entry: ClipboardHistoryEntry
    ): View? {
        val inputType = editorInfo?.inputType ?: InputType.TYPE_NULL
        if (InputTypeUtils.isNumberInputType(inputType) || InputTypeUtils.isPasswordInputType(inputType)) return null
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
        val density = latinIME.resources.displayMetrics.density
        binding.root.setPadding(0, 0, 0, 0)
        binding.root.layoutParams = android.widget.LinearLayout.LayoutParams((70 * density).toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
        imageView.layoutParams = android.widget.LinearLayout.LayoutParams((42 * density).toInt(), (42 * density).toInt())
        imageView.setImageBitmap(bmp)
        imageView.contentDescription = "Вставить скриншот"
        // The compact chip takes one narrow slot; word suggestions keep their own slots.
        textView.isGone = true
        val onClick = View.OnClickListener {
            dismissImageSuggestion(entry)
            try { latinIME.mKeyboardActionListener.onContent(entry.getContentInfo(latinIME)) } catch (e: Exception) { Log.w(TAG, "paste image", e) }
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, it, HapticEvent.KEY_PRESS)
            screenshotSuggestionView = null
            screenshotSuggestionFilename = null
            latinIME.refreshScreenshotSuggestion()
        }
        imageView.setOnClickListener(onClick)
        binding.root.setOnClickListener(onClick)
        // Short tap inserts the original image; a long press runs local OCR and inserts
        // readable text plus a JSON map of line/word coordinates into the active field.
        val onLongClick = View.OnLongClickListener {
            recognizeScreenshot(entry)
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, it, HapticEvent.KEY_PRESS)
            true
        }
        imageView.setOnLongClickListener(onLongClick)
        binding.root.setOnLongClickListener(onLongClick)
        imageView.contentDescription = "Нажмите, чтобы вставить изображение; удерживайте, чтобы распознать текст"
        styleSuggestion(binding, textView) {
            // Hide the whole current queue without deleting saved images from history.
            dismissAllRecentImageSuggestions()
            latinIME.refreshScreenshotSuggestion()
        }
        val closeSize = (22 * density).toInt()
        binding.clipboardSuggestionClose.layoutParams.width = closeSize
        binding.clipboardSuggestionClose.layoutParams.height = closeSize
        binding.clipboardSuggestionClose.contentDescription = "Скрыть предложение скриншота"
        screenshotSuggestionView = binding.root
        screenshotSuggestionFilename = entry.filename
        return screenshotSuggestionView
    }

    /**
     * Build the OCR decision inside the existing IME suggestion strip.
     * A separate dialog window can steal the input-window token and hide the keyboard on some ROMs.
     */
    private fun createScreenshotOcrPromptView(entry: ClipboardHistoryEntry): View {
        val colors = latinIME.mSettings.current.mColors
        // The parent screenshot slot is intentionally only 70dp wide. Keep all three
        // actions inside that slot; a text prompt pushed every button outside its bounds,
        // leaving an unresponsive-looking "OCR готов" chip on screen.
        val root = LinearLayout(latinIME).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            contentDescription = "Распознавание завершено. Действия со скриншотом."
        }
        fun addAction(symbol: String, description: String, action: () -> Unit) {
            val button = TextView(latinIME).apply {
                text = symbol
                contentDescription = description
                setTextColor(colors.get(ColorType.KEY_TEXT))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                isSingleLine = true
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    AudioAndHapticFeedbackManager.getInstance()
                        .performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, it, HapticEvent.KEY_PRESS)
                    action()
                }
            }
            root.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        // Only two actions: keep this image, or skip only this image.
        addAction("✓", "Оставить этот скриншот в очереди") {
            screenshotQueuePinnedEntry = entry
            clearScreenshotOcrPrompt()
            latinIME.refreshScreenshotSuggestion()
        }
        addAction("×", "Пропустить этот скриншот и показать следующий") {
            clearScreenshotOcrPrompt()
            dismissImageSuggestion(entry)
            latinIME.refreshScreenshotSuggestion()
        }
        colors.setBackground(root, ColorType.CLIPBOARD_SUGGESTION_BACKGROUND)
        screenshotOcrPromptView = root
        return root
    }

    private fun clearScreenshotOcrPrompt() {
        screenshotOcrPromptEntry = null
        screenshotOcrPromptView = null
        screenshotSuggestionView = null
        screenshotSuggestionFilename = null
    }

    private fun recognizeScreenshot(entry: ClipboardHistoryEntry) {
        if (!screenshotOcrBusy.compareAndSet(false, true)) {
            Toast.makeText(latinIME, "Распознавание уже выполняется", Toast.LENGTH_SHORT).show()
            return
        }
        val file = entry.filename?.let { java.io.File(ClipboardDao.clipFilesDir, it) }
        if (file == null || !file.isFile) {
            screenshotOcrBusy.set(false)
            Toast.makeText(latinIME, "Файл изображения больше недоступен", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(latinIME,
            "Распознаю локально. При первом запуске загружу более точные модели русского и английского (~30 МБ); потом можно без сети.",
            Toast.LENGTH_LONG).show()
        ru.klaviatura.clip.ScreenshotOcr.recognize(latinIME, file) { text, error ->
            screenshotOcrBusy.set(false)
            if (destroyed) return@recognize
            if (text.isNullOrBlank()) {
                val reason = error ?: "нет текста"
                Toast.makeText(latinIME, "Не удалось распознать изображение: " + reason, Toast.LENGTH_LONG).show()
            } else {
                // Set the prompt state before committing text: onTextInput may refresh the strip.
                screenshotOcrPromptEntry = entry
                screenshotOcrPromptView = null
                screenshotSuggestionView = null
                screenshotSuggestionFilename = null
                val inserted = runCatching { latinIME.onTextInput(text) }
                if (inserted.isFailure) {
                    clearScreenshotOcrPrompt()
                    Toast.makeText(latinIME, "Текст распознан, но не удалось вставить его в поле.", Toast.LENGTH_LONG).show()
                    Log.w(TAG, "could not insert screenshot OCR text", inserted.exceptionOrNull())
                    latinIME.refreshScreenshotSuggestion()
                } else {
                    runCatching { latinIME.refreshScreenshotSuggestion() }.onFailure {
                        Log.w(TAG, "could not refresh screenshot OCR prompt", it)
                    }
                }
            }
        }
    }

    /** an IME can't ask for permissions itself: offer a one-tap way to allow screenshots in the clipboard */
    private fun screenshotPermissionView(parent: ViewGroup): View? {
        if (!ru.klaviatura.clip.Screenshots.shouldOfferPermission(latinIME)) return null
        val binding = ClipboardSuggestionBinding.inflate(LayoutInflater.from(latinIME), parent, false)
        val textView = binding.clipboardSuggestionText
        textView.text = "📷 Разрешить скриншоты в буфере"
        textView.setOnClickListener {
            ru.klaviatura.clip.Screenshots.markOffered(latinIME)
            removeClipboardSuggestion(suppressPrimary = false)
            try { latinIME.startActivity(android.content.Intent(latinIME, ru.klaviatura.clip.MediaPermissionActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
        }
        styleSuggestion(binding, textView) { ru.klaviatura.clip.Screenshots.markOffered(latinIME); removeClipboardSuggestion(suppressPrimary = false) }
        clipboardSuggestionView = binding.root
        return clipboardSuggestionView
    }

    private fun dismissImageSuggestion(entry: ClipboardHistoryEntry) {
        // Pasting one image dismisses only that image; the next queued image can still be offered.
        if (screenshotQueuePinnedEntry?.id == entry.id) screenshotQueuePinnedEntry = null
        if (screenshotOcrPromptEntry?.id == entry.id) clearScreenshotOcrPrompt()
        val filename = entry.filename ?: return
        val prefs = latinIME.prefs()
        val dismissed = prefs.getStringSet(PREF_DISMISSED_IMAGE_FILENAMES, emptySet()).orEmpty().toMutableSet()
        if (dismissed.add(filename))
            prefs.edit().putStringSet(PREF_DISMISSED_IMAGE_FILENAMES, dismissed).apply()
    }

    /**
     * The close button dismisses the complete current screenshot batch.
     *
     * MediaStore DATE_ADDED has only second precision and imports can be delayed by the
     * screenshot observer. Store a cutoff at the end of the current second as well as
     * the known filenames, so a late import of an already-created screenshot cannot
     * immediately reopen the dismissed batch. A screenshot created in a later second
     * has a newer timestamp and becomes eligible normally.
     */
    private fun dismissAllRecentImageSuggestions() {
        screenshotQueuePinnedEntry = null
        clearScreenshotOcrPrompt()
        screenshotSuggestionView = null
        screenshotSuggestionFilename = null
        // Hide presentation only; new capture reactivates the still-pending queue.
        latinIME.prefs().edit().putBoolean(PREF_SCREENSHOT_QUEUE_DISMISSED, true).apply()
    }

    /** Restore legacy batch-closed entries when a fresh screenshot arrives. */
    private fun reactivateScreenshotQueueForNewCapture(importedCount: Int = 1) {
        // A fresh capture always takes the preview slot, even if an older image was pinned.
        // Older captures remain in history and become available after the newest one is used.
        screenshotQueuePinnedEntry = null
        screenshotSuggestionView = null
        screenshotSuggestionFilename = null
        val prefs = latinIME.prefs()
        val wasDismissed = prefs.getBoolean(PREF_SCREENSHOT_QUEUE_DISMISSED, false)
        val legacyCutoff = prefs.getLong(PREF_DISMISSED_IMAGE_TIMESTAMP, 0L)
        val editor = prefs.edit()
        if (legacyCutoff > 0L) {
            val legacyBatchFiles = clipboardDao?.getAll().orEmpty()
                .asSequence()
                .filter { entry -> entry.filename != null &&
                    entry.mimeTypes?.any { it.startsWith("image/") } == true &&
                    entry.timeStamp in (legacyCutoff - RECENT_IMAGE_MILLIS - 60_000L)..(legacyCutoff + 60_000L) }
                .mapNotNull { it.filename }
                .toSet()
            val dismissed = prefs.getStringSet(PREF_DISMISSED_IMAGE_FILENAMES, emptySet())
                .orEmpty().toMutableSet()
            dismissed.removeAll(legacyBatchFiles)
            editor.putStringSet(PREF_DISMISSED_IMAGE_FILENAMES, dismissed)
                .remove(PREF_DISMISSED_IMAGE_TIMESTAMP)
        }
        editor.putBoolean(
            PREF_SCREENSHOT_QUEUE_DISMISSED,
            ru.klaviatura.clip.Screenshots.keepQueueDismissed(wasDismissed, importedCount)
        ).apply()
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
            // Remove the external text card through the normal suggestion-strip path.
            // Hiding only its view leaves isExternalSuggestionVisible=true and keeps the
            // screenshot queue hidden even though the text has already been pasted.
            removeClipboardSuggestion()
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
            // Recompute the chip slot: a permission prompt may replace this card.
            latinIME.setNeutralSuggestionStrip()
            latinIME.mHandler.postResumeSuggestions(false)
        } else {
            latinIME.clearClipboardSuggestionView()
        }
        csv.isGone = true
        // The text card has released the external suggestion strip; restore the screenshot
        // queue in its own slot while keeping the three live word suggestions untouched.
        mainHandler.post { if (!destroyed) latinIME.refreshScreenshotSuggestion() }
    }

    companion object {
        private val TAG = "ClipboardHistoryManager"

        // avoid showing the current suggestion because it has been dismissed or pasted
        private var dontShowCurrentSuggestion: Boolean = false
        /** image suggestion that was pasted or dismissed */
        private const val PREF_DISMISSED_IMAGE_TIMESTAMP = "kl_clip_dismissed_image_timestamp" // legacy cutoff from 1.4.2
        private const val PREF_DISMISSED_IMAGE_FILENAMES = "kl_clip_dismissed_image_filenames"
        private const val PREF_SCREENSHOT_QUEUE_DISMISSED = "kl_clip_screenshot_queue_dismissed"
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
