package com.nexis.remoteagent

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.BatteryManager

/** Leituras locais para a tela "Informações do coletor" (não dependem do serviço). */
object DeviceInfo {

    data class Wifi(
        val connected: Boolean,
        val ssid: String,
        val bssid: String,
        val rssi: Int,
        val linkMbps: Int,
        val frequencyMhz: Int,
        val ip: String,
        val gateway: String
    ) {
        fun bandLabel(): String {
            if (frequencyMhz <= 0) return "—"
            val band = if (frequencyMhz >= 4900) "5 GHz" else "2,4 GHz"
            val channel = when {
                frequencyMhz == 2484 -> 14
                frequencyMhz in 2412..2472 -> (frequencyMhz - 2407) / 5
                frequencyMhz in 5000..5900 -> (frequencyMhz - 5000) / 5
                else -> 0
            }
            return if (channel > 0) "$band · canal $channel" else band
        }
    }

    data class Quality(val label: String, val percent: Int, val color: Int)

    fun wifi(context: Context): Wifi {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val info = wm.connectionInfo
            val ipRaw = info?.ipAddress ?: 0
            val connected = info != null && info.networkId != -1 && ipRaw != 0
            @Suppress("DEPRECATION")
            val gw = wm.dhcpInfo?.gateway ?: 0
            val ssid = info?.ssid.orEmpty().trim('"').let { if (it == "<unknown ssid>") "" else it }
            val bssid = info?.bssid.orEmpty().uppercase().let { if (it == "02:00:00:00:00:00") "" else it }
            Wifi(
                connected = connected,
                ssid = ssid,
                bssid = bssid,
                rssi = info?.rssi ?: -127,
                linkMbps = info?.linkSpeed ?: 0,
                frequencyMhz = info?.frequency ?: 0,
                ip = ipv4(ipRaw),
                gateway = ipv4(gw)
            )
        } catch (e: Exception) {
            Wifi(false, "", "", -127, 0, 0, "", "")
        }
    }

    /** Faixas usuais para coletor em armazém: acima de -60 é ótimo, abaixo de -80 cai. */
    fun quality(rssi: Int): Quality {
        val percent = ((rssi + 100) * 2).coerceIn(0, 100)
        return when {
            rssi >= -55 -> Quality("Excelente", percent, Color.parseColor("#4ADE80"))
            rssi >= -67 -> Quality("Bom", percent, Color.parseColor("#A3E635"))
            rssi >= -75 -> Quality("Regular", percent, Color.parseColor("#FBBF24"))
            else -> Quality("Fraco", percent, Color.parseColor("#F87171"))
        }
    }

    fun battery(context: Context): String {
        return try {
            val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = status?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = status?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val plugged = (status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            if (level < 0) return "—"
            val pct = if (scale > 0) level * 100 / scale else level
            if (plugged) "$pct% · carregando" else "$pct%"
        } catch (e: Exception) {
            "—"
        }
    }

    private fun ipv4(raw: Int): String {
        if (raw == 0) return ""
        return "${raw and 0xff}.${raw shr 8 and 0xff}.${raw shr 16 and 0xff}.${raw shr 24 and 0xff}"
    }
}
