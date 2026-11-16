// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.keyboard.clipboard

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import helium314.keyboard.latin.ClipboardHistoryEntry
import helium314.keyboard.latin.ClipboardHistoryManager
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings

enum class ClipboardFilter {
    ALL, TEXT, IMAGES, FILES, PINNED
}

class ClipboardAdapter(
       val clipboardLayoutParams: ClipboardLayoutParams,
       val keyEventListener: OnKeyEventListener
) : RecyclerView.Adapter<ClipboardAdapter.ViewHolder>() {

    var clipboardHistoryManager: ClipboardHistoryManager? = null
        set(value) {
            field = value
            refreshEntries()
        }

    var filter: ClipboardFilter = ClipboardFilter.ALL
        set(value) {
            if (field != value) {
                field = value
                refreshEntries()
            }
        }

    private var displayedEntries: List<ClipboardHistoryEntry> = emptyList()

    var pinnedIconResId = 0
    var itemBackgroundId = 0
    var itemTypeFace: Typeface? = null
    var itemTextColor = 0
    var itemTextSize = 0f

    /** Rebuild the visible list after inserts, deletes, pin changes, or filter changes. */
    fun refreshEntries() {
        val all = clipboardHistoryManager?.getAllHistoryEntries().orEmpty()
        displayedEntries = when (filter) {
            ClipboardFilter.ALL -> all
            ClipboardFilter.TEXT -> all.filter { it.filename == null }
            ClipboardFilter.IMAGES -> all.filter {
                it.filename != null && it.mimeTypes?.any { type -> type.startsWith("image/") } == true
            }
            ClipboardFilter.FILES -> all.filter {
                it.filename != null && it.mimeTypes?.none { type -> type.startsWith("image/") } == true
            }
            ClipboardFilter.PINNED -> all.filter { it.isPinned }
        }
        notifyDataSetChanged()
    }

    fun getEntryId(position: Int): Long? = displayedEntries.getOrNull(position)?.id

    fun canSwipeDelete(position: Int): Boolean =
        displayedEntries.getOrNull(position)?.isPinned == false

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.clipboard_entry_key, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.setContent(displayedEntries.getOrNull(position))
    }

    override fun getItemCount() = displayedEntries.size

    inner class ViewHolder(
            view: View
    ) : RecyclerView.ViewHolder(view), View.OnClickListener, View.OnTouchListener, View.OnLongClickListener {

        private val pinnedIconView: ImageView
        private val contentTextView: TextView
        private val contentImageView: ImageView
        private val timestampView: TextView
        private val deleteButton: TextView

        init {
            view.apply {
                setOnClickListener(this@ViewHolder)
                setOnTouchListener(this@ViewHolder)
                setOnLongClickListener(this@ViewHolder)
                setBackgroundResource(itemBackgroundId)
                isHapticFeedbackEnabled = false
            }
            Settings.getValues().mColors.setBackground(view, ColorType.KEY_BACKGROUND)
            pinnedIconView = view.findViewById<ImageView>(R.id.clipboard_entry_pinned_icon).apply {
                visibility = View.GONE
                setImageResource(pinnedIconResId)
            }
            contentTextView = view.findViewById<TextView>(R.id.clipboard_entry_text_content).apply {
                typeface = itemTypeFace
                setTextColor(itemTextColor)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, itemTextSize)
            }
            contentImageView = view.findViewById(R.id.clipboard_entry_image_content)
            timestampView = view.findViewById(R.id.clipboard_entry_timestamp)
            deleteButton = view.findViewById<TextView>(R.id.clipboard_entry_delete).apply {
                contentDescription = view.context.getString(R.string.clipboard_history_delete_entry)
                setOnClickListener {
                    val id = itemView.tag as? Long ?: return@setOnClickListener
                    clipboardHistoryManager?.deleteHistoryEntry(id)
                }
            }
            clipboardLayoutParams.setItemProperties(view)
            val colors = Settings.getValues().mColors
            colors.setColor(pinnedIconView, ColorType.CLIPBOARD_PIN)
        }

        fun setContent(historyEntry: ClipboardHistoryEntry?) {
            if (historyEntry == null) return
            itemView.tag = historyEntry.id
            contentTextView.text = null
            contentImageView.setImageDrawable(null)
            if (historyEntry.filename != null) {
                historyEntry.setImageAndDescription(contentImageView, contentTextView)
            } else {
                contentTextView.text = historyEntry.text?.take(1000)
            }
            pinnedIconView.visibility = if (historyEntry.isPinned) View.VISIBLE else View.GONE
            contentImageView.visibility = if (historyEntry.filename != null) View.VISIBLE else View.GONE
            contentTextView.visibility = if (contentTextView.text.isNullOrEmpty()) View.GONE else View.VISIBLE
            timestampView.text = DateUtils.getRelativeTimeSpanString(
                historyEntry.timeStamp,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS
            )
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                keyEventListener.onKeyDown(view.tag as Long)
            }
            return false
        }

        override fun onClick(view: View) {
            keyEventListener.onKeyUp(view.tag as Long)
        }

        override fun onLongClick(view: View): Boolean {
            clipboardHistoryManager?.toggleClipPinned(view.tag as Long)
            return true
        }
    }
}
