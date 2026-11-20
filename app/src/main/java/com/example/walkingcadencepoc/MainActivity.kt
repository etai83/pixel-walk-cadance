package com.example.walkingcadencepoc

import android.Manifest
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val manager = WalkingCadenceManager(applicationContext)

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
    val isTracking by manager.isTracking.collectAsState()
    val scope = rememberCoroutineScope()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            scope.launch { manager.startWalkingSession() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = cadence?.let { "${it.toInt()} SPM" } ?: "-- SPM",
            fontSize = 32.sp
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = {
                if (isTracking) {
                    scope.launch { manager.stopWalkingSession() }
                } else {
                    permissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                }
            }
        ) {
            Text(if (isTracking) "Stop" else "Start")
        }
    }
}
