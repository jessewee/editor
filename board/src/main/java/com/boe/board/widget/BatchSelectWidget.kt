package com.boe.board.widget

import android.graphics.*
import android.util.Log
import android.view.MotionEvent
import com.boe.board.*
import kotlin.math.cos
import kotlin.math.sin

/** 批量选择选中的组件分组，这里不负责绘制选中的组件，只绘制控制器 */
class BatchSelectWidget(
    boardWidth: Float,
    boardHeight: Float,
    private val onSelect: (Path) -> List<BaseWidget>,
    private val onDismiss: () -> Unit
) : BaseWidget(boardWidth, boardHeight) {

    // 画圈选择时的路径
    private val path = Path()

    // 绘制路径的画笔
    private val paint = Paint().apply {
        pathEffect = DashPathEffect(floatArrayOf(5F, 10F), 0F)
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = 2F
    }

    /** 选中的组件列表，空列表表示处于待选择状态 */
    private var _widgets: List<WidgetWithInfo> = emptyList()

    /** 选中的组件列表，空列表表示处于待选择状态 */
    val widgets: List<BaseWidget> get() = _widgets.map { it.widget }

    // 记录操作步骤用到的数据
    private var stepTargets: List<Board.Step.Target>? = null

    init {
        this.rect = RectF(safeRect)
    }

    override fun onSelectStatusChanged() {
        super.onSelectStatusChanged()
        if (!selected) onDismiss.invoke()
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
        super.onTransformed(dx, dy, dw, dh, dr, operateStatus, false)
        if (operateStatus == OperateStatus.START) {
            stepTargets = if (addStep) {
                _widgets.map {
                    Board.Step.Target(it.widget, Board.Step.Transform(0F, 0F, 0F, 0F, 0F))
                }
            } else {
                null
            }
        }
        // 跟parent不同角度的组件计算结束后改变了parent的大小，自身所占的位置和大小比例也变了，后边要重新计算
        var changed = false
        Log.e("============1", "dx:$dx,dy:$dy,dw:$dw,dh:$dh,dr:$dr")
        if (dx != 0F || dy != 0F || dw != 0F || dh != 0F || dr != 0F) {
            for (w in _widgets) {
                // 计算中心点位置偏移
                val tmpCx = w.cxPercentInParent * rect.width() + rect.left
                val tmpCy = w.cyPercentInParent * rect.height() + rect.top
                val widgetCenter =
                    rotatePoint(rotation, tmpCx, tmpCy, rect.centerX(), rect.centerY())
                val widgetDx = widgetCenter.x - w.widget.rect.centerX()
                val widgetDy = widgetCenter.y - w.widget.rect.centerY()
                // 旋转角度差
                val widgetDr = w.initialRotation + rotation - w.widget.rotation
                // 宽高差
                val widgetDw: Float
                val widgetDh: Float
                // 宽高没变
                if (dw == 0F && dh == 0F) {
                    widgetDw = 0F
                    widgetDh = 0F
                }
                // 同角度的情况
                else if (w.initialRotation % 360 == 0F) {
                    widgetDw = dw * w.wPercentInParent
                    widgetDh = dh * w.hPercentInParent
                }
                // 不同角度的情况，需要计算 TODO 有旋转角度的组件拉伸时方向不对
                else {
//                    val tmpW = dw * w.wPercentInParent
//                    val tmpH = dh * w.hPercentInParent
//                    val r: Float
//                    val len: Float
//                    when {
//                        tmpH == 0F -> {
//                            r = 0F
//                            len = tmpW
//                        }
//                        tmpW == 0F -> {
//                            r = PI.toFloat() / 2
//                            len = tmpH
//                        }
//                        else -> {
//                            r = atan(tmpH / tmpW)
//                            len = tmpH / sin(r)
//                        }
//                    }
//                    val tmpDr = r - angleToRadian(w.initialRotation)
//                    widgetDw = len * cos(tmpDr)
//                    widgetDh = len * sin(tmpDr)
//                    changed = true
                    val tmpW = dw * w.wPercentInParent
                    val tmpH = dh * w.hPercentInParent
                    val radian = angleToRadian(w.initialRotation)
                    val cosR = cos(radian)
                    val sinR = sin(radian)
                    widgetDw = tmpW * cosR + tmpH * sinR
                    widgetDh = tmpH * cosR + tmpW * sinR
                    changed = true
                }
                // 记录并执行转换
                (stepTargets?.find { it.widget == w.widget }?.operation as? Board.Step.Transform)?.run {
                    this.dx += widgetDx
                    this.dy += widgetDy
                    this.dw += widgetDw
                    this.dh += widgetDh
                    this.dr += widgetDr
                }
                Log.e(
                    "============a",
                    "widgetDx:$widgetDx," +
                            "widgetDy:$widgetDy," +
                            "widgetDw:$widgetDw," +
                            "widgetDh:$widgetDh," +
                            "widgetDr:$widgetDr," +
                            "rotation:${rotation}," +
                            "tmpCx:$tmpCx," +
                            "tmpCy:$tmpCy," +
                            "widgetCenterX:${widgetCenter.x}," +
                            "widgetCenterY:${widgetCenter.y}," +
                            "${w.widget.rect}"
                )
                w.widget.transform(
                    left = w.widget.rect.left + widgetDx - widgetDw / 2,
                    top = w.widget.rect.top + widgetDy - widgetDh / 2,
                    right = w.widget.rect.right + widgetDx + widgetDw / 2,
                    bottom = w.widget.rect.bottom + widgetDy + widgetDh / 2,
                    rotation = w.widget.rotation + widgetDr,
                    operateStatus = operateStatus,
                    addStep = false
                )
                Log.e("==========================================", "${w.widget.rect}")
            }
        }
        // 操作结束时各组件要检查是否符合最小尺寸和安全范围之类的，因为有!=0F的判断，可能会跳过，所以最后再检查一下
        else if (operateStatus == OperateStatus.END) {
            for (w in _widgets) w.widget.transform(
                left = w.widget.rect.left,
                top = w.widget.rect.top,
                right = w.widget.rect.right,
                bottom = w.widget.rect.bottom,
                rotation = w.widget.rotation,
                operateStatus = operateStatus,
                addStep = false
            )
        }
        // 不同角度的情况下边框可能变了
        if (changed) {
            val newRect = RectF()
            for (w in _widgets) newRect.union(w.widget.rect.rotated(w.initialRotation))
            this.rect = newRect
            frameAndController?.updateWidgetRect(rect)
            // 重新计算组件位置
            for (w in _widgets) {
                // 同角度下的组件位置和大小占比不会变，所以不需要重新计算
                if (w.initialRotation % 360 == 0F) continue
                val tr = w.widget.rect.rotated(w.initialRotation)
                w.cxPercentInParent = (w.widget.rect.centerX() - rect.left) / rect.width()
                w.cyPercentInParent = (w.widget.rect.centerY() - rect.top) / rect.height()
                w.wPercentInParent = tr.width() / rect.width()
                w.hPercentInParent = tr.height() / rect.height()
            }
        }
        if (operateStatus == OperateStatus.END) {
            val targets = stepTargets
            stepTargets = null
            if (addStep && targets != null) callback?.onStep(Board.Step(targets))
        }
    }

    override fun copy(): BaseWidget =
        BatchSelectWidget(boardWidth, boardHeight, onSelect, onDismiss)

    override fun drawContent(canvas: Canvas) {
        if (!path.isEmpty) canvas.drawPath(path, paint)
    }

    override fun touchContent(event: Board.TouchEvent, restoredPoint: PointF): Boolean {
        if (this._widgets.isNotEmpty()) return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                path.reset()
                path.moveTo(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                path.lineTo(event.x, event.y)
            }
            MotionEvent.ACTION_UP -> {
                path.close()
                doSelect()
                path.reset()
            }
            MotionEvent.ACTION_CANCEL -> {
                path.reset()
            }
        }
        callback?.onUpdate()
        return true
    }

    fun copyWidgets(): List<BaseWidget> = _widgets.map { it.widget.copy() }

    private fun doSelect() {
        val list = onSelect.invoke(path)
        if (list.isEmpty()) return
        rect = RectF()
        for (w in list) rect.union(w.transformedRect)
        setSelectStatus(true)
        _widgets = list.map {
            val tr = it.transformedRect
            WidgetWithInfo(
                widget = it,
                initialRotation = it.rotation,
                cxPercentInParent = (it.rect.centerX() - rect.left) / rect.width(),
                cyPercentInParent = (it.rect.centerY() - rect.top) / rect.height(),
                wPercentInParent = tr.width() / rect.width(),
                hPercentInParent = tr.height() / rect.height()
            )
        }
    }

    /** 记录一些组件初始选中时的信息 */
    private data class WidgetWithInfo(
        val widget: BaseWidget,
        /** 选中时组件的旋转角度，也是组件跟parent的角度差 */
        val initialRotation: Float,
        /** 组件中心点在parent里的横坐标方向上的百分比 */
        var cxPercentInParent: Float,
        /** 组件中心点在parent里的纵坐标方向上的百分比 */
        var cyPercentInParent: Float,
        /** 组件旋转后的方形在parent里的宽度的占比 */
        var wPercentInParent: Float,
        /** 组件旋转后的方形在parent里的高度的占比 */
        var hPercentInParent: Float,
    )
}