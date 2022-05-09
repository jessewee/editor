package com.boe.board

import android.graphics.*
import android.view.MotionEvent
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import kotlin.math.*

/**
 * 边框和缩放删除等控制按钮
 * */
@Suppress("SpellCheckingInspection", "unused")
class FrameAndController(
    boardWidth: Float,
    widgetRect: RectF,
    buttons: List<CtlBtn>,
    private val onGetWidgetRotation: () -> Float,
    private val onChangeSize: (Float, Float, Float, Float, OperateStatus) -> Unit,
    private val onRotate: (Float, OperateStatus) -> Unit,
    private val onOffsetBy: (Float, Float, OperateStatus) -> Unit
) {
    private var widgetRect: RectF = RectF(widgetRect)

    // 按钮引申的竖线高度
    private val rootLineHeight = 25F

    // 按钮垂直间隔
    private val btnVerticalSpace = 15F

    // 按钮水平间隔
    private val btnHorizontalSpace = 15F

    // 按钮大小
    private val btnSize = 40F

    // 按钮图片内边距
    private val btnPadding = btnSize / 10

    // 每行最多几个按钮
    private val maxBtnPerLine: Int by lazy {
        ceil((boardWidth + btnHorizontalSpace) / (btnSize + btnHorizontalSpace)).toInt()
    }

    // 按钮行数
    private val lineCnt: Int by lazy { ceil(buttons.size.toFloat() / maxBtnPerLine).toInt() }

    // 按钮区域宽度
    private val buttonsAreaWidth: Float by lazy {
        if (lineCnt == 1) buttons.size * btnSize + (buttons.size - 1) * btnHorizontalSpace else boardWidth
    }

    // 按钮列表，这里存的Rect是初始计算的，widgetRect移动后在绘制和触屏事件的地方添加偏移量，widgetRect大小改变时才重新计算
    private var buttons: List<Btn> = emptyList()

    // 当前按下的按钮
    private var pressedBtn: Btn? = null

    // 触屏事件位置
    private var lastBtnTouchX: Float? = null
    private var lastBtnTouchY: Float? = null
    private var lastMoveTouchX: Float? = null
    private var lastMoveTouchY: Float? = null

    // onTouch里是否执行过改变大小的操作，用来通知外层记录操作步骤
    private var sizeChanged = false

    // 8个拉伸手柄的位置
    private val sizeHandlers = listOf(
        SizeHandler(RectF(), RectF(), Anchor.TOP),
        SizeHandler(RectF(), RectF(), Anchor.BOTTOM),
        SizeHandler(RectF(), RectF(), Anchor.LEFT),
        SizeHandler(RectF(), RectF(), Anchor.RIGHT),
        SizeHandler(RectF(), RectF(), Anchor.LEFT_TOP),
        SizeHandler(RectF(), RectF(), Anchor.RIGHT_TOP),
        SizeHandler(RectF(), RectF(), Anchor.LEFT_BOTTOM),
        SizeHandler(RectF(), RectF(), Anchor.RIGHT_BOTTOM)
    )

    // 拉伸手柄的大小的一半
    private val sizeHandlerHalfSize = 12F

    // 当前生效的拉伸手柄
    private var pressedSizeHandler: SizeHandler? = null

    // 旋转按钮
    private var rotateBtn: Btn? = null

    // 把框和线放到path里绘制，减少canvas方法的调用
    private var strokePath = PathAndPaint(Paint().apply {
        strokeWidth = 2F
        style = Paint.Style.STROKE
        color = Color.BLACK
        isAntiAlias = true
    })

    // 边框和按钮的所在范围
    val rect: RectF get() = strokePath.rect

    init {
        // 加载按钮图片
        this.buttons = buttons.map { btn ->
            val bm: Bitmap? = Board.appContext?.let { ctx ->
                try {
                    ContextCompat.getDrawable(ctx, btn.resId)?.toBitmap()
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
            Btn(RectF(), RectF(), RectF(), btn, bm)
        }
        // 生成旋转按钮
        createRotateBtn()
        // 计算位置
        calculateSize()
    }

    fun updateWidgetRect(rect: RectF) {
        val oldRect = widgetRect
        widgetRect = RectF(rect)
        // 大小不变时偏移线框
        if (oldRect.sameSize(widgetRect, SIZE_MIN_DIFF)) {
            val dx = rect.left - oldRect.left
            val dy = rect.top - oldRect.top
            strokePath.offset(dx, dy)
            calculateOffset()
        }
        // 大小改变时重新计算
        else {
            calculateSize()
        }
    }

    fun onDraw(canvas: Canvas) {
        // 绘制边框和线
        canvas.drawPath(strokePath, strokePath.paint)
        // 绘制按钮图片
        for (btn in buttons) btn.bitmap?.let {
            if (!btn.bmRect.isEmpty) canvas.drawBitmap(it, null, btn.bmRect, null)
        }
        rotateBtn?.let { btn ->
            btn.bitmap?.let {
                if (!btn.bmRect.isEmpty) canvas.drawBitmap(it, null, btn.bmRect, null)
            }
        }
    }

    /** 按钮和拉伸控制手柄的点击判断 */
    fun onTouchUpper(event: Board.TouchEvent, restoredPoint: PointF): Boolean {
        if (checkTouchDownBtn(event, restoredPoint)) return true
        if (touchSizeHandler(event, restoredPoint)) return true
        return touchButtons(event, restoredPoint)
    }

    /** 移动的事件判断，组件子内容不消耗事件时才走到这 */
    fun onTouchLower(event: Board.TouchEvent, point: PointF): Boolean {
        // 判断按下事件
        if (event.action == MotionEvent.ACTION_DOWN) {
            // 判断点击范围用转换后的坐标，记录点还用原来的坐标，因为平移之类的不能考虑旋转
            return if (widgetRect.contains(point.x, point.y)) {
                lastMoveTouchX = event.x
                lastMoveTouchY = event.y
                onOffsetBy.invoke(0F, 0F, OperateStatus.START)
                true
            } else {
                lastMoveTouchX = null
                lastMoveTouchY = null
                false
            }
        }
        // 移动
        if (event.action == MotionEvent.ACTION_MOVE) {
            if (lastMoveTouchX == null || lastMoveTouchY == null) {
                lastMoveTouchX = event.x
                lastMoveTouchY = event.y
                onOffsetBy.invoke(0F, 0F, OperateStatus.START)
                return true
            }
            val dx = event.x - lastMoveTouchX!!
            val dy = event.y - lastMoveTouchY!!
            if (abs(dx) < MOVE_MIN_SPACE && abs(dy) < MOVE_MIN_SPACE) return true
            lastMoveTouchX = event.x
            lastMoveTouchY = event.y
            onOffsetBy.invoke(dx, dy, OperateStatus.OPERATING)
        }
        // 抬起
        else {
            lastMoveTouchX = null
            lastMoveTouchY = null
            onOffsetBy.invoke(0F, 0F, OperateStatus.END)
        }
        return true
    }

    fun dispose() {
        for (btn in buttons) {
            btn.bitmap?.recycle()
            btn.bitmap = null
        }
        rotateBtn?.bitmap?.recycle()
        rotateBtn?.bitmap = null
    }

    // 计算各按钮和线的位置。是相对于widgetRect.left和widgetRect.top的位置，所以组件位置改变但是大小不变的时候不用重新计算
    private fun calculateSize() {
        // 计算按钮位置
        val ww = widgetRect.width()
        val wh = widgetRect.height()
        val cx = ww / 2
        val cy = wh / 2
        // 更新按钮偏移量
        val startX = cx - buttonsAreaWidth / 2
        val startY = wh + rootLineHeight + btnVerticalSpace
        for ((idx, btn) in buttons.withIndex()) {
            val left = startX + (btnSize + btnHorizontalSpace) * idx
            val top = startY + (btnSize + btnVerticalSpace) * (idx / maxBtnPerLine)
            btn.rect = RectF(left, top, left + btnSize, top + btnSize)
        }
        // 计算旋转按钮位置
        rotateBtn?.run {
            val hbSize = btnSize / 2
            val rbBottom = -rootLineHeight
            rect = RectF(cx - hbSize, rbBottom - btnSize, cx + hbSize, rbBottom)
        }
        // 计算拉伸控制手柄的位置。列表是按顺序添加的，这里按顺序更改，就不根据direction去查找了
        sizeHandlers[0].rect.run {
            left = cx - sizeHandlerHalfSize
            top = -sizeHandlerHalfSize
            right = cx + sizeHandlerHalfSize
            bottom = +sizeHandlerHalfSize
        }
        sizeHandlers[1].rect.run {
            left = cx - sizeHandlerHalfSize
            top = wh - sizeHandlerHalfSize
            right = cx + sizeHandlerHalfSize
            bottom = wh + sizeHandlerHalfSize
        }
        sizeHandlers[2].rect.run {
            left = -sizeHandlerHalfSize
            top = cy - sizeHandlerHalfSize
            right = +sizeHandlerHalfSize
            bottom = cy + sizeHandlerHalfSize
        }
        sizeHandlers[3].rect.run {
            left = ww - sizeHandlerHalfSize
            top = cy - sizeHandlerHalfSize
            right = ww + sizeHandlerHalfSize
            bottom = cy + sizeHandlerHalfSize
        }
        sizeHandlers[4].rect.run {
            left = -sizeHandlerHalfSize
            top = -sizeHandlerHalfSize
            right = sizeHandlerHalfSize
            bottom = sizeHandlerHalfSize
        }
        sizeHandlers[5].rect.run {
            left = ww - sizeHandlerHalfSize
            top = -sizeHandlerHalfSize
            right = ww + sizeHandlerHalfSize
            bottom = sizeHandlerHalfSize
        }
        sizeHandlers[6].rect.run {
            left = -sizeHandlerHalfSize
            top = wh - sizeHandlerHalfSize
            right = sizeHandlerHalfSize
            bottom = wh + sizeHandlerHalfSize
        }
        sizeHandlers[7].rect.run {
            left = ww - sizeHandlerHalfSize
            top = wh - sizeHandlerHalfSize
            right = ww + sizeHandlerHalfSize
            bottom = wh + sizeHandlerHalfSize
        }
        // 添加线和框到path中
        val strokePath = PathAndPaint(strokePath.paint)
        // 组件边框
        strokePath.addRect(
            RectF(0F, 0F, widgetRect.width(), widgetRect.height()),
            Path.Direction.CW
        )
        // 按钮根部竖线和横线
        if (buttons.isNotEmpty()) {
            // 竖线
            val rlStopY = wh + rootLineHeight
            strokePath.moveTo(cx, wh)
            strokePath.lineTo(cx, rlStopY)
            // 横线
            strokePath.moveTo(buttons.first().rect.centerX(), rlStopY)
            strokePath.lineTo(buttons.last().rect.centerX(), rlStopY)
        }
        // 按钮边框和连接线
        for (btn in buttons) {
            // 边框
            strokePath.addRect(RectF(btn.rect), Path.Direction.CW)
            // 连接线
            val x = btn.rect.centerX()
            val top = btn.rect.top
            strokePath.moveTo(x, top)
            strokePath.lineTo(x, top - btnVerticalSpace)
        }
        // 旋转手柄的边框和连接线
        rotateBtn?.let { btn ->
            // 边框
            strokePath.addRect(RectF(btn.rect), Path.Direction.CW)
            // 连接线
            val x = btn.rect.centerX()
            val top = btn.rect.bottom
            strokePath.moveTo(x, top)
            strokePath.lineTo(x, top + rootLineHeight)
        }
        // 缩放手柄的边框
        for (sh in sizeHandlers) strokePath.addRect(RectF(sh.rect), Path.Direction.CW)
        // 位置偏移
        strokePath.offset(widgetRect.left, widgetRect.top)
        this.strokePath = strokePath
        calculateOffset()
    }

    // 计算偏移量
    private fun calculateOffset() {
        // 偏移按钮位置
        fun offsetRect(src: RectF): RectF = RectF(
            widgetRect.left + src.left,
            widgetRect.top + src.top,
            widgetRect.left + src.right,
            widgetRect.top + src.bottom
        )

        fun Btn.offset() {
            this.bmRect = RectF(
                widgetRect.left + this.rect.left + btnPadding,
                widgetRect.top + this.rect.top + btnPadding,
                widgetRect.left + this.rect.right - btnPadding,
                widgetRect.top + this.rect.bottom - btnPadding
            )
            this.touchRect = RectF(
                widgetRect.left + this.rect.left,
                widgetRect.top + this.rect.top,
                widgetRect.left + this.rect.right,
                widgetRect.top + this.rect.bottom
            )
        }
        for (btn in buttons) btn.offset()
        rotateBtn?.offset()
        for (sh in sizeHandlers) sh.touchRect = offsetRect(sh.rect)
    }

    // 判断拉伸控制手柄的事件
    private fun touchSizeHandler(event: Board.TouchEvent, restoredPoint: PointF): Boolean {
        val x = restoredPoint.x
        val y = restoredPoint.y
        // 按下
        if (pressedSizeHandler == null) {
            if (event.action == MotionEvent.ACTION_DOWN) {
                for (sh in sizeHandlers) {
                    if (sh.touchRect.contains(x, y)) {
                        pressedSizeHandler = sh
                        lastBtnTouchX = x
                        lastBtnTouchY = y
                        return true
                    }
                }
            }
            return false
        }
        val ltx = lastBtnTouchX ?: return false
        val lty = lastBtnTouchY ?: return false
        // 移动
        if (event.action == MotionEvent.ACTION_MOVE) {
            val psh = pressedSizeHandler ?: return false
            val dx = x - ltx
            val dy = y - lty
            if (abs(dx) < SIZE_MIN_DIFF && abs(dy) < SIZE_MIN_DIFF) return true
            val halfDx = dx / 2
            val halfDy = dy / 2
            val radian = angleToRadian(onGetWidgetRotation.invoke())
            var left = widgetRect.left
            var top = widgetRect.top
            var right = widgetRect.right
            var bottom = widgetRect.bottom
            var offsetX = 0F
            var offsetY = 0F
            when (psh.anchor) {
                Anchor.TOP -> {
                    top += halfDy
                    bottom -= halfDy
                    offsetX = -sin(radian) * halfDy
                    offsetY = cos(radian) * halfDy
                }
                Anchor.BOTTOM -> {
                    top -= halfDy
                    bottom += halfDy
                    offsetX = -sin(radian) * halfDy
                    offsetY = cos(radian) * halfDy
                }
                Anchor.LEFT -> {
                    left += halfDx
                    right -= halfDx
                    offsetX = cos(radian) * halfDx
                    offsetY = sin(radian) * halfDx
                }
                Anchor.RIGHT -> {
                    left -= halfDx
                    right += halfDx
                    offsetX = cos(radian) * halfDx
                    offsetY = sin(radian) * halfDx
                }
                Anchor.LEFT_TOP -> {
                    top += halfDy
                    bottom -= halfDy
                    left += halfDx
                    right -= halfDx
                    offsetX = cos(radian) * halfDx - sin(radian) * halfDy
                    offsetY = sin(radian) * halfDx + cos(radian) * halfDy
                }
                Anchor.RIGHT_TOP -> {
                    left -= halfDx
                    right += halfDx
                    top += halfDy
                    bottom -= halfDy
                    offsetX = cos(radian) * halfDx - sin(radian) * halfDy
                    offsetY = sin(radian) * halfDx + cos(radian) * halfDy
                }
                Anchor.LEFT_BOTTOM -> {
                    left += halfDx
                    right -= halfDx
                    top -= halfDy
                    bottom += halfDy
                    offsetX = cos(radian) * halfDx - sin(radian) * halfDy
                    offsetY = sin(radian) * halfDx + cos(radian) * halfDy
                }
                Anchor.RIGHT_BOTTOM -> {
                    left -= halfDx
                    right += halfDx
                    top -= halfDy
                    bottom += halfDy
                    offsetX = cos(radian) * halfDx - sin(radian) * halfDy
                    offsetY = sin(radian) * halfDx + cos(radian) * halfDy
                }
            }
            lastBtnTouchX = x
            lastBtnTouchY = y
            val op = if (sizeChanged) {
                OperateStatus.OPERATING
            } else {
                sizeChanged = true
                OperateStatus.START
            }
            onChangeSize.invoke(
                left + offsetX,
                top + offsetY,
                right + offsetX,
                bottom + offsetY,
                op
            )
            sizeChanged = true
            return true
        }
        // 其他
        if (sizeChanged) {
            sizeChanged = false
            onChangeSize.invoke(
                widgetRect.left,
                widgetRect.top,
                widgetRect.right,
                widgetRect.bottom,
                OperateStatus.END
            )
        }
        val b = pressedSizeHandler != null
        pressedSizeHandler = null
        return b
    }

    // 判断按下时是否点中按钮
    private fun checkTouchDownBtn(event: Board.TouchEvent, restoredPoint: PointF): Boolean {
        // 判断点击范围用转换后的坐标，记录点还用原来的坐标，因为平移之类的不能考虑旋转
        // 判断按下事件
        if (event.action == MotionEvent.ACTION_DOWN) {
            // 判断旋转按钮
            if (rotateBtn?.touchRect?.contains(restoredPoint.x, restoredPoint.y) == true) {
                pressedBtn = rotateBtn
                lastBtnTouchX = event.x
                lastBtnTouchY = event.y
                return true
            }
            // 判断其他按钮
            for (btn in buttons) {
                if (btn.touchRect.contains(restoredPoint.x, restoredPoint.y)) {
                    pressedBtn = btn
                    lastBtnTouchX = event.x
                    lastBtnTouchY = event.y
                    return true
                }
            }
            lastBtnTouchX = null
            lastBtnTouchY = null
        }
        return false
    }

    // 判断已按下的按钮的其他事件
    private fun touchButtons(event: Board.TouchEvent, restoredPoint: PointF): Boolean {
        // 按下事件并不在Widget中，不消耗事件
        val ltx = lastBtnTouchX ?: return false
        val lty = lastBtnTouchY ?: return false
        // 移动
        if (event.action == MotionEvent.ACTION_MOVE) {
            val pb = pressedBtn
            pb?.btn?.run {
                onDrag?.let {
                    val op = if (dragged) {
                        OperateStatus.OPERATING
                    } else {
                        dragged = true
                        OperateStatus.START
                    }
                    it.invoke(op, ltx, lty, event.x, event.y)
                }
            }
            lastBtnTouchX = event.x
            lastBtnTouchY = event.y
            return pb != null
        }
        // 抬起
        else if (event.action == MotionEvent.ACTION_UP) {
            val pb = pressedBtn
            if (pb?.touchRect?.contains(restoredPoint.x, restoredPoint.y) == true) {
                pb.btn.onClick?.invoke()
            }
            pressedBtn?.btn?.let {
                if (it.dragged) {
                    it.dragged = false
                    it.onDrag?.invoke(OperateStatus.END, ltx, lty, event.x, event.y)
                }
            }
            pressedBtn = null
            lastBtnTouchX = null
            lastBtnTouchY = null
            return pb != null
        }
        // 取消
        else if (event.action == MotionEvent.ACTION_CANCEL) {
            lastBtnTouchX = null
            lastBtnTouchY = null
            pressedBtn?.btn?.let {
                if (it.dragged) {
                    it.dragged = false
                    it.onDrag?.invoke(OperateStatus.END, ltx, lty, event.x, event.y)
                }
            }
            if (pressedBtn != null) {
                pressedBtn = null
                return true
            }
        }
        return false
    }

    // 生成旋转按钮
    private fun createRotateBtn() {
        val originalBtnAngle = -90
        rotateBtn = Btn(
            rect = RectF(),
            bmRect = RectF(),
            touchRect = RectF(),
            btn = CtlBtn(
                resId = R.drawable.board_ic_rotate,
                onClick = null,
                onDrag = { status, _, _, x, y ->
                    val angle = calculateAngle(x, y, widgetRect.centerX(), widgetRect.centerY())
                    val rotation = angle - originalBtnAngle
                    if (abs(rotation - onGetWidgetRotation.invoke()) < ROTATE_MIN_DEGREE) return@CtlBtn
                    onRotate.invoke(rotation, status)
                }
            ),
            bitmap = Board.appContext?.let { ctx ->
                try {
                    ContextCompat.getDrawable(ctx, R.drawable.board_ic_rotate)?.toBitmap()
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
        )
    }

    /** 一个path对应一个绘制的画笔 */
    private class PathAndPaint(val paint: Paint) : Path() {
        // path的点的范围
        val rect: RectF = RectF()

        override fun offset(dx: Float, dy: Float) {
            super.offset(dx, dy)
            this.rect.offset(dx, dy)
        }

        override fun addRect(rect: RectF, dir: Direction) {
            super.addRect(rect, dir)
            val diff = paint.strokeWidth / 2
            this.rect.union(
                rect.left.toInt() - diff,
                rect.top.toInt() - diff,
                rect.right.toInt() + diff,
                rect.bottom.toInt() + diff
            )
        }

        override fun moveTo(x: Float, y: Float) {
            super.moveTo(x, y)
            val diff = paint.strokeWidth / 2
            this.rect.union(
                x.toInt() - diff,
                y.toInt() - diff,
                x.toInt() + diff,
                y.toInt() + diff
            )
        }

        override fun lineTo(x: Float, y: Float) {
            super.lineTo(x, y)
            val diff = paint.strokeWidth / 2
            this.rect.union(
                x.toInt() - diff,
                y.toInt() - diff,
                x.toInt() + diff,
                y.toInt() + diff
            )
        }

        override fun reset() {
            super.reset()
            rect.setEmpty()
        }
    }

    /** 拉伸的控制手柄 */
    private class SizeHandler(
        val rect: RectF,
        var touchRect: RectF,
        /** 拉伸方向 */
        val anchor: Anchor
    )

    private class Btn(
        var rect: RectF,
        var bmRect: RectF,
        var touchRect: RectF,
        val btn: CtlBtn,
        var bitmap: Bitmap?
    )

    class CtlBtn(
        val resId: Int,
        val onClick: (() -> Unit)?,
        /** 上一个x,上一个y,移动的x,移动的y。OperateStatus.OPERATING的时候才有值*/
        val onDrag: ((OperateStatus, Float, Float, Float, Float) -> Unit)? = null
    ) {
        internal var dragged = false
    }
}