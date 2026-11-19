package com.nexus.videoclient.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * Erzwingt ein festes 16:9-Seitenverhältnis schon VOR dem Laden des Thumbnails.
 * Dadurch springt oder klappt beim schnellen Scrollen kein einziges Video mehr auf!
 */
class AspectRatioFrameLayout16by9 @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = (width * 9) / 16
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
    }
}
