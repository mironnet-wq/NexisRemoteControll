package com.nexis.remoteagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class AutostartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        android.util.Log.i("NexisAutostart", "Receiver executado para ação: $action")
        if (action == Intent.ACTION_BOOT_COMPLETED || 
            action == Intent.ACTION_MY_PACKAGE_REPLACED || 
            action == "android.intent.action.QUICKBOOT_POWERON") {
            
            val serviceIntent = Intent(context, RemoteControlService::class.java)
            try {
                ContextCompat.startForegroundService(context, serviceIntent)
                android.util.Log.i("NexisAutostart", "RemoteControlService iniciado com sucesso em background.")
            } catch (e: Exception) {
                android.util.Log.e("NexisAutostart", "Falha ao iniciar RemoteControlService: ${e.message}")
            }
            if (KioskPolicy.mode(context) == "user") {
                val home = Intent(context, KioskActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(home) }
            }
        }
    }
}
