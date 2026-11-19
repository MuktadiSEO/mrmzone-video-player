package com.mrmzone.player.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/** FrameLayout that is always 16:9 (used for grid thumbnails). */
class RatioFrameLayout @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : FrameLayout(ctx, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = View.MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(w * 9 / 16, View.MeasureSpec.EXACTLY))
    }
}
