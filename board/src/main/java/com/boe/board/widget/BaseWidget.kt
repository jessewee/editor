package com.boe.board.widget

import android.graphics.*
import android.view.MotionEvent
import androidx.annotation.CallSuper
import androidx.core.graphics.withSave
import com.boe.board.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.json.JSONObject
import java.lang.Float.max
import java.lang.Float.min
import kotlin.math.abs

/** 各种类型的组件的基类 */
@Suppress("MemberVisibilityCanBePrivate", "unused")
abstract class BaseWidget(protected val boardWidth: Float, protected val boardHeight: Float) {
    var rotation: Float = 0F
    var rect: RectF = RectF()
    private val rectI: Rect
        get() = Rect(
            rect.left.toInt(),
            rect.top.toInt(),
            rect.right.toInt(),
            rect.bottom.toInt()
        )
    protected var selected: Boolean = false // 选中状态是在外层控制，调用刷新也是在外层
    protected var frameAndController: FrameAndController? = null

    /** 内容限制范围，移动缩放都不能超出这个范围。画板有缩放功能 */
    protected val safeRect: RectF = if (BOARD_MAX_SCALE > 1) {
        val dx = boardWidth * (BOARD_MAX_SCALE - 1) / 2
        val dy = boardHeight * (BOARD_MAX_SCALE - 1) / 2
        RectF(-dx, -dy, boardWidth + dx, boardHeight + dy)
    } else {
        RectF(0F, 0F, boardWidth, boardHeight)
    }

    /** 旋转缩放位移时记录步骤用的操作对象 */
    protected var transformStepOperation: Board.Step.Transform? = null

    /** 通知外层的回调 */
    internal var callback: Callback? = null

    /** 组件旋转后的所在区域。rect旋转后4个顶点的位置变了，新区域比rect大 */
    val transformedRect: RectF get() = rect.rotated(rotation)

    private var _scope: CoroutineScope? = null
    protected val scope: CoroutineScope
        get() {
            if (_scope == null) {
                _scope = CoroutineScope(Dispatchers.Default + Job())
            }
            return _scope!!
        }

    /** 组件信息读取 */
    open fun fromConfig(config: JSONObject) {
        val oldRect = rect
        val oldRotation = rotation
        rect = RectF(
            config.getDouble("left").toFloat(),
            config.getDouble("top").toFloat(),
            config.getDouble("right").toFloat(),
            config.getDouble("bottom").toFloat()
        )
        rotation = config.getDouble("rotation").toFloat()
        onTransformed(
            dx = rect.left - oldRect.left,
            dy = rect.top - oldRect.top,
            dw = rect.width() - oldRect.width(),
            dh = rect.height() - oldRect.height(),
            dr = rotation - oldRotation,
            operateStatus = OperateStatus.END,
            addStep = false
        )
    }

    /** 组件信息保存 */
    open fun toConfig(): JSONObject {
        val json = JSONObject()
        json.put("className", this::class.qualifiedName)
        json.put("left", rect.left)
        json.put("top", rect.top)
        json.put("right", rect.right)
        json.put("bottom", rect.bottom)
        json.put("rotation", rotation)
        return json
    }

    /** 复制组件 */
    abstract fun copy(): BaseWidget

    /** 绘制组件 */
    open fun onDraw(canvas: Canvas) {
        if (rect.width() == 0F || rect.height() == 0F) return
        canvas.withSave {
            if (rotation != 0F) rotate(rotation, rect.centerX(), rect.centerY())
            (frameAndController?.rect ?: rect).let {
                clipRect(it.left - 1, it.top - 1, it.right + 1, it.bottom + 1)
            }
            drawContent(canvas)
            frameAndController?.onDraw(canvas)
        }
    }

    /** 绘制在所有组件上层的内容 */
    open fun onDrawTop(canvas: Canvas) {
    }

