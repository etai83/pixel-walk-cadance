package com.example.walkingcadencepoc

import android.Manifest
import android.os.Bundle
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val manager = WalkingCadenceManager.getInstance(applicationContext)

        setContent {
            MaterialTheme {
                CadenceScreen(manager)
            }
        }
    }
}

@Composable
fun CadenceScreen(manager: WalkingCadenceManager) {
    val cadence by manager.cadenceSpm.collectAsState()
    val spmHistory by manager.spmHistory.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            manager.ensureAlwaysOnStarted()
        }
    }

    LaunchedEffect(Unit) {
        if (!manager.ensureAlwaysOnStarted()) {
            permissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
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
                .height(74.dp)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Button(
            modifier = Modifier
                .height(30.dp)
                .width(76.dp),
            onClick = { manager.resetHistory() }
        ) {
            Text(
                text = "Reset",
                fontSize = 11.sp
            )
        }
    }
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
