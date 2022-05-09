package com.boe.board.widget

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.net.Uri
import android.provider.Settings
import android.text.TextPaint
import android.view.MotionEvent
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.core.widget.addTextChangedListener
import com.boe.board.*
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 文字组件 */
@Suppress("unused")
class TextWidget private constructor(boardWidth: Float, boardHeight: Float) :
    BaseWidget(boardWidth, boardHeight) {

    private var text: String = ""
    private var pages = emptyList<Page>()
    private var pageIdx = 0
    private val paint = TextPaint().apply {
        style = Paint.Style.FILL
        textSize = 28F
        isAntiAlias = true
        color = Color.BLACK
        strokeCap = Paint.Cap.ROUND
        letterSpacing = 0.1F
    }
    private var textLineH = paint.fontSpacing
    private val padding: Float by lazy { min(boardWidth, boardHeight) / 100 }

    private var lastTouchX: Float? = null
    private var lastTouchY: Float? = null

    // 编辑状态时光标信息，null表示不在编辑状态
    private var editCursor: EditCursor? = null

    // 光标宽度
    private val cursorWidth = 4F

    // 光标闪烁的计时
    private var cursorTimer: Timer? = null

    // 编辑文本时先记录当前文本，撤销重做时用到
    private var oldText: String = ""

    constructor(boardWidth: Float, boardHeight: Float, text: String) : this(
        boardWidth,
        boardHeight
    ) {
        this.rect = suggestedMaxInitialRect(boardWidth, boardHeight)
        this.text = text
        pageText(resetWidgetSize = true)
    }

    constructor(boardWidth: Float, boardHeight: Float, config: JSONObject) : this(
        boardWidth,
        boardHeight
    ) {
        fromConfig(config)
    }

    override fun fromConfig(config: JSONObject) {
        text = config.getString("text")
        pageIdx = config.optInt("pageIdx")
        pageText(resetWidgetSize = true)
        super.fromConfig(config)
    }

    override fun toConfig(): JSONObject {
        return super.toConfig().apply {
            put("text", text)
            put("pageIdx", pageIdx)
        }
    }

    override fun copy(): BaseWidget {
        val wd = TextWidget(boardWidth, boardHeight, toConfig())
        wd.rect = checkedCopyWidgetRect(rect)
        return wd
    }

    override fun undo(operation: Board.Step.Operation) {
        if (operation is ChangeContent) {
            val tmp = this.text
            this.text = operation.text
            operation.text = tmp
            pageText(resetWidgetSize = false)
        } else {
            super.undo(operation)
        }
    }

    override fun redo(operation: Board.Step.Operation) {
        if (operation is ChangeContent) {
            val tmp = this.text
            this.text = operation.text
            operation.text = tmp
            pageText(resetWidgetSize = false)
        } else {
            super.redo(operation)
        }
    }

    override fun onSelectStatusChanged() {
        super.onSelectStatusChanged()
        if (!selected) exitEditStatus()
    }

    override fun onTransformed(
        dx: Float,
        dy: Float,
        dw: Float,
        dh: Float,
        dr: Float,
        operateStatus: OperateStatus,
        addStep: Boolean
    ) {
        super.onTransformed(dx, dy, dw, dh, dr, operateStatus, addStep)
        if (dw != 0F || dh != 0F) pageText(resetWidgetSize = false)
    }

    override fun drawContent(canvas: Canvas) {
        if (pageIdx !in pages.indices) return
        // 绘制当页文字
        val lines = pages[pageIdx].lines
        for ((idx, ln) in lines.withIndex()) {
            canvas.drawText(
                ln,
                (rect.left + padding),
                rect.top + padding + textLineH * (idx + 1F),
                paint
            )
        }
        // 绘制光标
        editCursor?.let { cursor ->
            if (pageIdx == cursor.pageIdx && cursor.show) {
                val descent = paint.fontMetrics.descent
                canvas.drawRect(
                    cursor.x - cursorWidth / 2F,
                    cursor.y + descent,
                    cursor.x + cursorWidth / 2F,
                    cursor.y + textLineH + descent,
                    paint
                )
            }
        }
    }

    override fun touchContent(event: Board.TouchEvent, restoredPoint: PointF): Boolean {
        // 按下时判断是否在文本框内
        if (event.action == MotionEvent.ACTION_DOWN) {
            // 处于选中状态时再点一下才显示光标
            if (selected && rect.contains(restoredPoint.x, restoredPoint.y)) {
                lastTouchX = event.x
                lastTouchY = event.y
                return true
            } else {
                lastTouchX = null
                lastTouchY = null
            }
        }
        // 触摸事件不在文本框内的情况
        if (lastTouchX == null || lastTouchY == null) {
            exitEditStatus()
            return false
        }
        // 移动距离
        if (event.action == MotionEvent.ACTION_MOVE) {
            val gap = max(MOVE_MIN_SPACE, 5)
            return if (abs(event.x - lastTouchX!!) >= gap && abs(event.y - lastTouchY!!) >= gap) {
                lastTouchX = null
                lastTouchY = null
                exitEditStatus()
                false
            } else {
                true
            }
        }
        // 抬起时如果在文本框内，进入编辑状态
        if (event.action == MotionEvent.ACTION_UP) {
            if (rect.contains(restoredPoint.x, restoredPoint.y)) {
                enterEditStatus(restoredPoint.x, restoredPoint.y)
                lastTouchX = null
                lastTouchY = null
                return true
            }
        }
        // 其他情况
        exitEditStatus()
        lastTouchX = null
        lastTouchY = null
        return false
    }

    override fun getControllerButtons(): List<FrameAndController.CtlBtn> {
        return super.getControllerButtons().toMutableList().apply {
            // 上一页
            add(
                FrameAndController.CtlBtn(
                    resId = R.drawable.board_ic_pre,
                    onClick = {
                        if (pageIdx > 0) {
                            pageIdx--
                            callback?.onUpdate()
                        }
                    },
                    onDrag = null
                )
            )
            // 下一页
            add(
                FrameAndController.CtlBtn(
                    resId = R.drawable.board_ic_next,
                    onClick = {
                        if (pageIdx < pages.size - 1) {
                            pageIdx++
                            callback?.onUpdate()
                        }
                    },
                    onDrag = null
                )
            )
        }
    }

    // 文字分页
    private fun pageText(resetWidgetSize: Boolean) {
        if (text.isEmpty()) {
            if (pages.isNotEmpty()) {
                pages = emptyList()
                callback?.onUpdate()
            }
            return
        }
        val pages = mutableListOf<Page>()
        var lines = mutableListOf<String>()
        var sumH = 0F
        val maxW = rect.width() - padding * 2
        val maxH = rect.height() - padding * 2
        var pageW = 0F
        var pageH = 0F
        var pageStartIdx = 0
        var pageEndIdx = 0
        for (t in this.text.split(Regex("[\n\r]"))) {
            var text = t + "\n"
            while (text.isNotEmpty()) {
                val lineW = FloatArray(1)
                val idx = paint.breakText(text, true, maxW, lineW)
                if (idx <= 0) break
                lineW.first().let { if (it > pageW) pageW = it }
                val tmp = text.substring(0, idx)
                lines.add(tmp)
                pageEndIdx += tmp.length
                sumH += textLineH
                text = text.substring(idx)
                if (sumH > pageH) pageH = sumH
                // 满一页的情况
                if (sumH + textLineH > maxH) {
                    pages.add(Page(lines, pageStartIdx, pageEndIdx))
                    lines = mutableListOf()
                    sumH = 0F
                    pageStartIdx = pageEndIdx
                }
            }
        }
        if (lines.isNotEmpty()) pages.add(Page(lines, pageStartIdx, pageEndIdx))
        if (this.pages == pages) return
        this.pages = pages
        if (pageIdx >= pages.size) pageIdx = pages.size - 1
        if (resetWidgetSize && pageW > 0 && pageH > 0) {
            if (maxW != pageW || maxH != sumH) {
                size(
                    left = rect.left,
                    top = rect.top,
                    right = rect.left + pageW + padding * 2,
                    bottom = rect.top + pageH + padding * 2,
                    operateStatus = OperateStatus.END,
                    addStep = false
                )
            }
        }
        if (editCursor != null) editCursor = editCursor?.let { findEditCursorByCharIdx(it.charIdx) }
        callback?.onUpdate()
    }

    // 进入编辑状态，显示软键盘
    private fun enterEditStatus(touchX: Float, touchY: Float) {
        if (text.isEmpty()) return
        // 判断点击位置的字符在全文本里的索引
        val ec = findEditCursorByTouchPoint(touchX, touchY) ?: return
        editCursor = ec
        if (cursorTimer == null) {
            cursorTimer = Timer().apply {
                schedule(object : TimerTask() {
                    override fun run() {
                        val editCursor = editCursor
                        if (editCursor == null) {
                            cursorTimer?.cancel()
                            cursorTimer = null
                        } else {
                            editCursor.show = !editCursor.show
                            callback?.onUpdate()
                        }
                    }
                }, 1000, 1000)
            }
        }
        // 弹出隐形的EditText，设置text和selection
        Board.appContext?.let { ctx ->
            oldText = text
            InputDialog.show(
                context = ctx,
                text = text,
                selection = ec.charIdx + 1,
                onInput = { t, s ->
                    text = t
                    editCursor?.run { if (charIdx != s) charIdx = s }
                    pageText(resetWidgetSize = false)
                },
                onFinish = ::exitEditStatus
            )
        }
    }

    // 退出编辑状态
    private fun exitEditStatus() {
        if (editCursor == null) return
        editCursor = null
        cursorTimer?.cancel()
        cursorTimer = null
        InputDialog.hide()
        callback?.onUpdate()
        callback?.onStep(Board.Step(this, ChangeContent(oldText)))
    }

    // 判断点击位置的字符在全文本里的索引
    private fun findEditCursorByTouchPoint(touchX: Float, touchY: Float): EditCursor? {
        val tmpIdx = if (pageIdx <= 0) 0 else pages.subList(0, pageIdx)
            .sumOf { page -> page.lines.sumOf { it.length } }
        val cursor = EditCursor(0F, 0F, pageIdx, tmpIdx, true)
        for ((idx, ln) in pages[pageIdx].lines.withIndex()) {
            val startY = rect.top + padding + idx * textLineH
            val endY = startY + textLineH
            if (touchY !in startY..endY) {
                cursor.charIdx += ln.length
                continue
            }
            cursor.y = startY
            val widthArray = FloatArray(ln.length)
            paint.getTextWidths(ln, widthArray)
            var sumX = rect.left + padding
            for ((i, w) in widthArray.withIndex()) {
                val last = sumX
                sumX += w.toInt()
                if (touchX !in last..sumX) continue
                cursor.charIdx += i
                cursor.x = sumX
                return cursor
            }
        }
        return null
    }

    // 大小改变后重新计算光标位置
    private fun findEditCursorByCharIdx(charIdx: Int): EditCursor? {
        if (pages.isEmpty()) return null
        var cnt = 0
        for ((pi, page) in pages.withIndex()) {
            for ((li, ln) in page.lines.withIndex()) {
                cnt += ln.length
                if (charIdx >= cnt) continue
                val text = ln.substring(0, charIdx - (cnt - ln.length))
                return EditCursor(
                    x = paint.measureText(text) + rect.left + padding,
                    y = rect.top + padding + li * textLineH,
                    pageIdx = pi,
                    charIdx = charIdx,
                    show = true
                )
            }
        }
        return null
    }

    // 每页的文字
    private data class Page(val lines: List<String>, val startIdx: Int, val endIdx: Int)

    // 编辑状态时的光标
    private data class EditCursor(
        var x: Float,
        var y: Float,
        var pageIdx: Int,
        var charIdx: Int,
        var show: Boolean
    )

    // 操作步骤记录里的数据
    private data class ChangeContent(var text: String) : Board.Step.Operation()
}

