package com.boe.board.widget

import android.graphics.*
import android.view.MotionEvent
import com.boe.board.*
import org.json.JSONObject
import kotlin.math.*

/** 形状组件的基类 */
@Suppress("unused")
class ShapeWidget private constructor(boardWidth: Float, boardHeight: Float) :
    BaseWidget(boardWidth, boardHeight) {

    private var shape: Shape = RectangleShape
    private var shaping = true // 是否正在拖动绘制形状
    private var touchDownX = 0F
    private var touchDownY = 0F

    constructor(boardWidth: Float, boardHeight: Float, shape: Shape) : this(
        boardWidth,
        boardHeight
    ) {
        this.shape = shape
    }

    constructor(boardWidth: Float, boardHeight: Float, config: JSONObject) : this(
        boardWidth,
        boardHeight
    ) {
        fromConfig(config)
    }

    override fun fromConfig(config: JSONObject) {
        shaping = false
        shape = Shape.fromConfig(config.getJSONObject("shape"))
        super.fromConfig(config)
    }

    override fun toConfig(): JSONObject {
        return super.toConfig().apply { put("shape", shape.toConfig()) }
    }

    override fun copy(): ShapeWidget {
        val wd = ShapeWidget(boardWidth, boardHeight, toConfig())
        wd.rect = checkedCopyWidgetRect(rect)
        return wd
    }

    override fun drawContent(canvas: Canvas) {
        if (rect.width() == 0F || rect.height() == 0F) return
        shape.draw(canvas, rect, shaping)
    }

    override fun touchContent(event: Board.TouchEvent, restoredPoint: PointF): Boolean {
        if (!shaping) return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
                size(
                    left = touchDownX,
                    top = touchDownY,
                    right = touchDownX,
                    bottom = touchDownY,
                    OperateStatus.START,
                    addStep = false
                )
            }
            MotionEvent.ACTION_MOVE -> {
                size(
                    left = touchDownX,
                    top = touchDownY,
                    right = event.x,
                    bottom = event.y,
                    operateStatus = OperateStatus.OPERATING,
                    addStep = false
                )
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                shaping = false
                size(operateStatus = OperateStatus.END, addStep = false)
                callback?.onStep(Board.Step(this, Board.Step.Add))
            }
        }
        return true
    }
}

sealed class Shape {

    protected val paint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 5F
        isAntiAlias = true
    }

    abstract fun draw(canvas: Canvas, rect: RectF, shaping: Boolean)

    open fun toConfig(): JSONObject {
        return JSONObject().apply { put("name", this@Shape::class.qualifiedName) }
    }

    companion object {
        fun fromConfig(config: JSONObject): Shape {
            return when (config.optString("name")) {
                CircleShape::class.qualifiedName -> CircleShape
                ArrowShape::class.qualifiedName -> ArrowShape.fromConfig(config)
                else -> RectangleShape
            }
        }
    }
}

object RectangleShape : Shape() {
    override fun draw(canvas: Canvas, rect: RectF, shaping: Boolean) {
        val pd = paint.strokeWidth / 2
        if (shaping) {
            canvas.drawRect(
                min(rect.left, rect.right) + pd,
                min(rect.top, rect.bottom) + pd,
                max(rect.left, rect.right) - pd,
                max(rect.top, rect.bottom) - pd,
                paint
            )
        } else {
            canvas.drawRect(rect.left + pd, rect.top + pd, rect.right - pd, rect.bottom - pd, paint)
        }
    }
}

object CircleShape : Shape() {
    override fun draw(canvas: Canvas, rect: RectF, shaping: Boolean) {
        val radius = if (shaping) {
            (min(abs(rect.width()), abs(rect.height())) - paint.strokeWidth) / 2
        } else {
            (min(rect.width(), rect.height()) - paint.strokeWidth) / 2
        }
        canvas.drawCircle(rect.centerX(), rect.centerY(), radius, paint)
    }
}

class ArrowShape : Shape() {

    private val arrowSize = 15F
    private val arrowRadian = 0.75F
    private val path = Path()
    private val oldRect = RectF()
    internal var from: Anchor? = null

    init {
        paint.strokeWidth = 5F
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
    }

