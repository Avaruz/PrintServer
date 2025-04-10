package com.avaruz.printservice

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.avaruz.printservice.ui.theme.PrintserviceTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PrintserviceTheme {
                PrintServiceScreen(onStartService = { startPrintService() })
            }
        }
    }

    private fun startPrintService() {
        val serviceIntent = Intent(this, PrintService::class.java)
        serviceIntent.action = "START"
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        val serviceIntent = Intent(this, PrintService::class.java)
        serviceIntent.action = "STOP"
        ContextCompat.startForegroundService(this, serviceIntent)
    }
}

@Composable
fun PrintServiceScreen(onStartService: () -> Unit) {
    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(text = "Print Service Control")
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onStartService) {
                Text(text = "Start Print Service")
            }
            // You can add more UI elements here to show the service status etc.
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PrintServiceScreenPreview() {
    PrintserviceTheme {
        PrintServiceScreen(onStartService = {})
    }
}