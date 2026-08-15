package com.hj.dc1

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.FrameLayout

/**
 * 自绘开关控件：52x32dp 圆角轨道 + 白色圆形拇指
 *
 * 解决 MaterialSwitch 轨道在视图内不居中导致的位置/触摸错位问题：
 * - 可见轨道严格居中于控件，控件宽度 = 轨道宽度，布局位置即视觉位置
 * - 动画切换拇指位置
 * - 整个控件可点击（触摸区 = 视觉区）
 */
class DcSwitch(context: Context) : FrameLayout(context) {

    private val trackColorOn = 0xFF6750A4.toInt()   // 与 M3 主色一致（紫色）
    private val trackColorOff = 0xFF9E9E9E.toInt()  // 灰色
    private val trackSize = dp(52)
    private val trackHeight = dp(32)
    private val thumbSize = dp(18)
    private val thumbMargin = dp(3)                  // 拇指距轨道边缘

    private val track = View(context).apply {
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(trackColorOff)
        }
    }

    private val thumb = View(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFFFFFFFF.toInt())
        }
    }

    private var animator: ValueAnimator? = null

    /** 开关状态；程序赋值时带动画切换（不会触发点击监听） */
    var isChecked: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            applyState(true)
        }

    init {
        isClickable = true
        isFocusable = true

        addView(track, LayoutParams(trackSize, trackHeight, android.view.Gravity.CENTER))
        addView(thumb, LayoutParams(thumbSize, thumbSize, android.view.Gravity.CENTER_VERTICAL))
        applyState(false)
    }

    private fun applyState(animate: Boolean) {
        (track.background as GradientDrawable).setColor(if (isChecked) trackColorOn else trackColorOff)
        val target = if (isChecked) {
            (trackSize - thumbSize - thumbMargin).toFloat()
        } else {
            thumbMargin.toFloat()
        }
        animator?.cancel()
        if (animate) {
            animator = ValueAnimator.ofFloat(thumb.translationX, target).apply {
                duration = 180
                addUpdateListener {
                    thumb.translationX = it.animatedValue as Float
                }
                start()
            }
        } else {
            thumb.translationX = target
        }
    }

    private fun dp(v: Int): Int = (resources.displayMetrics.density * v).toInt()
}
