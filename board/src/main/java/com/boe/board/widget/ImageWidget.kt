package com.boe.board.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import com.boe.board.loadBitmapFromPath
import org.json.JSONObject

/** 图片组件 */
@Suppress("unused")
class ImageWidget private constructor(boardWidth: Float, boardHeight: Float) :
    BaseWidget(boardWidth, boardHeight) {

    private var path: String = ""
    private var bitmap: Bitmap? = null

    constructor(boardWidth: Float, boardHeight: Float, config: JSONObject) : this(
        boardWidth,
        boardHeight
    ) {
        fromConfig(config)
    }

    constructor(boardWidth: Float, boardHeight: Float, path: String, rect: RectF) : this(
        boardWidth,
        boardHeight
    ) {
        this.path = path
        this.rect = rect
        bitmap = loadBitmapFromPath(path, boardWidth.toInt(), boardHeight.toInt())
    }

    override fun fromConfig(config: JSONObject) {
        path = config.getString("path")
        bitmap = loadBitmapFromPath(path, boardWidth.toInt(), boardHeight.toInt())
        super.fromConfig(config)
    }

    override fun toConfig(): JSONObject {
        return super.toConfig().apply { put("path", path) }
    }

    override fun copy(): ImageWidget {
        val w = ImageWidget(boardWidth, boardHeight, toConfig())
        w.rect = checkedCopyWidgetRect(rect)
        return w
    }

    override fun drawContent(canvas: Canvas) {
        bitmap?.let { canvas.drawBitmap(it, null, rect, null) }
    }

    override fun dispose() {
        super.dispose()
        bitmap?.recycle()
        bitmap = null
    }
}