/** 隐藏的输入弹框 */
private object InputDialog {

    private var lo: WeakReference<EditText>? = null

    fun show(
        context: Context,
        text: String,
        selection: Int,
        onInput: (String, Int) -> Unit,
        onFinish: () -> Unit
    ) {
        if (!Settings.canDrawOverlays(context)) {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            return
        }
        lo?.get()?.run {
            setSelection(selection)
            showSoftKeyboard()
            return
        }
        val et = EditText(context)
        et.setText(text)
        et.setSingleLine()
        et.imeOptions = EditorInfo.IME_ACTION_DONE
        et.setSelection(selection)
        et.addTextChangedListener { onInput.invoke(it.toString(), et.selectionEnd) }
        et.setOnEditorActionListener { _, _, _ ->
            onFinish.invoke()
            true
        }
        et.postDelayed({ et.showSoftKeyboard() }, 1000)
        et.afterLayoutChanged { }
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val wmLp = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            width = 1
            height = 1
        }
        mainHandler.post {
            wm.addView(et, wmLp)
            lo = WeakReference(et)
        }
    }

    fun hide() {
        val lo = lo?.get() ?: return
        lo.hideSoftKeyboard()
        val wm = lo.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        mainHandler.post {
            wm.removeView(lo)
            InputDialog.lo = null
        }
    }
}