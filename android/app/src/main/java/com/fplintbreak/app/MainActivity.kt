package com.fplintbreak.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.fplintbreak.app.ui.BreakScreen
import com.fplintbreak.app.ui.theme.BreakTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BreakTheme {
                BreakScreen()
            }
        }
    }
}
