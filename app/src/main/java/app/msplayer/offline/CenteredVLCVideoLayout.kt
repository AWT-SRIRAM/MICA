package app.msplayer.offline

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Custom VLCVideoLayout that ensures the underlying surface frame
 * fills the full parent bounds without mutating layoutParams during measure.
 */
class CenteredVLCVideoLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : VLCVideoLayout(context, attrs, defStyleAttr) {

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val parentW = right - left
        val parentH = bottom - top
        if (parentW <= 0 || parentH <= 0) return

        val frame = findViewById<FrameLayout>(org.videolan.R.id.player_surface_frame) ?: return
        if (frame.left != 0 || frame.top != 0 || frame.right != parentW || frame.bottom != parentH) {
            frame.layout(0, 0, parentW, parentH)
        }
    }
}
