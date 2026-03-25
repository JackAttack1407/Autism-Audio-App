package com.example.autismaudioapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class AudioLevelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint().apply { color = Color.GREEN }
    private var levels: FloatArray = FloatArray(16) { 0f } // default 16 bars


    fun setLevels(newLevels: FloatArray) {
        levels = newLevels.copyOf()
        invalidate() // redraw
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val barWidth = width / levels.size.toFloat()
        val heightF = height.toFloat()

        for (i in levels.indices) {
            val barHeight = heightF * levels[i].coerceIn(0f, 1f)
            canvas.drawRect(
                i * barWidth,
                heightF - barHeight,
                (i + 1) * barWidth - 2f, // spacing between bars
                heightF,
                paint
            )
        }
    }
}