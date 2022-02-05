package zyc.book

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.res.Resources
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.text.Layout
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ImageSpan
import android.text.style.UnderlineSpan
import android.view.*
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.chad.library.adapter.base.BaseQuickAdapter
import com.chad.library.adapter.base.viewholder.BaseViewHolder
import com.yanzhenjie.recyclerview.SwipeMenuBridge
import com.yanzhenjie.recyclerview.SwipeMenuItem
import com.yanzhenjie.recyclerview.SwipeRecyclerView
import com.zyc.arrow.builtins.dp
import kotlinx.android.synthetic.main.activity_main.*
import razerdp.basepopup.BasePopupWindow

class MainActivity : AppCompatActivity() {

    lateinit var popupWindow: BasePopupWindow

    private val underlineSpanMap = mutableMapOf<Line, Any>()

    private val bgColorSpanMap = mutableMapOf<Line, Any>()

    private val paraNotesMap = mutableMapOf<Int, MutableList<Note>>()

    private val lastBubbles = mutableListOf<Any?>()

    private val lastBubbleClicks = mutableListOf<Any?>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setPageContent()
        set_page.setOnClickListener { setPageContent() }
        setCustomSelectionActionModeCallback()
    }

    private fun setCustomSelectionActionModeCallback() {
        tv.customSelectionActionModeCallback = object : ActionMode.Callback {

            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                mode.menuInflater.inflate(R.menu.text_selection, menu)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.run {
                    removeItem(android.R.id.selectAll)
                    removeItem(android.R.id.copy)
                    removeItem(android.R.id.shareText)
                    removeItem(android.R.id.textAssist)
                    removeItem(
                        if (underlineSpanMap.containsKey(Line(tv.selectionStart, tv.selectionEnd))) {
                            R.id.underline
                        } else {
                            R.id.delete_underline
                        }
                    )
                }
                return false
            }

            @RequiresApi(Build.VERSION_CODES.Q)
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                val line = Line(tv.selectionStart, tv.selectionEnd)
                return when (item.itemId) {
                    R.id.underline -> {
                        val span = UnderlineSpan()
                        tv.spannable.setSpan(span, tv.selectionStart, tv.selectionEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        underlineSpanMap[line] = span
                        mode.finish()
                        true
                    }
                    R.id.delete_underline -> {
                        removeLineSpan(line, underlineSpanMap)
                        mode.finish()
                        true
                    }
                    R.id.note_down -> {
                        val selectionStart = tv.selectionStart
                        val selectionEnd = tv.selectionEnd
                        AlertDialog.Builder(this@MainActivity)
                            .setView(R.layout.dialog_create_note)
                            .setPositiveButton("确定") { dialogInterface, _ ->
                                val content = (dialogInterface as Dialog).findViewById<EditText>(R.id.et_note).text.toString()
                                if (content.isEmpty()) {
                                    Toast.makeText(this@MainActivity, "内容不能为空", Toast.LENGTH_SHORT).show()
                                    return@setPositiveButton
                                }
                                dialogInterface.dismiss()
                                handleCreateNote(content, selectionStart, selectionEnd, line)
                            }
                            .show()
                        mode.finish()
                        true
                    }
                    else -> false
                }
            }

            override fun onDestroyActionMode(mode: ActionMode) {
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun handleCreateNote(content: String, selectionStart: Int, selectionEnd: Int, line: Line) {
        val paraIndex = selectionStart / (TEXT.length + 9)

        val notes = paraNotesMap[paraIndex]
        val note = Note(content, line)
        if (notes == null) {
            paraNotesMap[paraIndex] = mutableListOf(note)
        } else {
            if (notes.contains(note)) {
                Toast.makeText(this, "存在相同内容的笔记，无法新建", Toast.LENGTH_SHORT).show()
                return
            } else {
                notes.add(note)
            }
        }

        val span = BackgroundColorSpan(Color.parseColor("#ffe384"))
        tv.spannable.setSpan(span, selectionStart, selectionEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        bgColorSpanMap[line] = span

        insertForBubble(paraIndex)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun insertForBubble(paraIndex: Int) {
        insertBubbleSpan(
            paraIndex,
            lastSpans = lastBubbles,
            spanCreator = { notes ->
                ImageSpan(
                    this@MainActivity,
                    drawTextToBitmap(this@MainActivity, notes.size.toString()),
                    ImageSpan.ALIGN_CENTER
                )
            }
        )

        insertBubbleSpan(
            paraIndex,
            lastSpans = lastBubbleClicks,
            spanCreator = { notes ->
                object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        handleParaNoteBubbleClicked(
                            notes,
                            onNoteDelete = { note -> // handle deletion
                                removeLineSpan(note.line, bgColorSpanMap)
                                insertForBubble(paraIndex)
                                if (paraNotesMap[paraIndex]!!.isEmpty()) {
                                    popupWindow.dismiss()
                                }
                            }
                        )
                    }
                }
            }
        )
        tv.movementMethod = LinkMovementMethod.getInstance()
    }

    fun removeLineSpan(line: Line, map: MutableMap<Line, Any>): Unit {
        val span = map[line]!!
        tv.spannable.removeSpan(span)
        map.remove(line)
    }

    private fun insertBubbleSpan(
        paraIndex: Int,
        lastSpans: MutableList<Any?>,
        spanCreator: (notes: MutableList<Note>) -> Any,
    ) {
        val lastSpan = lastSpans[paraIndex]
        if (lastSpan != null) {
            tv.spannable.removeSpan(lastSpan)
        }
        val notes = paraNotesMap[paraIndex]!!
        if (notes.isEmpty()) return

        val start = (TEXT.length + 9) * paraIndex + TEXT.length + 7
        lastSpans[paraIndex] = spanCreator(notes)
        tv.spannable.setSpan(
            lastSpans[paraIndex],
            start,
            start + 1,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    private fun ClickableSpan.handleParaNoteBubbleClicked(
        notes: MutableList<Note>,
        onNoteDelete: (Note) -> Unit
    ) {
        val (x, y) = getClickingCoordinate()

        @SuppressLint("InflateParams")
        val itemView = layoutInflater.inflate(R.layout.item_note, null)
        val textView = itemView.findViewById<TextView>(R.id.tv_note)!!
        var sum = 0
        for (note in notes) {
            textView.text = note.content
            itemView.measure(
                View.MeasureSpec.makeMeasureSpec(screenWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            sum += itemView.measuredHeight
        }
        val popY = if (sum + y <= screenHeight) y else y - sum

        popupWindow = object : BasePopupWindow(this@MainActivity) {
            init {
                setContentView(R.layout.popup_notes)
                val rv = findViewById<SwipeRecyclerView>(R.id.rv_note)
                setPopupGravityMode(GravityMode.RELATIVE_TO_ANCHOR, GravityMode.RELATIVE_TO_ANCHOR)

                rv.setSwipeMenuCreator { _, rightMenu, _ ->
                    rightMenu.addMenuItem(
                        SwipeMenuItem(this@MainActivity)
                            .setBackground(ColorDrawable(Color.parseColor("#d0021b")))
                            .setText("删除")
                            .setTextColor(Color.WHITE)
                            .setWidth(60.dp)
                            .setHeight(ViewGroup.LayoutParams.MATCH_PARENT)
                    )
                }
                rv.setOnItemMenuClickListener { menuBridge: SwipeMenuBridge, position: Int ->
                    handleDeleteNote(menuBridge, rv, position, onNoteDelete)
                }

                rv.adapter = object : BaseQuickAdapter<Note, BaseViewHolder>(R.layout.item_note, data = notes) {
                    override fun convert(holder: BaseViewHolder, item: Note) {
                        holder.setText(R.id.tv_note, item.content)
                    }
                }
            }
        }
        popupWindow.showPopupWindow(x, popY)
    }

    private fun handleDeleteNote(
        menuBridge: SwipeMenuBridge,
        rv: SwipeRecyclerView,
        position: Int,
        onNoteDelete: (Note) -> Unit
    ) {
        menuBridge.closeMenu()
        @Suppress("UNCHECKED_CAST")
        val adapter = rv.originAdapter as BaseQuickAdapter<Note, *>
        val note = adapter.getItem(position)
        adapter.removeAt(position)
        onNoteDelete(note)
    }

    private fun ClickableSpan.getClickingCoordinate(): Coordinate {
        val parentTextView = tv
        val parentTextViewRect = Rect()

        // Initialize values for the computing of clickedText position
        val completeText = parentTextView.text as SpannableString
        val textViewLayout: Layout = parentTextView.layout

        val startOffsetOfClickedText = completeText.getSpanStart(this).toDouble()
        val endOffsetOfClickedText = completeText.getSpanEnd(this).toDouble()
        val startXCoordinatesOfClickedText: Double = textViewLayout.getPrimaryHorizontal(startOffsetOfClickedText.toInt()).toDouble()
        val endXCoordinatesOfClickedText: Double = textViewLayout.getPrimaryHorizontal(endOffsetOfClickedText.toInt()).toDouble()

        // Get the rectangle of the clicked text
        val currentLineStartOffset: Int = textViewLayout.getLineForOffset(startOffsetOfClickedText.toInt())
        val currentLineEndOffset: Int = textViewLayout.getLineForOffset(endOffsetOfClickedText.toInt())
        val keywordIsInMultiLine = currentLineStartOffset != currentLineEndOffset
        textViewLayout.getLineBounds(currentLineStartOffset, parentTextViewRect)

        // Update the rectangle position to his real position on screen
        val parentTextViewLocation = intArrayOf(0, 0)
        parentTextView.getLocationOnScreen(parentTextViewLocation)

        val parentTextViewTopAndBottomOffset = (parentTextViewLocation[1] -
                parentTextView.scrollY +
                parentTextView.compoundPaddingTop).toDouble()
        parentTextViewRect.top += parentTextViewTopAndBottomOffset.toInt()
        parentTextViewRect.bottom += parentTextViewTopAndBottomOffset.toInt()

        parentTextViewRect.left += (parentTextViewLocation[0] +
                startXCoordinatesOfClickedText +
                parentTextView.compoundPaddingLeft -
                parentTextView.scrollX).toInt()
        parentTextViewRect.right = (parentTextViewRect.left +
                endXCoordinatesOfClickedText -
                startXCoordinatesOfClickedText).toInt()

        var x = (parentTextViewRect.left + parentTextViewRect.right) / 2
        val y = parentTextViewRect.bottom
        if (keywordIsInMultiLine) {
            x = parentTextViewRect.left
        }
        return Coordinate(x, y)
    }

    private fun setPageContent() {
        underlineSpanMap.clear()
        bgColorSpanMap.clear()
        paraNotesMap.clear()
        lastBubbles.clear()
        lastBubbleClicks.clear()

        val ssb = SpannableStringBuilder()
        for (i in 1..number_picker.value) {
            ssb.append("    ${i.toString().padStart(2, '0')}:")
            ssb.append(TEXT)
            ssb.append(" \n")
            lastBubbles.add(null)
            lastBubbleClicks.add(null)
        }
        tv.text = ssb

        scroll_view.post { scroll_view.fullScroll(View.FOCUS_UP) }
    }

    companion object {
        private var TEXT =
            "Lorem ipsum dolor sit amet, consectetur adipisicing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur. Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia deserunt mollit anim id est laborum."
    }
}

data class Line(val start: Int, val end: Int)

data class Note(val content: String, val line: Line)

data class Coordinate(val x: Int, val y: Int)

val screenWidth = Resources.getSystem().configuration.screenWidthDp.dp

val screenHeight = Resources.getSystem().configuration.screenHeightDp.dp

val TextView.spannable: Spannable
    get() = text as Spannable

fun getBitmapFromVectorDrawable(context: Context, drawableId: Int): Bitmap? {
    val drawable = ContextCompat.getDrawable(context, drawableId)
    val bitmap = Bitmap.createBitmap(
        drawable!!.intrinsicWidth,
        drawable.intrinsicHeight, Bitmap.Config.ARGB_8888
    )
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap
}

fun drawTextToBitmap(context: Context, text: String): Bitmap {
    val scale: Float = context.resources.displayMetrics.density

    val bitmap = getBitmapFromVectorDrawable(context, R.drawable.bubble_24)!!
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = Color.parseColor("#333333")
    // text size in pixels
    paint.textSize = (9 * scale).toInt().toFloat()
    // text shadow
    paint.setShadowLayer(1f, 0f, 1f, Color.DKGRAY)

    // draw text to the Canvas center
    val bounds = Rect()
    paint.getTextBounds(text, 0, text.length, bounds)
    val x: Int = (bitmap.width - bounds.width()) / 6
    val y: Int = (bitmap.height + bounds.height()) / 6
    canvas.drawText(text, x * scale, y * scale, paint)
    return bitmap
}