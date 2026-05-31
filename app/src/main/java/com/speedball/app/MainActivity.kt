package com.speedball.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.speedball.app.ui.SpeedBallApp

/** Launches the Phase 1 Compose shell without camera, import, or measurement work. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SpeedBallApp()
        }
    }
}
