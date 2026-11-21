package app.msplayer.offline

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class VersionLockState(
    val isLocked: Boolean = false,
    val minRequiredVersion: String = "",
    val latestVersion: String = "",
    val downloadUrl: String = "https://github.com/AWT-SRIRAM/MICA/releases/latest",
    val message: String = "This build has expired or is deprecated. Please update to continue enjoying lossless music and video playback.",
)

object VersionLockManager {
    // Current release cutoff: minimum required is 0.7.6 (versionCode 23)
    const val MIN_SUPPORTED_VERSION_CODE = 23
    const val MIN_SUPPORTED_VERSION_NAME = "0.7.6"

    suspend fun checkVersionLock(context: Context): VersionLockState = withContext(Dispatchers.IO) {
        // Local baseline validation: if this APK itself were below 23, lock.
        val currentCode = BuildConfig.VERSION_CODE
        if (currentCode < MIN_SUPPORTED_VERSION_CODE) {
            return@withContext VersionLockState(
                isLocked = true,
                minRequiredVersion = MIN_SUPPORTED_VERSION_NAME,
                latestVersion = MIN_SUPPORTED_VERSION_NAME,
            )
        }

        // Also query remote release: if remote release body contains [LOCK_BELOW:x.x.x] or [DEPRECATED]
        val updateInfo = UpdateChecker.check(BuildConfig.VERSION_NAME)
        if (updateInfo != null && updateInfo.isAvailable) {
            val changelog = updateInfo.changelog
            if (changelog.contains("[FORCE_UPDATE]") || changelog.contains("[DEPRECATE_OLD]")) {
                return@withContext VersionLockState(
                    isLocked = true,
                    minRequiredVersion = updateInfo.latestVersion,
                    latestVersion = updateInfo.latestVersion,
                    downloadUrl = updateInfo.downloadUrl,
                    message = "A critical update (v${updateInfo.latestVersion}) is required to continue using MS PLAYER.",
                )
            }
        }

        VersionLockState(isLocked = false)
    }
}

@Composable
fun VersionLockScreen(
    lockState: VersionLockState,
    onExitApp: () -> Unit,
) {
    val context = LocalContext.current

    // Block back press: prevent dismissal
    BackHandler {
        onExitApp()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090B10))
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF141721))
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Icon
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(Color(0x22EF4444)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.SystemUpdate,
                    contentDescription = null,
                    tint = Color(0xFFEF4444),
                    modifier = Modifier.size(42.dp),
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Update Required",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = lockState.message,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 14.sp,
                lineHeight = 21.sp,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Minimum Required: v${lockState.minRequiredVersion.ifBlank { "0.7.6" }}",
                color = Color(0xFFEF4444),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )

            Spacer(modifier = Modifier.height(28.dp))

            // Action Button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFF6366F1), Color(0xFF8B5CF6))
                        )
                    )
                    .clickable {
                        runCatching {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(lockState.downloadUrl)).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Download Latest Update",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Exit App",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clickable { onExitApp() }
                    .padding(8.dp)
            )
        }
    }
}
