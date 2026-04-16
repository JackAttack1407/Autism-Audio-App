package com.example.autismaudioapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class AudioLevelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var levels: FloatArray = FloatArray(16)
    private var smoothed: FloatArray = FloatArray(16)

    // smoothing factor (lower = smoother but slower response)
    private val smoothing = 0.2f

    fun setLevels(newLevels: FloatArray) {

        val size = minOf(newLevels.size, smoothed.size)

        // shift LEFT (older data moves left)
        for (i in 0 until size - 1) {
            smoothed[i] = smoothed[i + 1]
        }

        // insert NEW data on RIGHT side
        smoothed[size - 1] = newLevels[0]

        // smooth entire buffer
        for (i in 0 until size) {
            levels[i] += (smoothed[i] - levels[i]) * smoothing
        }

        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val barWidth = width / levels.size.toFloat()
        val heightF = height.toFloat()

        for (i in levels.indices) {

            val value = levels[i].coerceIn(0f, 1f)
            val barHeight = heightF * value

            // 🔥 Color transition: green → yellow → red
            val color = getColorForLevel(value)
            paint.color = color

            canvas.drawRect(
                i * barWidth,
                heightF - barHeight,
                (i + 1) * barWidth - 2f,
                heightF,
                paint
            )
        }
    }

    private fun getColorForLevel(v: Float): Int {
        return when {
            v < 0.5f -> {
                // green → yellow
                val t = v / 0.5f
                blendColor(Color.GREEN, Color.YELLOW, t)
            }
            else -> {
                // yellow → red
                val t = (v - 0.5f) / 0.5f
                blendColor(Color.YELLOW, Color.RED, t)
            }
        }
    }

    private fun blendColor(c1: Int, c2: Int, t: Float): Int {
        val r = (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * t).toInt()
        val g = (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * t).toInt()
        val b = (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * t).toInt()

        return Color.rgb(
            max(0, r),
            max(0, g),
            max(0, b)
        )
    }
}