    /** 组件的点击事件 */
    open fun onTouch(event: Board.TouchEvent): Boolean {
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

    /** 设置选中状态，选中后显示边框和控制按钮 */
    fun setSelectStatus(selected: Boolean) {
        this.selected = selected
        frameAndController = if (selected) {
            FrameAndController(
                boardWidth = boardWidth,
                widgetRect = rect,
                buttons = getControllerButtons(),
                onGetWidgetRotation = { rotation },
                onChangeSize = ::size,
                onRotate = ::rotate,
                onOffsetBy = ::offsetBy
            )
        } else {
            frameAndController?.dispose()
            null
        }
        onSelectStatusChanged()
    }

    /** 检查批量选择时所画的路径是否选中本组件。返回选中的组件对象，正常就是自身 */
    open fun checkIfSelectedByPath(path: Path): BaseWidget? {
        if (rect.isEmpty) return null
        val rotatedPath = if (abs(rotation % 360) < 1) {
            path
        } else {
            val tmp = Path()
            path.transform(
                Matrix().apply { this.setRotate(-rotation, rect.centerX(), rect.centerY()) },
                tmp
            )
            tmp
        }
        val pathBounds = RectF()
        rotatedPath.computeBounds(pathBounds, true)
        val region = Region().apply {
            setPath(
                rotatedPath,
                Region(
                    pathBounds.left.toInt(),
                    pathBounds.top.toInt(),
                    pathBounds.right.toInt(),
                    pathBounds.bottom.toInt()
                )
            )
        }
        return if (region.op(rectI, Region.Op.INTERSECT)) this else null
    }

    /**
     * 缩放组件。
     * [operateStatus] 开始、进行中、结束三个状态，在结束时才进行所在范围的判断处理
     * */
    fun sizeBy(
        diffScaleW: Float,
        diffScaleH: Float,
        operateStatus: OperateStatus,
        addStep: Boolean = true
    ) {
        if (diffScaleW == 0F && diffScaleH == 0F) {
            size(operateStatus = operateStatus, addStep = addStep)
            return
        }
        val dw = rect.width() * diffScaleW / 2
        val dh = rect.height() * diffScaleH / 2
        size(
            left = rect.left - dw,
            top = rect.top - dh,
            right = rect.right + dw,
            bottom = rect.bottom + dh,
            operateStatus = operateStatus,
            addStep = addStep
        )
    }

    /**
     * 改变组件大小。
     * [operateStatus] 开始、进行中、结束三个状态，在结束时才进行所在范围的判断处理
     * */
    fun size(
        left: Float = rect.left,
        top: Float = rect.top,
        right: Float = rect.right,
        bottom: Float = rect.bottom,
        operateStatus: OperateStatus,
        addStep: Boolean = true
    ) {
        transform(
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            rotation = rotation,
            operateStatus = operateStatus,
            addStep = addStep
        )
    }

    /**
     * 移动组件。
     * [operateStatus] 开始、进行中、结束三个状态，在结束时才进行所在范围的判断处理
     * */
    fun offsetBy(dx: Float, dy: Float, operateStatus: OperateStatus, addStep: Boolean = true) {
        transform(
            left = rect.left + dx,
            top = rect.top + dy,
            right = rect.right + dx,
            bottom = rect.bottom + dy,
            rotation = rotation,
            operateStatus = operateStatus,
            addStep = addStep
        )
    }

    /**
     * 旋转组件。
     * [operateStatus] 开始、进行中、结束三个状态，在结束时才进行所在范围的判断处理
     * */
    fun rotate(rotation: Float, operateStatus: OperateStatus, addStep: Boolean = true) {
        transform(
            left = rect.left,
            top = rect.top,
            right = rect.right,
            bottom = rect.bottom,
            rotation = rotation,
            operateStatus = operateStatus,
            addStep = addStep
        )
    }

    /** 销毁组件 */
    open fun dispose() {
        frameAndController?.dispose()
        frameAndController = null
        _scope?.cancel()
        _scope = null
    }

    /** 移动缩放旋转等操作 */
    fun transform(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        rotation: Float,
        operateStatus: OperateStatus,
        addStep: Boolean
    ) {
        if (operateStatus != OperateStatus.END) {
            val dx = left - this.rect.left
            val dy = top - this.rect.top
            val dw = right - left - this.rect.width()
            val dh = bottom - top - this.rect.height()
            val dr = rotation - this.rotation
            this.rect.left = left
            this.rect.top = top
            this.rect.right = right
            this.rect.bottom = bottom
            this.rotation = rotation
            onTransformed(dx, dy, dw, dh, dr, operateStatus, addStep)
            return
        }
        // 操作结束时要检查是否在范围内
        val l = min(left, right)
        val t = min(top, bottom)
        var r = max(left, right)
        var b = max(top, bottom)
        if (r - l < WIDGET_MIN_SIZE) r = l + WIDGET_MIN_SIZE
        if (b - t < WIDGET_MIN_SIZE) b = t + WIDGET_MIN_SIZE
        val cx = (l + r) * 0.5F
        val cy = (t + b) * 0.5F
        val dr = rotation - this.rotation
        this.rotation = rotation
        val lt = rotatePoint(rotation, l, t, cx, cy)
        val rt = rotatePoint(rotation, r, t, cx, cy)
        val lb = rotatePoint(rotation, l, b, cx, cy)
        val rb = rotatePoint(rotation, r, b, cx, cy)
        val rotatedLeft = min(min(lt.x, rt.x), min(lb.x, rb.x))
        val rotatedTop = min(min(lt.y, rt.y), min(lb.y, rb.y))
        val rotatedRight = max(max(lt.x, rt.x), max(lb.x, rb.x))
        val rotatedBottom = max(max(lt.y, rt.y), max(lb.y, rb.y))
        val rotatedDx = when {
            rotatedLeft < safeRect.left -> safeRect.left - rotatedLeft
            rotatedRight > safeRect.right -> safeRect.right - rotatedRight
            else -> 0F
        }
        val rotatedDy = when {
            rotatedTop < safeRect.top -> safeRect.top - rotatedTop
            rotatedBottom > safeRect.bottom -> safeRect.bottom - rotatedBottom
            else -> 0F
        }
        val finalLeft = l + rotatedDx
        val finalTop = t + rotatedDy
        val finalRight = r + rotatedDx
        val finalBottom = b + rotatedDy
        val dx = finalLeft - this.rect.left
        val dy = finalTop - this.rect.top
        val dw = finalRight - finalLeft - this.rect.width()
        val dh = finalBottom - finalTop - this.rect.height()
        this.rect.left = finalLeft
        this.rect.top = finalTop
        this.rect.right = finalRight
        this.rect.bottom = finalBottom
        onTransformed(dx, dy, dw, dh, dr, operateStatus, addStep)
    }

    /** 撤销 */
    internal open fun undo(operation: Board.Step.Operation) {
        if (operation !is Board.Step.Transform) return
        transform(
            left = rect.left - operation.dx,
            top = rect.top - operation.dy,
            right = rect.right - operation.dx - operation.dw,
            bottom = rect.bottom - operation.dy - operation.dh,
            rotation = rotation - operation.dr,
            operateStatus = OperateStatus.END,
            addStep = false
        )
        // 通知更新
        callback?.onUpdate()
    }

    /** 重做 */
    internal open fun redo(operation: Board.Step.Operation) {
        if (operation !is Board.Step.Transform) return
        transform(
            left = rect.left + operation.dx,
            top = rect.top + operation.dy,
            right = rect.right + operation.dx + operation.dw,
            bottom = rect.bottom + operation.dy + operation.dh,
            rotation = rotation + operation.dr,
            operateStatus = OperateStatus.END,
            addStep = false
        )
        // 通知更新
        callback?.onUpdate()
    }

    /** 选中状态改变 */
    protected open fun onSelectStatusChanged() {}

    /** 检查是否点中选中区域。正常组件是在rect中就可以，手写组件的话是点中单个笔画的rect算选中，点笔画中间的空白不算 */
    protected open fun checkIfInSelectArea(event: Board.TouchEvent): Boolean {
        return rect.contains(event.x, event.y)
    }

    /** 检查复制时新组件的rect，正常复制出来的新组建往右下偏移一点，如果已经在画板右下边界的话，改成往左上偏移 */
    protected fun checkedCopyWidgetRect(src: RectF): RectF {
        val rect = RectF(src)
        val diff = 20F
        rect.offset(diff, diff)
        if (rect.right > safeRect.right || rect.bottom > safeRect.bottom) {
            rect.offset(-diff * 2, -diff * 2)
        }
        return rect
    }

    /** 绘制组件内容 */
    protected abstract fun drawContent(canvas: Canvas)

    /** 组件里边的内容的点击事件，子类可以重写这个方法来处理自己的点击事件 */
    protected open fun touchContent(event: Board.TouchEvent, restoredPoint: PointF): Boolean {
        return false
    }

    /**
     * 组件大小、位置、旋转角度改变的回调。记录操作步骤也是在这里处理
     * [dx] x坐标差值
     * [dy] y坐标差值
     * [dw] 宽度差值
     * [dh] 高度差值
     * [dr] 旋转角度差值
     * [operateStatus] 开始、进行中、结束三个状态，在结束时才进行所在范围的判断处理
     * */
    @CallSuper
    protected open fun onTransformed(
        dx: Float = 0F,
        dy: Float = 0F,
        dw: Float = 0F,
        dh: Float = 0F,
        dr: Float = 0F,
        operateStatus: OperateStatus,
        addStep: Boolean
    ) {
        if (dx != 0F || dy != 0F || dw != 0F || dh != 0F) {
            frameAndController?.updateWidgetRect(rect)
        }
        if (dx != 0F || dy != 0F || dw != 0F || dh != 0F || dr != 0F) {
            callback?.onUpdate()
        }
        when (operateStatus) {
            OperateStatus.START -> {
                transformStepOperation =
                    if (addStep) Board.Step.Transform(dx, dy, dw, dh, dr) else null
            }
            OperateStatus.OPERATING -> {
                transformStepOperation?.run {
                    this.dx += dx
                    this.dy += dy
                    this.dw += dw
                    this.dh += dh
                    this.dr += dr
                }
            }
            OperateStatus.END -> {
                val operation = transformStepOperation?.apply {
                    this.dx += dx
                    this.dy += dy
                    this.dw += dw
                    this.dh += dh
                    this.dr += dr
                }
                transformStepOperation = null
                if (addStep && operation != null) callback?.onStep(Board.Step(this, operation))
            }
        }
    }

    /** 获取控制按钮列表 */
    protected open fun getControllerButtons(): List<FrameAndController.CtlBtn> {
        return listOf(
            // 移动
            FrameAndController.CtlBtn(
                resId = R.drawable.board_ic_move,
                onClick = null,
                onDrag = { status, lastX, lastY, x, y ->
                    val dx = x - lastX
                    val dy = y - lastY
                    offsetBy(dx, dy, status)
                }
            ),
            // 缩小
            FrameAndController.CtlBtn(
                resId = R.drawable.board_ic_zoom_out,
                onClick = {
                    // 限制最小比例
                    if (rect.width() < boardWidth / 10 || rect.height() < boardHeight / 10 || rect.width() <= 0 || rect.height() <= 0) {
                        return@CtlBtn
                    }
                    sizeBy(-0.25F, -0.25F, OperateStatus.END)
                },
                onDrag = null
            ),
            // 放大
            FrameAndController.CtlBtn(
                resId = R.drawable.board_ic_zoom_in,
                onClick = {
                    // 限制最大比例
                    if (rect.width() > boardWidth * 2 || rect.height() > boardHeight * 2) {
                        return@CtlBtn
                    }
                    sizeBy(0.25F, 0.25F, OperateStatus.END)
                },
                onDrag = null
            ),
            // 复制
            FrameAndController.CtlBtn(
                resId = R.drawable.board_ic_copy,
                onClick = { callback?.onCopy() },
                onDrag = null
            ),
            // 删除
            FrameAndController.CtlBtn(
                resId = R.drawable.board_ic_delete,
                onClick = { callback?.onDelete() },
                onDrag = null
            )
        )
    }

    /** 用来通知外层的回调 */
    interface Callback {
        fun onUpdate()
        fun onDelete()
        fun onCopy()

        /**
         * 通知外层记录步骤，用来撤销重做。
         * 移动缩放旋转都在基类中调用，
         * 内容改变在子类中调用，
         * 添加删除复制在外层操作，这里不用调用。列外是添加形状时拖动抬起才算添加完成，所以是在形状组件里添加的
         * 防止重复添加
         * */
        fun onStep(step: Board.Step)
    }
}