package com.boe.board.widget

import android.graphics.*
import android.view.MotionEvent
import androidx.core.graphics.withSave
import com.boe.board.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.lang.Float.max
import java.lang.Float.min

/** 手写组件。笔画显示的就是实际的点，画板缩放时实际上是改变了点的位置和画笔粗细 */
class HandwriteWidget(
    boardWidth: Float,
    boardHeight: Float,
    private var boardScale: Float,
    private val onGetDeadArea: () -> List<RectF>
) : BaseWidget(boardWidth, boardHeight) {

    // 笔画列表
    private val strokes = mutableListOf<HwStroke>()

    private val boardCx = boardWidth / 2
    private val boardCy = boardHeight / 2

    /** 是否是手写状态 */
    var handwriteStatus = false
        private set
    var penWidth: Float = DEFAULT_PEN_WIDTH
    var penType: HwPen = HwPen.PENCIL

    // 画圈擦除用到的path
    private var enclosedEraserPath: DashedPath? = null

    // 当前笔画，按下的时候赋值新列表，移动的时候添加到strokes里边
    private var points = mutableListOf<HwPoint>()

    // 笔画绘制的图片
    private var bitmap: Bitmap? = null
    private val canvas: Canvas by lazy {
        if (bitmap == null) {
            bitmap = Bitmap.createBitmap(
                boardWidth.toInt(),
                boardHeight.toInt(),
                Bitmap.Config.ARGB_8888
            )
        }
        Canvas(bitmap!!)
    }

    private val paint: Paint by lazy {
        Paint().apply {
            style = Paint.Style.STROKE
            color = Color.BLACK
            isDither = true
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    }

    private var lastX: Float? = null
    private var lastY: Float? = null

    // 拖动缩放时临时记录的变量。处于选中状态时的变换都是图片，取消选中时才应用到笔画上
    private var oldRect: Rect? = null

    constructor(
        boardWidth: Float,
        boardHeight: Float,
        boardScale: Float,
        onGetDeadArea: () -> List<RectF>,
        config: JSONObject
    ) : this(boardWidth, boardHeight, boardScale, onGetDeadArea) {
        fromConfig(config)
    }

    override fun fromConfig(config: JSONObject) {
        super.fromConfig(config)
        oldRect = null
        rotation = 0F
        // 读取的是按照画板比例1的数据
        val strokes = mutableListOf<HwStroke>()
        val array = config.optJSONArray("strokes")
        if (array != null) for (i in 0 until array.length()) {
            val sObj = array.optJSONObject(i) ?: continue
            val pointArray = sObj.optJSONArray("points") ?: continue
            val points = mutableListOf<HwPoint>()
            for (j in 0 until pointArray.length()) {
                val pObj = pointArray.optJSONObject(j) ?: continue
                val x = pObj.optDouble("x").toFloat()
                val y = pObj.optDouble("y").toFloat()
                val pressure = pObj.optDouble("pressure").toFloat()
                val p = if (boardScale == 1F) {
                    HwPoint(x, y, pressure)
                } else {
                    HwPoint(
                        x = (x - boardCx) * boardScale + boardCx,
                        y = (y - boardCy) * boardScale + boardCy,
                        pressure = pressure
                    )
                }
                points.add(p)
            }
            strokes.add(
                HwStroke(
                    points = points,
                    penType = sObj.optString("penType")
                        .let { if (it.isBlank()) HwPen.PENCIL else HwPen.valueOf(it) },
                    penWidth = sObj.optDouble("penWidth").toFloat()
                        .let { if (boardScale == 1F) it else (it * boardScale) }
                )
            )
        }
        this.strokes.clear()
        this.strokes.addAll(strokes)
        drawStrokes()
    }

    override fun toConfig(): JSONObject {
        // 保存的是按照画板比例1的数据
        recalculatePoints(directly = true)
        return super.toConfig().apply {
            val array = JSONArray()
            for (s in strokes) {
                val sOjb = JSONObject()
                sOjb.put(
                    "penWidth",
                    if (boardScale == 1F) s.penWidth else s.penWidth / boardScale
                )
                sOjb.put("penType", s.penType.name)
                val pointArray = JSONArray()
                for (p in s.points) {
                    val pObj = JSONObject()
                    if (boardScale == 1F) {
                        pObj.put("x", p.x)
                        pObj.put("y", p.y)
                    } else {
                        pObj.put("x", (p.x - boardCx) / boardScale + boardCx)
                        pObj.put("y", (p.y - boardCy) / boardScale + boardCy)
                    }
                    pObj.put("pressure", p.pressure)
                    pointArray.put(pObj)
                }
                sOjb.put("points", pointArray)
                array.put(sOjb)
            }
            put("strokes", array)
        }
    }

    override fun copy(): BaseWidget {
        val w = HandwriteWidget(boardWidth, boardHeight, boardScale, onGetDeadArea, toConfig())
        w.rect = checkedCopyWidgetRect(rect)
        return w
    }

    override fun checkIfSelectedByPath(path: Path): BaseWidget? {
        if (strokes.isEmpty()) return null
        val pathBounds = RectF()
        path.computeBounds(pathBounds, true)
        val region = Region().apply {
            setPath(
                path,
                Region(
                    pathBounds.left.toInt(),
                    pathBounds.top.toInt(),
                    pathBounds.right.toInt(),
                    pathBounds.bottom.toInt()
                )
            )
        }
        return if (strokes.any { region.op(Rect(it.rectI), Region.Op.INTERSECT) }) this else null
    }

    override fun onSelectStatusChanged() {
        super.onSelectStatusChanged()
        recalculatePoints(directly = false)
    }

    override fun undo(operation: Board.Step.Operation) {
        if (operation is ChangeContent) {
            strokes.removeLastOrNull()
            drawStrokes()
        } else {
            super.undo(operation)
        }
    }

    override fun redo(operation: Board.Step.Operation) {
        if (operation is ChangeContent) {
            strokes.add(operation.stroke)
            drawStroke(canvas, operation.stroke)
            callback?.onUpdate()
        } else {
            super.redo(operation)
        }
    }

    override fun checkIfInSelectArea(event: Board.TouchEvent): Boolean {
        return strokes.any { it.rect.contains(event.x, event.y) }
    }

    override fun onDraw(canvas: Canvas) {
        if (rect.width() == 0F || rect.height() == 0F) return
        canvas.withSave {
            // 手写图片不跟随画板比例缩放，缩放时是重新计算点的坐标。所以这里把画板缩放效果撤回
            if (boardScale != 1F) scale(1 / boardScale, 1 / boardScale, boardCx, boardCy)
            // 直接绘制笔画内容
            if (oldRect == null) {
                bitmap?.let { canvas.drawBitmap(it, 0F, 0F, null) }
                enclosedEraserPath?.let { canvas.drawPath(it, it.paint) }
            }
            // 笔画转换中，绘制临时转换图片
            else {
                if (rotation != 0F) rotate(rotation, rect.centerX(), rect.centerY())
                bitmap?.let { canvas.drawBitmap(it, oldRect, rect, null) }
            }
            frameAndController?.onDraw(canvas)
        }
    }

    override fun drawContent(canvas: Canvas) {}

    override fun onTouch(event: Board.TouchEvent): Boolean {
        // 手写内容要跟屏幕显示对上，所以要用画板缩放前的点
        if (boardScale != 1F) {
            event.x = (event.x - boardCx) * boardScale + boardCx
            event.y = (event.y - boardCy) * boardScale + boardCy
        }
        // 旋转后的点
        val point = restoreFromRotatedPoint(
            angle = rotation,
            x = event.x,
            y = event.y,
            px = rect.centerX(),
            py = rect.centerY()
        )
        // 触摸事件被控制器的按钮消耗
        if (frameAndController?.onTouchUpper(event, point) == true) return true
        // 触摸事件被内容消耗
        if (touchContent(event, point)) return true
        // 触摸事件被控制器的移动操作消耗
        if (frameAndController?.onTouchLower(event, point) == true) return true
        // 返回是否选中
        return if (event.action == MotionEvent.ACTION_DOWN) checkIfInSelectArea(event) else selected
    }

    override fun touchContent(event: Board.TouchEvent, restoredPoint: PointF): Boolean {
        if (!handwriteStatus) return false
        if (event.toolType != MotionEvent.TOOL_TYPE_STYLUS && event.toolType != MotionEvent.TOOL_TYPE_ERASER) return false
        if (enclosedErase(event.action, event.x, event.y, event.pressure)) return true

        if (onGetDeadArea.invoke().any { it.contains(event.x, event.y) }) {
            event.action = MotionEvent.ACTION_CANCEL
        }
        handwrite(event.action, event.x, event.y, event.pressure, event.toolType)
        return true
    }

    override fun dispose() {
        super.dispose()
        bitmap?.recycle()
        bitmap = null
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
        if (oldRect == null) oldRect = Rect(
            rect.left.toInt(),
            rect.top.toInt(),
            rect.right.toInt(),
            rect.bottom.toInt()
        )
    }

    /** 设置手写编辑状态 */
    fun setHandwriteStatus(handwriteStatus: Boolean) {
        this.handwriteStatus = handwriteStatus
        lastX = null
        lastY = null
        enclosedEraserPath = null
    }

    /** 设置缩放比例。画板缩放比例改变时调用 */
    fun setBoardScale(scale: Float) {
        if (this.boardScale == scale) return
        val pointScale = scale / this.boardScale
        this.boardScale = scale
        // 处于选中状态时的变换都是图片，取消选中时才应用到笔画上
        if (oldRect != null) {
            rect.left = (rect.left - boardCx) * pointScale + boardCx
            rect.top = (rect.top - boardCy) * pointScale + boardCy
            rect.right = (rect.right - boardCx) * pointScale + boardCx
            rect.bottom = (rect.bottom - boardCy) * pointScale + boardCy
            frameAndController?.updateWidgetRect(rect)
            return
        }
        scope.launch {
            var tmp: RectF? = null
            for (s in strokes) {
                s.penWidth *= pointScale
                for (p in s.points) {
                    p.x = (p.x - boardCx) * pointScale + boardCx
                    p.y = (p.y - boardCy) * pointScale + boardCy
                    // 重新计算区域
                    if (s.penType != HwPen.ERASER && s.penType != HwPen.ENCLOSED_ERASER) {
                        val penW = s.penWidth * p.pressure
                        if (tmp == null) {
                            tmp = RectF(p.x - penW, p.y - penW, p.x + penW, p.y + penW)
                        } else {
                            tmp.union(p.x - penW, p.y - penW, p.x + penW, p.y + penW)
                        }
                    }
                }
            }
            this@HandwriteWidget.rect = tmp ?: RectF()
            frameAndController?.updateWidgetRect(this@HandwriteWidget.rect)
            val path = Path()
            bitmap?.eraseColor(Color.TRANSPARENT)
            for (s in strokes) drawStroke(canvas, s, path)
            callback?.onUpdate()
        }
    }

    // 画圈擦除处理
    private fun enclosedErase(action: Int, x: Float, y: Float, pressure: Float): Boolean {
        if (penType != HwPen.ENCLOSED_ERASER) return false
        val path = if (enclosedEraserPath == null) {
            val tmp = DashedPath()
            enclosedEraserPath = tmp
            tmp
        } else {
            enclosedEraserPath!!
        }
        when (action) {
            // 按下
            MotionEvent.ACTION_DOWN -> {
                path.reset()
                path.moveTo(x, y)
                points = mutableListOf()
                points.add(HwPoint(x, y, pressure))
            }
            // 移动
            MotionEvent.ACTION_MOVE -> {
                path.lineTo(x, y)
                points.add(HwPoint(x, y, pressure))
            }
            // 抬起，执行擦除
            MotionEvent.ACTION_UP -> {
                path.close()
                paint.style = Paint.Style.FILL
                paint.color = Color.TRANSPARENT
                paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                canvas.drawPath(path, paint)
                if (enclosedEraserPath != null) {
                    enclosedEraserPath = null
                    strokes.add(HwStroke(points, HwPen.ENCLOSED_ERASER, penWidth))
                    strokes.lastOrNull()?.let {
                        callback?.onStep(Board.Step(this, ChangeContent(it)))
                    }
                }
            }
            // 其他
            else -> {
                if (enclosedEraserPath != null) {
                    enclosedEraserPath = null
                    strokes.add(HwStroke(points, HwPen.ENCLOSED_ERASER, penWidth))
                }
            }
        }
        callback?.onUpdate()
        return true
    }

    // 手写处理
    private fun handwrite(action: Int, x: Float, y: Float, pressure: Float, toolType: Int) {
        val pen = if (toolType == MotionEvent.TOOL_TYPE_ERASER) HwPen.ERASER else penType
        when (action) {
            // 按下
            MotionEvent.ACTION_DOWN -> {
                lastX = x
                lastY = y
                points = mutableListOf()
                points.add(HwPoint(x, y, pressure))
            }
            // 移动
            MotionEvent.ACTION_MOVE -> {
                val lx = lastX
                val ly = lastY
                if (lx == null || ly == null) {
                    lastX = x
                    lastY = y
                    points = mutableListOf()
                    points.add(HwPoint(x, y, pressure))
                    return
                }
                lastX = x
                lastY = y
                points.add(HwPoint(x, y, pressure))
                paint.style = Paint.Style.STROKE
                if (pen == HwPen.ERASER) {
                    paint.strokeWidth = penWidth
                    paint.color = Color.TRANSPARENT
                    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                    canvas.drawLine(lx, ly, x, y, paint)
                } else {
                    val penW = penWidth * pressure
                    paint.strokeWidth = penW
                    paint.color = Color.BLACK
                    paint.xfermode = null
                    canvas.drawLine(lx, ly, x, y, paint)
                    val updateRect = Rect(
                        max(0F, min(lx, x) - penW).toInt(),
                        max(0F, min(ly, y) - penW).toInt(),
                        min(boardWidth, max(lx, x) + penW).toInt(),
                        min(boardHeight, max(ly, y) + penW).toInt()
                    )
                    this.rect.union(
                        updateRect.left.toFloat(),
                        updateRect.top.toFloat(),
                        updateRect.right.toFloat(),
                        updateRect.bottom.toFloat()
                    )
                }
                callback?.onUpdate()
            }
            // 其他，一般是抬起
            else -> {
                if (lastX != null || lastY != null) {
                    lastX = null
                    lastY = null
                    strokes.add(HwStroke(points, pen, penWidth))
                    strokes.lastOrNull()?.let {
                        callback?.onStep(Board.Step(this, ChangeContent(it)))
                    }
                }
            }
        }
    }

    // 绘制笔画到图片上
    private fun drawStrokes() {
        bitmap?.eraseColor(Color.TRANSPARENT)
        if (strokes.isEmpty()) return
        scope.launch {
            val path = Path()
            for (s in strokes) drawStroke(canvas, s, path)
            callback?.onUpdate()
        }
    }

    // 画一个笔画
    private fun drawStroke(canvas: Canvas, stroke: HwStroke, path: Path = Path()) {
        // 画圈擦除
        if (stroke.penType == HwPen.ENCLOSED_ERASER) {
            if (stroke.points.size < 2) return
            path.reset()
            for ((idx, p) in stroke.points.withIndex()) {
                if (idx == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            path.close()
            paint.style = Paint.Style.FILL
            paint.color = Color.TRANSPARENT
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            canvas.drawPath(path, paint)
            return
        }
        // 擦除
        if (stroke.penType == HwPen.ERASER) {
            if (stroke.points.size < 2) return
            path.reset()
            for ((idx, p) in stroke.points.withIndex()) {
                if (idx == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = stroke.penWidth
            paint.color = Color.TRANSPARENT
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            return
        }
        // 笔画
        paint.style = Paint.Style.STROKE
        paint.color = Color.BLACK
        paint.xfermode = null
        for (i in 1 until stroke.points.size) {
            val lastP = stroke.points[i - 1]
            val p = stroke.points[i]
            paint.strokeWidth = stroke.penWidth * p.pressure
            canvas.drawLine(lastP.x, lastP.y, p.x, p.y, paint)
        }
    }

    // 重新计算旋转拉伸后各点的位置
    private fun recalculatePoints(directly: Boolean) {
        val oldRect = oldRect ?: return
        val diffRotation = rotation
        val offsetX: Float = rect.left.toInt() - oldRect.left.toFloat()
        val offsetY: Float = rect.top.toInt() - oldRect.top.toFloat()
        val scaleX: Float = rect.width().toInt() / oldRect.width().toFloat()
        val scaleY: Float = rect.height().toInt() / oldRect.height().toFloat()
        if (diffRotation == 0F && offsetX == 0F && offsetY == 0F && scaleX == 1F && scaleY == 1F) return
        if (strokes.isEmpty()) return
        val strokes = this.strokes.map { it.copy(points = it.points.toList()) }
        this.strokes.clear()
        fun doCalculate() {
            var tmp: RectF? = null
            for (s in strokes) for (p in s.points) {
                // 位移
                var x = if (offsetX == 0F) p.x else (p.x + offsetX)
                var y = if (offsetY == 0F) p.y else (p.y + offsetY)
                // 缩放
                if (scaleX != 1F) x = ((x - rect.left) * scaleX + rect.left)
                if (scaleY != 1F) y = ((y - rect.top) * scaleY + rect.top)
                // 旋转
                if (diffRotation != 0F) {
                    val np = rotatePoint(diffRotation, x, y, rect.centerX(), rect.centerY())
                    x = np.x
                    y = np.y
                }
                // 结果
                p.x = x
                p.y = y
                // 重新计算区域
                if (s.penType != HwPen.ERASER && s.penType != HwPen.ENCLOSED_ERASER) {
                    val penW = s.penWidth * p.pressure
                    if (tmp == null) tmp = RectF(x - penW, y - penW, x + penW, y + penW)
                    else tmp.union(x - penW, y - penW, x + penW, y + penW)
                }
            }
            this@HandwriteWidget.strokes.addAll(strokes)
            this@HandwriteWidget.rect = tmp ?: RectF()
            frameAndController?.updateWidgetRect(this@HandwriteWidget.rect)
            val path = Path()
            bitmap?.eraseColor(Color.TRANSPARENT)
            for (s in strokes) drawStroke(canvas, s, path)
            this@HandwriteWidget.oldRect = null
            this@HandwriteWidget.rotation = 0F
            callback?.onUpdate()
        }
        if (directly) doCalculate() else scope.launch { doCalculate() }
    }

    companion object {
        const val DEFAULT_PEN_WIDTH = 10F
    }

    // 操作步骤记录里的数据
    private data class ChangeContent(val stroke: HwStroke) : Board.Step.Operation()

    // 画圈擦除的path
    private class DashedPath : Path() {
        val paint = Paint().apply {
            pathEffect = DashPathEffect(floatArrayOf(5F, 10F), 0F)
            style = Paint.Style.STROKE
            color = Color.BLACK
            strokeWidth = 2F
        }
    }

    /** 手写的笔画 */
    data class HwStroke(
        val points: List<HwPoint>,
        val penType: HwPen,
        var penWidth: Float
    ) {
        /** 笔画所在的区域 */
        val rect: RectF
            get() = points.let { list ->
                RectF(
                    list.minOf { it.x },
                    list.minOf { it.y },
                    list.maxOf { it.x },
                    list.maxOf { it.y }
                )
            }
        val rectI: Rect
            get() = points.let { list ->
                Rect(
                    list.minOf { it.x }.toInt(),
                    list.minOf { it.y }.toInt(),
                    list.maxOf { it.x }.toInt(),
                    list.maxOf { it.y }.toInt()
                )
            }
    }

    /** 手写的点 */
    data class HwPoint(var x: Float, var y: Float, val pressure: Float)

    /** 手写画笔类型 */
    enum class HwPen { PENCIL, ERASER, ENCLOSED_ERASER }
}