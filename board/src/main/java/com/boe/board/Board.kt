package com.boe.board

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import androidx.core.graphics.withSave
import com.boe.board.widget.*
import org.json.JSONArray
import java.io.File
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** 画板。当前选中的widget不会放到最上层，所以绘制的时候有上下两层静态画布和中间的动态widget画布，共三层 */
@Suppress("MemberVisibilityCanBePrivate")
class Board(
    context: Context,
    val width: Float,
    val height: Float,
    private val onUpperWidgetsUpdate: () -> Unit,
    private val onLowerWidgetsUpdate: () -> Unit,
    private val onActiveWidgetsUpdate: () -> Unit
) {

    /** 手写组件，设置笔的类型和粗细之类的直接在组件里设置 */
    internal val handwriteWidget: HandwriteWidget? get() = widgets.findLast { it is HandwriteWidget } as? HandwriteWidget

    internal var onHandwriteStatusChangeListener: ((Boolean) -> Unit)? = null

    /** 获取手写组件，没有的话自动添加 */
    private val checkedHandwriteWidget: HandwriteWidget
        get() {
            val hw: HandwriteWidget
            val tmp = widgets.findLast { it is HandwriteWidget }
            if (tmp == null) {
                hw = HandwriteWidget(width, height, scale, ::getHandwriteDeadAreas)
                doAddWidget(hw)
            } else {
                hw = tmp as HandwriteWidget
            }
            return hw
        }

    // 画板缩放比例
    private var scale = 1F

    // 缩放时用到的中心点
    private val cx = width / 2F
    private val cy = height / 2F

    // 所有的组件列表
    private val widgets = mutableListOf<BaseWidget>()

    // 确保设置无选中widget的时候是-1，因为在绘制的时候会用这个值+1来当作上层的起始索引
    private var activeWidgetIdx: Int = -1

    // 当前选中的widget
    private val activeWidget: BaseWidget? get() = if (activeWidgetIdx in widgets.indices) widgets[activeWidgetIdx] else null

    // 操作步骤，用来撤销
    private val steps = mutableListOf<Step>()

    // 已撤销的步骤，重做的时候使用
    private val undoneSteps = mutableListOf<Step>()

    // 批量选择相关
    private var batchSelectWidget: BatchSelectWidget? = null

    init {
        appContext = context.applicationContext
        board = WeakReference(this)
    }

    /** 销毁 */
    fun dispose() {
        board = null
        for (w in widgets) w.dispose()
    }

    /** 保存 */
    fun save(path: String): Boolean {
        return try {
            val array = JSONArray()
            for (w in widgets) array.put(w.toConfig())
            val file = File(path)
            file.parentFile?.run { if (!exists()) mkdirs() }
            file.writeText(array.toString())
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /** 加载 */
    fun load(path: String): Boolean {
        if (path.isBlank()) return false
        val file = File(path)
        if (!file.exists()) return false
        try {
            val oldHandwriteStatus = handwriteWidget?.handwriteStatus == true
            val text = file.readText()
            val array = JSONArray(text)
            activeWidgetIdx = -1
            for (w in widgets) w.dispose()
            widgets.clear()
            for (i in 0 until array.length()) {
                val config = array.optJSONObject(i) ?: continue
                when (config.optString("className")) {
                    ImageWidget::class.qualifiedName -> {
                        doAddWidget(ImageWidget(width, height, config))
                    }
                    ShapeWidget::class.qualifiedName -> {
                        doAddWidget(ShapeWidget(width, height, config))
                    }
                    TextWidget::class.qualifiedName -> {
                        doAddWidget(TextWidget(width, height, config))
                    }
                    HandwriteWidget::class.qualifiedName -> {
                        doAddWidget(
                            HandwriteWidget(
                                boardWidth = width,
                                boardHeight = height,
                                boardScale = scale,
                                onGetDeadArea = ::getHandwriteDeadAreas,
                                config = config
                            )
                        )
                    }
                }
            }
            changeHandwriteStatus(oldHandwriteStatus)
            updateAll()
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    /** 更改画布比例 */
    fun setScale(scale: Float) {
        val s = when {
            scale < BOARD_MIN_SCALE -> BOARD_MIN_SCALE
            scale > BOARD_MAX_SCALE -> BOARD_MAX_SCALE
            else -> scale
        }
        // 比例改变小于0.1时忽略
        if ((s * 10).roundToInt() == (this.scale * 10).roundToInt()) return
        this.scale = s
        handwriteWidget?.setBoardScale(s)
        updateAll()
    }

    /** 添加组件 */
    fun addWidget(widget: BaseWidget, recordStep: Boolean = true) {
        doAddWidget(widget)
        activeWidget?.setSelectStatus(false)
        activeWidgetIdx = widgets.size - 1
        widget.setSelectStatus(true)
        changeHandwriteStatus(false)
        updateAll()
        if (recordStep) addStep(Step(widget, Step.Add))
    }

    private fun doAddWidget(widget: BaseWidget) {
        widget.callback = object : BaseWidget.Callback {
            override fun onUpdate() = update(widget)
            override fun onDelete() = removeWidget(widget)
            override fun onCopy() = addWidget(widget.copy())
            override fun onStep(step: Step) {
                if (steps.lastOrNull() != step) addStep(step)
            }
        }
        widgets.add(widget)
    }

    /** 删除组件 */
    fun removeWidget(widget: BaseWidget, recordStep: Boolean = true) {
        val idx = widgets.indexOf(widget)
        val r = widgets.remove(widget)
        if (!r) return
        if (idx == activeWidgetIdx) activeWidgetIdx = -1
        updateAll()
        if (recordStep) addStep(Step(widget, Step.Delete))
    }

    /** 撤销 */
    fun undo() {
        val step = steps.removeLastOrNull() ?: return
        undoneSteps.add(step)
        for (target in step.targets) {
            when (target.operation) {
                // 撤销添加
                Step.Add -> {
                    val idx = widgets.indexOf(target.widget)
                    val r = widgets.remove(target.widget)
                    if (r && idx == activeWidgetIdx) {
                        target.widget.setSelectStatus(false)
                        activeWidgetIdx = -1
                    }
                    updateAll()
                }
                // 撤销删除，删除后widget不会再改变，所以直接用原对象还原就可以
                Step.Delete -> {
                    doAddWidget(target.widget)
                    updateAll()
                }
                // 更改层级
                is Step.ChangeLayer -> {
                    doChangeLayer(
                        step = -target.operation.diff,
                        fromIdx = widgets.indexOf(target.widget),
                        recordStep = false
                    )
                    updateAll()
                }
                // 其他
                else -> {
                    target.widget.undo(target.operation)
                }
            }
        }
    }

    /** 重做 */
    fun redo() {
        val step = undoneSteps.removeLastOrNull() ?: return
        addStep(step, clearUndone = false)
        for (target in step.targets) {
            when (target.operation) {
                // 添加，撤销添加操作时widget已经从列表里删除，之后不会再有修改，所以重做时直接用原widget就可以
                Step.Add -> {
                    doAddWidget(target.widget)
                    updateAll()
                }
                // 删除
                Step.Delete -> {
                    val idx = widgets.indexOf(target.widget)
                    val r = widgets.remove(target.widget)
                    if (r && idx == activeWidgetIdx) {
                        target.widget.setSelectStatus(false)
                        activeWidgetIdx = -1
                    }
                    updateAll()
                }
                // 更改层级
                is Step.ChangeLayer -> {
                    doChangeLayer(
                        step = target.operation.diff,
                        fromIdx = widgets.indexOf(target.widget),
                        recordStep = false
                    )
                    addStep(step, clearUndone = false)
                    updateAll()
                }
                // 其他
                else -> {
                    target.widget.redo(target.operation)
                }
            }
        }
        updateAll()
    }

    /**
     * 移动当前选中组件的层级。
     * [step]正数是往上移，复数是往下移，超过索引范围会按照边界值设置。
     * 比如传入Int.MAX_VALUE会移动到最上层，传入Int.MIN_VALUE会移动到最下层
     * 传入1是上移一层，传入-2是下移两层
     * */
    fun changeLayer(step: Int) {
        doChangeLayer(step, activeWidgetIdx)?.let { activeWidgetIdx = it }
    }

    // 返回改变后的层级
    private fun doChangeLayer(step: Int, fromIdx: Int, recordStep: Boolean = true): Int? {
        if (step == 0 || fromIdx < 0) return null
        val diff: Int =
            // 上移
            if (step > 0) {
                val tmp = min(widgets.size - 1 - fromIdx, step)
                if (tmp <= 0) return null
                tmp
            }
            // 下移
            else {
                val tmp = min(
                    fromIdx,
                    abs(step.let { if (it == Int.MIN_VALUE) Int.MIN_VALUE + 1 else it }) // abs方法的注释里有说明，Int.MIN_VALUE的abs还是Int.MIN_VALUE，所以这里处理一下
                )
                if (tmp <= 0) return null
                -tmp
            }
        // 移动并更新显示
        val w = widgets.removeAt(fromIdx)
        val newIdx = fromIdx + diff
        widgets.add(newIdx, w)
        updateAll()
        if (recordStep) addStep(Step(w, Step.ChangeLayer(diff)))
        return newIdx
    }

    /** 批量选择 */
    fun batchSelect() {
        val bsw = BatchSelectWidget(
            boardWidth = width,
            boardHeight = height,
            onSelect = { path -> widgets.mapNotNull { it.checkIfSelectedByPath(path) } },
            onDismiss = {
                cancelBatchSelect()
                updateAll()
            }
        )
        bsw.callback = object : BaseWidget.Callback {
            override fun onUpdate() {
                updateAll()
            }

            override fun onDelete() {
                if (bsw.widgets.isNotEmpty()) widgets.removeAll(bsw.widgets)
                cancelBatchSelect()
                updateAll()
                addStep(Step(bsw.widgets.map { Step.Target(it, Step.Delete) }))
            }

            override fun onCopy() {
                val list = bsw.copyWidgets()
                for (w in list) doAddWidget(w)
                updateAll()
                addStep(Step(list.map { Step.Target(it, Step.Add) }))
            }

            override fun onStep(step: Step) {
                addStep(step)
            }
        }
        this.batchSelectWidget = bsw
        onUpperWidgetsUpdate.invoke()
        if (activeWidgetIdx >= 0) {
            activeWidget?.setSelectStatus(false)
            activeWidgetIdx = -1
            onActiveWidgetsUpdate.invoke()
        }
    }

    /** 取消批量选择 */
    fun cancelBatchSelect() {
        if (batchSelectWidget == null) return
        batchSelectWidget?.dispose()
        batchSelectWidget = null
    }

    /** 改变手写/编辑状态 */
    internal fun changeHandwriteStatus(handwriteStatus: Boolean) {
        if (handwriteStatus) checkedHandwriteWidget.setHandwriteStatus(handwriteStatus)
        else handwriteWidget?.setHandwriteStatus(handwriteStatus)
        onHandwriteStatusChangeListener?.invoke(handwriteStatus)
        if (handwriteStatus && activeWidgetIdx >= 0) {
            activeWidget?.setSelectStatus(false)
            activeWidgetIdx = -1
            updateAll()
        }
    }

    /** 画板的触摸事件 */
    fun touch(event: TouchEvent) {
        checkTouchScale(event)
        // 手写状态下，手写组件优先
        if (handwriteWidget?.handwriteStatus == true) {
            handwriteWidget?.onTouch(event)
            return
        }
        // 批量选择状态
        if (batchSelectWidget?.onTouch(event) == true) return
        if (batchSelectWidget != null) {
            cancelBatchSelect()
            updateAll()
        }
        // 当前选中的单个组件
        if (activeWidget?.onTouch(event) == true) return
        // 其他组件的事件
        for (i in widgets.size - 1 downTo 0) {
            val w = widgets[i]
            // 选中widget
            if (w.onTouch(event)) {
                if (activeWidgetIdx == i) return
                activeWidget?.setSelectStatus(false)
                w.setSelectStatus(true)
                activeWidgetIdx = i
                updateAll()
                return
            }
        }
        // 取消选中widget
        if (activeWidgetIdx >= 0) {
            activeWidget?.setSelectStatus(false)
            activeWidgetIdx = -1
            updateAll()
        }
    }

    /** 绘制当前活动的widget */
    fun drawActiveWidgets(canvas: Canvas) {
        checkDrawScale(canvas) { activeWidget?.onDraw(canvas) }
    }

    /** 绘制当前活动widget之上的静态widget */
    fun drawUpperWidgets(canvas: Canvas) {
        checkDrawScale(canvas) {
            if (activeWidgetIdx in -1 until widgets.size - 1) {
                for (i in activeWidgetIdx + 1 until widgets.size) widgets[i].onDraw(canvas)
            }
            for (w in widgets) w.onDrawTop(canvas)
            batchSelectWidget?.onDraw(canvas)
        }
    }

    /** 绘制当前活动widget之下的静态widget */
    fun drawLowerWidgets(canvas: Canvas) {
        checkDrawScale(canvas) {
            if (activeWidgetIdx > 0) for (i in 0 until activeWidgetIdx) widgets[i].onDraw(canvas)
        }
    }

    // 检查画板缩放比例，如果不是1的话把触屏事件的xy值设置成对应比例下的xy值
    private fun checkTouchScale(event: TouchEvent) {
        if (scale == 1F) return
        event.x = (event.x - cx) / scale + cx
        event.y = (event.y - cy) / scale + cy
    }

    // 检查画板缩放比例，如果不是1的话设置canvas的缩放
    private fun checkDrawScale(canvas: Canvas, onDraw: () -> Unit) {
        if (scale == 1F) {
            onDraw.invoke()
            return
        }
        canvas.withSave {
            scale(scale, scale, cx, cy)
            onDraw.invoke()
        }
    }

    // 通知外层更新
    private fun update(widget: BaseWidget) {
        val widgetIdx = widgets.indexOf(widget)
        when {
            // 更新活动widget的情况
            widgetIdx == activeWidgetIdx -> onActiveWidgetsUpdate.invoke()
            // 更新上层widget的情况
            widgetIdx > activeWidgetIdx -> onUpperWidgetsUpdate.invoke()
            // 更新下层widget的情况
            else -> onLowerWidgetsUpdate.invoke()
        }
    }

    private fun updateAll() {
        onUpperWidgetsUpdate.invoke()
        onActiveWidgetsUpdate.invoke()
        onLowerWidgetsUpdate.invoke()
    }

    // 添加操作步骤
    private fun addStep(step: Step, clearUndone: Boolean = true) {
        if (step.targets.all { it.widget.rect.isEmpty }) return // 添加形状时默认大小时0，拖动抬起才算添加完成
        steps.add(step)
        if (clearUndone) undoneSteps.clear()
        // 判断最大步骤
        if (steps.size > 99) steps.removeFirst()
    }

    /** 当前手写组件之上的组件的区域，用来控制手写的禁用区域 */
    private fun getHandwriteDeadAreas(): List<RectF> {
        val list = mutableListOf<RectF>()
        for (w in widgets) {
            list.add(RectF(w.rect))
            if (w is HandwriteWidget) list.clear()
        }
        return list
    }

    companion object {
        internal var appContext: Context? = null
        private var board: WeakReference<Board>? = null
    }

    /** 操作步骤，用来撤销重做 */
    data class Step(val targets: List<Target>) {

        constructor(widget: BaseWidget, operation: Operation)
                : this(listOf(Target(widget, operation)))

        data class Target(val widget: BaseWidget, val operation: Operation)

        /** 记录步骤时的操作，子组件可以继承这个类来细化步骤 */
        open class Operation
        object Delete : Operation()
        object Add : Operation()

        /** 移动、改变大小、旋转 */
        class Transform(
            var dx: Float = 0F,
            var dy: Float = 0F,
            var dw: Float = 0F,
            var dh: Float = 0F,
            var dr: Float = 0F
        ) : Operation()

        /** 更改显示层级 */
        class ChangeLayer(val diff: Int) : Operation()
    }


    data class TouchEvent(
        /** MotionEvent.ACTION_ */
        var action: Int,
        var x: Float,
        var y: Float,
        val pressure: Float,
        /** MotionEvent.TOOL_TYPE_ */
        val toolType: Int
    ) {
        companion object {
            fun fromMotionEvent(event: MotionEvent): TouchEvent =
                TouchEvent(
                    action = event.action,
                    x = event.x,
                    y = event.y,
                    pressure = event.pressure,
                    toolType = event.getToolType(0)
                )
        }
    }
}

/** 画板上初始化组件时的最大区域 */
internal fun suggestedMaxInitialRect(boardWidth: Float, boardHeight: Float): RectF {
    val l = boardWidth / 10F
    val t = boardHeight / 10F
    val maxW = boardWidth / 10 * 8F
    val maxH = boardHeight / 10 * 8F
    return RectF(l, t, l + maxW, t + maxH)
}