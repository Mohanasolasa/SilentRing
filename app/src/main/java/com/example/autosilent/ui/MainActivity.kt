package com.example.autosilent.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.example.autosilent.AutoSilentApplication
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import com.example.autosilent.ui.theme.AutoSilentTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as? AutoSilentApplication
        val canExact = app?.alarmScheduler?.canScheduleExactAlarms() ?: false
        com.example.autosilent.scheduler.DiagnosticLogger.i(
            "AutoSilentDiag",
            "MAIN_ACTIVITY_LAUNCHED: canScheduleExactAlarms=$canExact"
        )
        setContent {
            AutoSilentTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Text(text = "Auto Silent")
                }
            }
        }
    }
}
