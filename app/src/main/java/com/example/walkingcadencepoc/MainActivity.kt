package com.example.walkingcadencepoc

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text

class MainActivity : ComponentActivity() {
    private lateinit var manager: WalkingCadenceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        manager = WalkingCadenceManager.getInstance(applicationContext)

        setContent {
            MaterialTheme {
                CadenceScreen(manager)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        manager.setForegroundUiActive(true)
        if (manager.hasPermission()) {
            manager.ensureAlwaysOnStarted()
        } else {
            manager.releaseIfUnauthorized()
        }
    }

    override fun onStop() {
        manager.setForegroundUiActive(false)
        super.onStop()
    }
}

@Composable
fun CadenceScreen(manager: WalkingCadenceManager) {
    val context = LocalContext.current
    val cadence by manager.cadenceSpm.collectAsState()
    val spmHistory by manager.spmHistory.collectAsState()
    val status by manager.statusMessage.collectAsState()
    var askedForPermission by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        askedForPermission = true
        manager.ensureAlwaysOnStarted()
    }

    fun requestPermissionsOrSettings() {
        val missing = manager.permissionsToRequest()
        val activity = context as? Activity
        val activityRecognitionDenied = Manifest.permission.ACTIVITY_RECOGNITION in missing
        val permanentlyDenied = askedForPermission &&
            activityRecognitionDenied &&
            activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.ACTIVITY_RECOGNITION
            )
        if (permanentlyDenied) {
            openAppPermissionSettings(context)
        } else if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            manager.ensureAlwaysOnStarted()
        }
    }

    LaunchedEffect(Unit) {
        val missing = manager.permissionsToRequest()
        if (missing.isEmpty()) {
            manager.ensureAlwaysOnStarted()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "${cadence?.toInt() ?: 0} SPM",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        CadenceBarGraph(
            history = spmHistory,
            modifier = Modifier
                .fillMaxWidth()
                .height(if (status != null) 52.dp else 74.dp)
        )
        if (status != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = statusLabel(status),
                fontSize = 11.sp,
                color = Color(0xFFFFCC80),
                textAlign = TextAlign.Center,
                lineHeight = 13.sp
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        val action = actionFor(status)
        Button(
            modifier = Modifier
                .height(30.dp)
                .width(if (action == CadenceAction.RESET) 76.dp else 96.dp),
            onClick = {
                when (action) {
                    CadenceAction.ALLOW -> requestPermissionsOrSettings()
                    CadenceAction.RETRY -> manager.ensureAlwaysOnStarted()
                    CadenceAction.RESET -> manager.resetHistory()
                }
            }
        ) {
            Text(
                text = stringResource(action.labelRes),
                fontSize = 11.sp
            )
        }
    }
}

private enum class CadenceAction(val labelRes: Int) {
    ALLOW(R.string.action_allow),
    RETRY(R.string.action_retry),
    RESET(R.string.action_reset)
}

private fun actionFor(status: CadenceStatus?): CadenceAction {
    return when (status) {
        CadenceStatus.NEED_ACTIVITY_RECOGNITION -> CadenceAction.ALLOW
        CadenceStatus.SENSOR_ACCESS_DENIED,
        CadenceStatus.MONITOR_START_FAILED -> CadenceAction.RETRY
        CadenceStatus.NO_STEP_SENSOR, null -> CadenceAction.RESET
    }
}

@Composable
private fun statusLabel(status: CadenceStatus?): String {
    return when (status) {
        CadenceStatus.NEED_ACTIVITY_RECOGNITION ->
            stringResource(R.string.status_need_activity_recognition)
        CadenceStatus.NO_STEP_SENSOR -> stringResource(R.string.status_no_step_sensor)
        CadenceStatus.SENSOR_ACCESS_DENIED -> stringResource(R.string.status_sensor_access_denied)
        CadenceStatus.MONITOR_START_FAILED -> stringResource(R.string.status_monitor_start_failed)
        null -> ""
    }
}

private fun openAppPermissionSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
    }
    context.startActivity(intent)
}

@Composable
fun CadenceBarGraph(
    history: List<Int>,
    modifier: Modifier = Modifier
) {
    val maxScale = maxOf(180, history.maxOrNull() ?: 180)
    val minScale = 0

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        for (index in 0 until MAX_BARS) {
            val value = history.getOrNull(index)
            val isLatest = value != null && index == history.lastIndex

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                if (value != null) {
                    val fraction = ((value - minScale).coerceAtLeast(10).toFloat() /
                        (maxScale - minScale).coerceAtLeast(1)).coerceIn(0.12f, 1f)
                    val barHeight = 52.dp * fraction

                    Text(
                        text = value.toString(),
                        fontSize = 8.sp,
                        color = if (isLatest) Color(0xFF00E5FF) else Color(0xFFB0BEC5),
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .height(barHeight)
                            .background(
                                color = if (isLatest) Color(0xFF00E5FF) else MaterialTheme.colors.primary,
                                shape = RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)
                            )
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .height(3.dp)
                            .background(
                                color = Color.DarkGray.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(1.5.dp)
                            )
                    )
                }
            }
        }
    }
}
