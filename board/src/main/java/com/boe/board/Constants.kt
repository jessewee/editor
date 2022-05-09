package com.boe.board

/** 画板最大缩放倍数。实际效果是画布变小，内容变大 */
const val BOARD_MAX_SCALE = 2F

/** 画板最小缩放倍数。实际效果是画布变大，内容变小 */
const val BOARD_MIN_SCALE = 0.5F

/** 组件最小尺寸 */
const val WIDGET_MIN_SIZE = 50

/** 最小缩放差值，防止频繁刷新 */
const val SIZE_MIN_DIFF = 0 // 50

/** 最小旋转角度，防止频繁刷新 */
const val ROTATE_MIN_DEGREE = 0 // 5

/** 最小移动距离，防止频繁刷新 */
const val MOVE_MIN_SPACE = 0 // 50


/** 操作状态。开始、操作、结束三个状态，一般开始时记录当时状态，结束时记录新状态，用来添加操作步骤记录 */
enum class OperateStatus { START, OPERATING, END }

/** 缩放时的锚点或者方向 */
enum class Anchor { TOP, BOTTOM, LEFT, RIGHT, LEFT_TOP, RIGHT_TOP, LEFT_BOTTOM, RIGHT_BOTTOM }