package com.boe.board

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.HandlerThread
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.graphics.scale
import com.boe.board.widget.*

/** 画板View */
@Suppress("unused")
class BoardView(context: Context) : FrameLayout(context) {

    var board: Board? = null
        private set
    private var onBoardHandwriteStatusChangeListener: ((Boolean) -> Unit)? = null

    private var shapeBoBeAdd: Shape? = null

    // View的onTouch事件也放到子线程里处理
    private val handlerThread = HandlerThread("Board")
    private val eventHandler: Handler

    // 手写功能识别用的id
    private val hwId: Int by lazy { this.hashCode() }

    init {
        handlerThread.start()
        eventHandler = Handler(handlerThread.looper)
        afterLayoutChanged {
            val upperView = LayerView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            val activeView = LayerView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            val lowerView = LayerView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            board = Board(
                context = context,
                width = width.toFloat(),
                height = height.toFloat(),
                onUpperWidgetsUpdate = upperView::postInvalidate,
                onActiveWidgetsUpdate = activeView::postInvalidate,
                onLowerWidgetsUpdate = lowerView::postInvalidate
            )
            board?.onHandwriteStatusChangeListener = {
                post { onBoardHandwriteStatusChangeListener?.invoke(it) }
            }
            upperView.onDrawCall = { board?.drawUpperWidgets(it) }
            activeView.onDrawCall = { board?.drawActiveWidgets(it) }
            lowerView.onDrawCall = { board?.drawLowerWidgets(it) }
            addView(lowerView)
            addView(activeView)
            addView(upperView)
        }
    }

    /** 画板的点击事件 */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val touchEvent = Board.TouchEvent(
            action = event.action,
            x = event.x,
            y = event.y,
            pressure = event.pressure,
            toolType = if (handwriteStatus) MotionEvent.TOOL_TYPE_STYLUS else event.getToolType(0)
        )
        eventHandler.post { onTouch(touchEvent) }
        return true
    }

    /** 销毁 */
    fun dispose() {
        shapeBoBeAdd = null
        board?.cancelBatchSelect()
        board?.dispose()
        board = null
        handlerThread.quitSafely()
    }

    /** 保存 */
    fun save(path: String): Boolean {
        return board?.save(path) == true
    }

    /** 加载 */
    fun load(path: String): Boolean {
        return board?.load(path) == true
    }

    /** 更改画布比例 */
    fun setScale(scale: Float) {
        board?.setScale(scale)
    }

    /** 添加文本widget */
    fun addText(text: String) {
        shapeBoBeAdd = null
        board?.cancelBatchSelect()
        board?.run {
            addWidget(TextWidget(boardWidth = width, boardHeight = height, text = text))
        }
    }

    /** 添加图片widget */
    fun addImg(path: String) {
        shapeBoBeAdd = null
        board?.cancelBatchSelect()
        val mr = suggestedMaxInitialRect(width.toFloat(), height.toFloat())
        val rect: RectF = try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, options)
            val size = fitImgSize(
                imgW = options.outWidth,
                imgH = options.outHeight,
                frameW = mr.width().toInt(),
                frameH = mr.height().toInt()
            )
            RectF(mr.left, mr.top, mr.left + size.width, mr.top + size.height)
        } catch (e: Exception) {
            mr
        }
        board?.run { addWidget(ImageWidget(width, height, path, rect)) }
    }

    /** 添加形状widget */
    fun addShape(shape: Shape) {
        board?.cancelBatchSelect()
        board?.changeHandwriteStatus(false)
        shapeBoBeAdd = shape
    }

    /** 更改当前选中的组件的层级 */
    fun changeLayer(step: Int) {
        board?.changeLayer(step)
    }

    /** 撤销 */
    fun undo() {
        board?.cancelBatchSelect()
        board?.undo()
    }

    /** 重做 */
    fun redo() {
        board?.cancelBatchSelect()
        board?.redo()
    }

    /** 批量选择 */
    fun batchSelect() {
        board?.batchSelect()
    }

    /** 改变手写/编辑状态 */
    fun changeHandwriteStatus(handwriteStatus: Boolean) {
        board?.changeHandwriteStatus(handwriteStatus)
    }

    /** 手写/编辑状态改变的监听 */
    fun setOnHandwriteStatusChangeListener(listener: (Boolean) -> Unit) {
        onBoardHandwriteStatusChangeListener = listener
    }

    /** 生成缩略图 */
    fun generateThumb(width: Int = 0, height: Int = 0): Bitmap? {
        val bd = board ?: return null
        val bm = Bitmap.createBitmap(bd.width.toInt(), bd.height.toInt(), Bitmap.Config.RGB_565)
        bm.eraseColor(Color.WHITE)
        val canvas = Canvas(bm)
        bd.drawLowerWidgets(canvas)
        bd.drawActiveWidgets(canvas)
        bd.drawUpperWidgets(canvas)
        val bmSize = fitImgSize(bd.width.toInt(), bd.height.toInt(), width, height)
        return bm.scale(bmSize.width, bmSize.height)
    }

    private fun onTouch(event: Board.TouchEvent) {
        if (shapeBoBeAdd != null && event.action == MotionEvent.ACTION_DOWN) {
            board?.run { addWidget(ShapeWidget(width, height, shapeBoBeAdd!!)) }
            shapeBoBeAdd = null
        }
        board?.touch(event)
    }
}

val BoardView.handwriteStatus: Boolean get() = board?.handwriteWidget?.handwriteStatus == true
var BoardView.penType: HandwriteWidget.HwPen
    set(value) {
        board?.handwriteWidget?.penType = value
    }
    get() = board?.handwriteWidget?.penType ?: HandwriteWidget.HwPen.PENCIL
var BoardView.penWidth: Float
    set(value) {
        board?.handwriteWidget?.penWidth = value
    }
    get() = board?.handwriteWidget?.penWidth ?: HandwriteWidget.DEFAULT_PEN_WIDTH

/** 当前选中的widget不会放到最上层，所以绘制的时候有上下两层静态画布和中间的动态widget画布，共三层，每层单独绘制 */
private class LayerView(context: Context) : View(context) {
    var onDrawCall: ((Canvas) -> Unit)? = null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        onDrawCall?.invoke(canvas)
    }
}