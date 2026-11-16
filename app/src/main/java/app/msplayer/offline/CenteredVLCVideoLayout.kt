package app.msplayer.offline

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Custom VLCVideoLayout that enforces mathematical center positioning of the video surface
 * on every layout and measure pass, completely overriding LibVLC's (0, 0) top-left bias.
 */
class CenteredVLCVideoLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : VLCVideoLayout(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val frame = findViewById<FrameLayout>(org.videolan.R.id.player_surface_frame)
        if (frame != null) {
            val flp = frame.layoutParams as? FrameLayout.LayoutParams
            if (flp != null && flp.gravity != Gravity.CENTER) {
                flp.gravity = Gravity.CENTER
                frame.layoutParams = flp
            }
            val surface = frame.findViewById<View>(org.videolan.R.id.surface_video)
                ?: frame.findViewById<View>(org.videolan.R.id.texture_video)
            if (surface != null) {
                val slp = surface.layoutParams as? FrameLayout.LayoutParams
                if (slp != null && slp.gravity != Gravity.CENTER) {
                    slp.gravity = Gravity.CENTER
                    surface.layoutParams = slp
                }
            }
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val parentW = right - left
        val parentH = bottom - top
        if (parentW <= 0 || parentH <= 0) return

        val frame = findViewById<FrameLayout>(org.videolan.R.id.player_surface_frame) ?: return
        // Force the surface frame to fill the full bounds
        frame.layout(0, 0, parentW, parentH)

        val surface = frame.findViewById<View>(org.videolan.R.id.surface_video)
            ?: frame.findViewById<View>(org.videolan.R.id.texture_video)
        if (surface != null && surface.visibility != View.GONE) {
            val sw = surface.measuredWidth
            val sh = surface.measuredHeight
            if (sw > 0 && sh > 0) {
                val sl = (parentW - sw) / 2
                val st = (parentH - sh) / 2
                surface.layout(sl, st, sl + sw, st + sh)
            }
        }
    }
}
