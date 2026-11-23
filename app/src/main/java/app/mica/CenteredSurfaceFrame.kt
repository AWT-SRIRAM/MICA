package app.mica

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/**
 * Custom FrameLayout that mathematically centers every child surface
 * (surface_video, texture_video, surface_subtitles) on every single layout pass.
 *
 * This completely eliminates LibVLC's top-left (0, 0) placement bug caused by
 * LibVLC setting lp.gravity = -1 (NO_GRAVITY) without triggering layout recursion loops.
 */
class CenteredSurfaceFrame @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val parentW = right - left
        val parentH = bottom - top
        if (parentW <= 0 || parentH <= 0) {
            super.onLayout(changed, left, top, right, bottom)
            return
        }

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility != View.GONE) {
                val cw = child.measuredWidth
                val ch = child.measuredHeight
                if (cw > 0 && ch > 0) {
                    val cl = (parentW - cw) / 2
                    val ct = (parentH - ch) / 2
                    child.layout(cl, ct, cl + cw, ct + ch)
                } else {
                    child.layout(0, 0, parentW, parentH)
                }
            }
        }
    }
}