    override fun draw(canvas: Canvas, rect: RectF, shaping: Boolean) {
        if (shaping) {
            val from = when {
                rect.right > rect.left && rect.bottom < rect.top -> Anchor.LEFT_BOTTOM
                rect.right < rect.left && rect.bottom > rect.top -> Anchor.RIGHT_TOP
                rect.right < rect.left && rect.bottom < rect.top -> Anchor.RIGHT_BOTTOM
                else -> Anchor.LEFT_TOP
            }
            if (this.from != from || oldRect != rect) {
                this.from = from
                oldRect.left = rect.left
                oldRect.top = rect.top
                oldRect.right = rect.right
                oldRect.bottom = rect.bottom
                setPath(
                    left = min(rect.left, rect.right),
                    top = min(rect.top, rect.bottom),
                    right = max(rect.left, rect.right),
                    bottom = max(rect.top, rect.bottom)
                )
            }
        } else {
            if (rect.width() < 0 || rect.height() < 0) return
            if (path.isEmpty || oldRect != rect) {
                oldRect.left = rect.left
                oldRect.top = rect.top
                oldRect.right = rect.right
                oldRect.bottom = rect.bottom
                setPath(rect.left, rect.top, rect.right, rect.bottom)
            }
        }
        canvas.drawPath(path, paint)
    }

    override fun toConfig(): JSONObject {
        return super.toConfig().apply { put("from", (from ?: Anchor.LEFT_TOP).name) }
    }

    private fun setPath(left: Float, top: Float, right: Float, bottom: Float) {
        val l = left + paint.strokeWidth
        val t = top + paint.strokeWidth
        val r = right - paint.strokeWidth
        val b = bottom - paint.strokeWidth
        path.reset()
        when (from) {
            Anchor.LEFT_BOTTOM -> {
                path.moveTo(l, b)
                path.lineTo(r, t)
                val radian = calculateRadian(r, t, l, b)
                val x = r - arrowSize * cos(radian)
                val y = t - arrowSize * sin(radian)
                val p0 = rotatePointByRadian(-arrowRadian, x, y, r, t)
                val p1 = rotatePointByRadian(arrowRadian, x, y, r, t)
                path.lineTo(p0.x, p0.y)
                path.moveTo(r, t)
                path.lineTo(p1.x, p1.y)
            }
            Anchor.RIGHT_TOP -> {
                path.moveTo(r, t)
                path.lineTo(l, b)
                val radian = calculateRadian(l, b, r, t)
                val x = l - arrowSize * cos(radian)
                val y = b - arrowSize * sin(radian)
                val p0 = rotatePointByRadian(-arrowRadian, x, y, l, b)
                val p1 = rotatePointByRadian(arrowRadian, x, y, l, b)
                path.lineTo(p0.x, p0.y)
                path.moveTo(l, b)
                path.lineTo(p1.x, p1.y)
            }
            Anchor.RIGHT_BOTTOM -> {
                path.moveTo(r, b)
                path.lineTo(l, t)
                val radian = calculateRadian(l, t, r, b)
                val x = l - arrowSize * cos(radian)
                val y = t - arrowSize * sin(radian)
                val p0 = rotatePointByRadian(-arrowRadian, x, y, l, t)
                val p1 = rotatePointByRadian(arrowRadian, x, y, l, t)
                path.lineTo(p0.x, p0.y)
                path.moveTo(l, t)
                path.lineTo(p1.x, p1.y)
            }
            else -> {
                path.moveTo(l, t)
                path.lineTo(r, b)
                val radian = calculateRadian(r, b, l, t)
                val x = r - arrowSize * cos(radian)
                val y = b - arrowSize * sin(radian)
                val p0 = rotatePointByRadian(-arrowRadian, x, y, r, b)
                val p1 = rotatePointByRadian(arrowRadian, x, y, r, b)
                path.lineTo(p0.x, p0.y)
                path.moveTo(r, b)
                path.lineTo(p1.x, p1.y)
            }
        }
    }

    companion object {
        fun fromConfig(config: JSONObject): ArrowShape {
            return ArrowShape().apply {
                from = config.optString("from")
                    .let { if (it.isBlank()) Anchor.LEFT_TOP else Anchor.valueOf(it) }
            }
        }
    }
}