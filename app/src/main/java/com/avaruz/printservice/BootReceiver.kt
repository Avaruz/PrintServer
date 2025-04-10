package com.avaruz.printservice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i("BootReceiver", "Sistema operativo iniciado, arrancando PrintService")
            val serviceIntent = Intent(context, PrintService::class.java)
            serviceIntent.action = "START"
            context.startForegroundService(serviceIntent)
        }
    }
}