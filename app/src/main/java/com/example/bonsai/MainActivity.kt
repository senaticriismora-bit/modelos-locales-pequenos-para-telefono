package com.example.bonsai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.bonsai.ui.ChatRoute
import com.example.bonsai.ui.theme.BonsaiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BonsaiTheme {
                ChatRoute()
            }
        }
    }
}
