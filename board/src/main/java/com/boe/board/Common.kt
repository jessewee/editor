@file:Suppress("unused")

package com.boe.board

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import kotlin.math.*

val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

/** 加载图片 */
fun loadBitmapFromPath(
    path: String,
    maxWidth: Int = 0,
    maxHeight: Int = 0
): Bitmap? {
    if (maxWidth == 0 && maxHeight == 0) return BitmapFactory.decodeFile(path)
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, options)
    val scale = 1 / when {
        maxWidth == 0 -> maxHeight / options.outHeight.toFloat()
        maxHeight == 0 -> maxWidth / options.outWidth.toFloat()
        else -> min(maxWidth / options.outWidth.toFloat(), maxHeight / options.outHeight.toFloat())
    }
    options.inJustDecodeBounds = false
    if (scale > 1) options.inSampleSize = scale.roundToInt()
    return BitmapFactory.decodeFile(path, options)
}

/** 图片在边框范围内按比例缩放后的大小 */
fun fitImgSize(imgW: Int, imgH: Int, frameW: Int, frameH: Int): Size {
    if (frameW <= 0 && frameH <= 0) return Size(imgW, imgH)
    val tarW = if (frameW <= 0) imgW else frameW
    val tarH = if (frameH <= 0) imgH else frameH
    val tarScale: Float = tarW / tarH.toFloat()
    val imgScale: Float = imgW / imgH.toFloat()
    return when {
        tarScale > imgScale -> Size(tarH, (imgScale * tarH).toInt())
        tarScale < imgScale -> Size(tarW, (tarW / imgScale).toInt())
        else -> Size(tarW, tarH)
    }
}

fun View.afterLayoutChanged(callback: () -> Unit) {
    viewTreeObserver.addOnGlobalLayoutListener(
        object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                viewTreeObserver.removeOnGlobalLayoutListener(this)
                callback.invoke()
            }
        }
    )
}

val View.locationInWindow: IntArray get() = IntArray(2).apply { getLocationInWindow(this) }
val View.locationOnScreen: IntArray get() = IntArray(2).apply { getLocationOnScreen(this) }

fun View.showSoftKeyboard() {
    try {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(this, InputMethodManager.SHOW_FORCED)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

fun View.hideSoftKeyboard() {
    try {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(windowToken, 0)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

/** 角度转弧度 */
fun angleToRadian(angle: Float): Float = angle * PI.toFloat() / 180

/** 弧度转角度 */
fun radianToAngle(radian: Float): Float = radian * 180 / PI.toFloat()

/** 计算两点之间的角度 */
fun calculateRadian(x: Float, y: Float, px: Float, py: Float): Float {
    return atan2(y - py, x - px)
}

/** 计算两点之间的角度 */
fun calculateAngle(x: Float, y: Float, px: Float, py: Float): Float {
    return radianToAngle(calculateRadian(x, y, px, py))
}

/** 计算旋转后的点 */
fun rotatePoint(angle: Float, x: Float, y: Float, px: Float, py: Float): PointF {
    if (angle % 360 == 0F) return PointF(x, y)
    val radian = angleToRadian(angle)
    return PointF(
        (x - px) * cos(radian) - (y - py) * sin(radian) + px,
        (x - px) * sin(radian) + (y - py) * cos(radian) + py
    )
}

/** 计算旋转后的点 */
fun rotatePointByRadian(radian: Float, x: Float, y: Float, px: Float, py: Float): PointF {
    if (radian % (PI.toFloat() * 2) == 0F) return PointF(x, y)
    return PointF(
        (x - px) * cos(radian) - (y - py) * sin(radian) + px,
        (x - px) * sin(radian) + (y - py) * cos(radian) + py
    )
}

/** 还原经过旋转的点 */
fun restoreFromRotatedPoint(angle: Float, x: Float, y: Float, px: Float, py: Float): PointF {
    return rotatePoint(360 - angle, x, y, px, py)
}

/** 计算旋转后的方形 */
fun RectF.rotated(rotation: Float): RectF {
    if (rotation % 360 == 0F) return RectF(this)
    val lt = rotatePoint(rotation, left, top, centerX(), centerY())
    val rt = rotatePoint(rotation, right, top, centerX(), centerY())
    val lb = rotatePoint(rotation, left, bottom, centerX(), centerY())
    val rb = rotatePoint(rotation, right, bottom, centerX(), centerY())
    return RectF(
        min(min(lt.x, rt.x), min(lb.x, rb.x)),
        min(min(lt.y, rt.y), min(lb.y, rb.y)),
        max(max(lt.x, rt.x), max(lb.x, rb.x)),
        max(max(lt.y, rt.y), max(lb.y, rb.y))
    )
}

/** 还原经过旋转的方形 */
fun RectF.restoredFromRotate(rotation: Float): RectF {
    if (rotation % 360 == 0F) return RectF(this)
    val lt = restoreFromRotatedPoint(rotation, left, top, centerX(), centerY())
    val rt = restoreFromRotatedPoint(rotation, right, top, centerX(), centerY())
    val lb = restoreFromRotatedPoint(rotation, left, bottom, centerX(), centerY())
    val rb = restoreFromRotatedPoint(rotation, right, bottom, centerX(), centerY())
    return RectF(
        min(min(lt.x, rt.x), min(lb.x, rb.x)),
        min(min(lt.y, rt.y), min(lb.y, rb.y)),
        max(max(lt.x, rt.x), max(lb.x, rb.x)),
        max(max(lt.y, rt.y), max(lb.y, rb.y))
    )
}

/** [diff] 允许的误差值 */
fun RectF.samePosition(rect: RectF, diff: Int = 0) =
    abs(left - rect.left) <= diff && abs(top - rect.top) <= diff

/** [diff] 允许的误差值 */
fun RectF.samePosition(left: Float, top: Float, diff: Int = 0) =
    abs(left - this.left) <= diff && abs(top - this.top) <= diff

/** [diff] 允许的误差值 */
fun RectF.sameSize(rect: RectF, diff: Int = 0) =
    abs(width() - rect.width()) <= diff && abs(height() - rect.height()) <= diff

/** [diff] 允许的误差值 */
fun RectF.sameSize(width: Float, height: Float, diff: Int = 0) =
    abs(width() - width) <= diff && abs(height() - height) <= diff

/** [diff] 允许的误差值 */
fun RectF.same(rect: Rect, diff: Int = 0) =
    abs(left - rect.left) <= diff
            && abs(top - rect.top) <= diff
            && abs(right - rect.right) <= diff
            && abs(bottom - rect.bottom) <= diff