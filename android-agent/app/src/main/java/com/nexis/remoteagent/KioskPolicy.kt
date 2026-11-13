package com.nexis.remoteagent

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Modo usuário trava a tela inicial nos aplicativos escolhidos.
 * Modo admin devolve Wi-Fi e as configurações de hoje. A senha fica só como hash.
 */
object KioskPolicy {

    private const val TAG = "NexisKiosk"
    const val ACTION_CHANGED = "com.nexis.remoteagent.KIOSK_CHANGED"
    private const val PREFS = "nexis_kiosk"
    private const val ITERATIONS = 20000
    private const val DEFAULT_SALT = "4e657869734b696f736b32303236"
    private const val DEFAULT_HASH = "eee0aab506e00eba955c437ae853c669e6521281770a691c8d972112699cabfc"

    private val extraRestrictions = listOf(
        UserManager.DISALLOW_CONFIG_WIFI,
        UserManager.DISALLOW_CONFIG_BLUETOOTH,
        UserManager.DISALLOW_NETWORK_RESET,
        UserManager.DISALLOW_DEBUGGING_FEATURES,
        UserManager.DISALLOW_FACTORY_RESET,
        UserManager.DISALLOW_SAFE_BOOT,
        UserManager.DISALLOW_ADD_USER,
        UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
        UserManager.DISALLOW_UNINSTALL_APPS,
        UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA
    )

    fun mode(context: Context): String = prefs(context).getString("mode", "off") ?: "off"

    fun packages(context: Context): List<String> {
        val raw = prefs(context).getString("packages", "[]") ?: "[]"
        return parsePackages(raw)
    }

    fun hasPassword(context: Context): Boolean = true

    fun passwordOk(context: Context, password: String): Boolean {
        val pair = storedOrDefault(context)
        return try {
            val digest = pbkdf2(password, hexToBytes(pair.first)).toHex()
            MessageDigest.isEqual(digest.toByteArray(), pair.second.toByteArray())
        } catch (e: Exception) {
            false
        }
    }

    fun savePassword(context: Context, password: String): Boolean {
        val secret = password.trim()
        if (secret.length < 4 || secret.length > 64) return false
        val salt = ByteArray(16)
        java.security.SecureRandom().nextBytes(salt)
        val hash = pbkdf2(secret, salt).toHex()
        prefs(context).edit()
            .putString("pass_hash", hash)
            .putString("pass_salt", salt.toHex())
            .commit()
        return true
    }

    fun applyCommand(context: Context, data: JSONObject) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            // Antes o comando era gravado e ignorado em silêncio: o Gestor mostrava
            // "Modo usuário" e o coletor continuava livre. Agora avisa e não finge.
            Log.w(TAG, "set_kiosk recusado: o app não é Device Owner.")
            RemoteControlService.instance?.kioskLog(
                "Quiosque NÃO aplicado: o agente não é administrador (Device Owner) deste coletor. " +
                    "Reprovisione pelo QR ou pelo cabo (dpm set-device-owner)."
            )
            report(context)
            return
        }
        val editor = prefs(context).edit()
        val hash = data.optString("password_hash")
        val salt = data.optString("password_salt")
        if (hash.isNotEmpty() && salt.isNotEmpty()) {
            editor.putString("pass_hash", hash)
            editor.putString("pass_salt", salt)
        }
        val mode = when (data.optString("mode")) {
            "user" -> "user"
            "admin" -> "admin"
            else -> "off"
        }
        val packages = mutableListOf<String>()
        val arr = data.optJSONArray("packages") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val name = arr.optString(i).trim()
            if (name.isNotEmpty() && name != context.packageName && !packages.contains(name)) {
                packages.add(name)
            }
        }
        editor.putString("mode", mode)
        editor.putString("packages", JSONArray(packages).toString())
        editor.commit()
        apply(context, openScreen = true)
        report(context)
        val label = when (mode) {
            "user" -> "usuário"
            "admin" -> "admin"
            else -> "desligado"
        }
        RemoteControlService.instance?.notifyKiosk(label)
    }

    fun applySaved(context: Context) {
        apply(context, openScreen = mode(context) == "user")
        if (inventory(context)) RemoteControlService.instance?.onInventoryChanged()
    }

    /**
     * No reinício (e quando o serviço volta, ex.: depois de atualizar o app) o quiosque
     * volta, mesmo se o coletor ficou em modo admin. "Desligado" é decisão do Gestor:
     * fica desligado — antes virava modo usuário só por ter lista de apps salva.
     */
    fun engageUserOnBoot(context: Context) {
        val current = mode(context)
        if (current == "off") {
            applySaved(context)
            return
        }
        if (current != "user") {
            prefs(context).edit().putString("mode", "user").commit()
        }
        apply(context, openScreen = true)
        report(context)
    }

    fun setLocal(context: Context, mode: String, packages: List<String>) {
        val clean = packages.filter { it.isNotBlank() && it != context.packageName }.distinct()
        prefs(context).edit()
            .putString("mode", if (mode == "user" || mode == "admin") mode else "off")
            .putString("packages", JSONArray(clean).toString())
            .commit()
        apply(context, openScreen = true)
        report(context)
    }

    fun apply(context: Context, openScreen: Boolean) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, DeviceAdminReceiver::class.java)
        val owner = dpm.isDeviceOwnerApp(context.packageName)
        val current = mode(context)
        if (!owner) {
            Log.w(TAG, "Modo do coletor ignorado: o app não é Device Owner.")
            return
        }
        val locked = current == "user"
        if (!locked) {
            EnterpriseConnectivityPolicy.apply(context)
            try {
                dpm.clearUserRestriction(admin, UserManager.DISALLOW_CHANGE_WIFI_STATE)
            } catch (e: Exception) {
                Log.w(TAG, "Wi-Fi admin: ${e.message}")
            }
        } else {
            try {
                dpm.addUserRestriction(admin, UserManager.DISALLOW_CHANGE_WIFI_STATE)
            } catch (e: Exception) {
                Log.w(TAG, "Wi-Fi usuário: ${e.message}")
            }
            holdRadios(context)
        }
        for (restriction in extraRestrictions) {
            try {
                if (locked) dpm.addUserRestriction(admin, restriction)
                else dpm.clearUserRestriction(admin, restriction)
            } catch (e: Exception) {
                Log.w(TAG, "Restriction $restriction: ${e.message}")
            }
        }
        try {
            dpm.setStatusBarDisabled(admin, locked)
        } catch (e: Exception) {
            Log.w(TAG, "Status bar: ${e.message}")
        }
        try {
            dpm.setApplicationHidden(admin, "com.android.settings", locked)
        } catch (e: Exception) {
            Log.w(TAG, "Configurações: ${e.message}")
        }
        try {
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS)
        } catch (e: Exception) {
            Log.w(TAG, "Rede móvel: ${e.message}")
        }
        try {
            // O MC33 tem teclado físico. Sem isto o Android não abre o teclado no campo.
            dpm.setSecureSetting(admin, "show_ime_with_hard_keyboard", "1")
        } catch (e: Exception) {
            Log.w(TAG, "Teclado virtual: ${e.message}")
        }
        val home = ComponentName(context.packageName, context.packageName + ".KioskHome")
        try {
            dpm.clearPackagePersistentPreferredActivities(admin, context.packageName)
            if (locked) {
                context.packageManager.setComponentEnabledSetting(
                    home,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                val filter = IntentFilter(Intent.ACTION_MAIN)
                filter.addCategory(Intent.CATEGORY_HOME)
                filter.addCategory(Intent.CATEGORY_DEFAULT)
                dpm.addPersistentPreferredActivity(admin, filter, home)
            } else {
                context.packageManager.setComponentEnabledSetting(
                    home,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Tela inicial: ${e.message}")
        }
        context.sendBroadcast(Intent(ACTION_CHANGED).setPackage(context.packageName))
        if (inventory(context)) applyInventory(context)
        if (openScreen && current != "off") {
            val intent = Intent(context, KioskActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            context.startActivity(intent)
        }
        RemoteControlService.instance?.syncAdminTimer()
    }

    /** No modo usuário o Wi-Fi e a localização ficam ligados. O usuário não desliga os dois. */
    fun holdRadios(context: Context) {
        if (mode(context) != "user") return
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, DeviceAdminReceiver::class.java)
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            if (!wifi.isWifiEnabled) wifi.setWifiEnabled(true)
            dpm.setGlobalSetting(admin, Settings.Global.WIFI_ON, "1")
        } catch (e: Exception) {
            Log.w(TAG, "Wi-Fi: ${e.message}")
        }
        try {
            @Suppress("DEPRECATION")
            dpm.setSecureSetting(admin, Settings.Secure.LOCATION_MODE, "3")
        } catch (e: Exception) {
            Log.w(TAG, "Localização: ${e.message}")
        }
    }

    // ---------- Guarda do modo usuário ----------
    // O quiosque é a tela inicial do Android, não um lock task: o botão de recentes, um app
    // liberado que abre outro, ou o launcher do sistema no boot ainda levavam para fora.
    // O serviço de acessibilidade avisa cada troca de janela e aqui decidimos se volta.

    private var guardLastMs = 0L
    private var guardCacheMs = 0L
    private var homePackages: Set<String> = emptySet()
    private var imePackages: Set<String> = emptySet()
    private val launchable = HashMap<String, Boolean>()
    private val guardLogged = HashMap<String, Long>()

    /** Pacotes que nunca são "fuga": janelas do sistema, teclado, diálogos de permissão. */
    private val systemOk = setOf(
        "android",
        "com.android.systemui",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.symbol.datawedge"
    )

    fun guardForeground(context: Context, pkg: String) {
        if (pkg.isEmpty() || pkg == context.packageName || pkg in systemOk) return
        if (mode(context) != "user") return
        if (pkg in packages(context)) return
        refreshGuardCache(context)
        if (pkg in imePackages) return
        val escape = pkg == "com.android.settings" || pkg in homePackages || isLaunchable(context, pkg)
        if (!escape) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - guardLastMs < 600) return
        guardLastMs = now
        Log.i(TAG, "Quiosque: $pkg fora da lista. Voltando para a tela do coletor.")
        val last = guardLogged[pkg] ?: 0L
        if (now - last > 60_000) {
            guardLogged[pkg] = now
            RemoteControlService.instance?.kioskLog("Quiosque bloqueou $pkg e voltou para a tela do coletor.")
        }
        try {
            context.startActivity(
                Intent(context, KioskActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Guarda do quiosque: ${e.message}")
        }
    }

    private fun refreshGuardCache(context: Context) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - guardCacheMs < 60_000 && guardCacheMs != 0L) return
        guardCacheMs = now
        launchable.clear()
        val pm = context.packageManager
        homePackages = try {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            pm.queryIntentActivities(home, PackageManager.MATCH_ALL)
                .mapNotNull { it.activityInfo?.packageName }
                .filter { it != context.packageName }
                .toSet()
        } catch (e: Exception) {
            emptySet()
        }
        imePackages = try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.enabledInputMethodList.map { it.packageName }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    private fun isLaunchable(context: Context, pkg: String): Boolean {
        return launchable.getOrPut(pkg) {
            try {
                context.packageManager.getLaunchIntentForPackage(pkg) != null
            } catch (e: Exception) {
                false
            }
        }
    }

    fun inventory(context: Context): Boolean = prefs(context).getBoolean("inventory", false)

    /** Inventário: tela ligada por 1 hora, Wi-Fi sem dormir e Velocity fora da economia. */
    fun setInventory(context: Context, enabled: Boolean) {
        val editor = prefs(context).edit()
        if (enabled && !prefs(context).contains("screen_prev")) {
            val current = try {
                Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT)
            } catch (e: Exception) {
                30000
            }
            editor.putInt("screen_prev", current)
        }
        editor.putBoolean("inventory", enabled).commit()
        applyInventory(context)
        report(context)
        RemoteControlService.instance?.notifyKiosk(if (enabled) "inventário ligado" else "inventário desligado")
        RemoteControlService.instance?.onInventoryChanged()
    }

    fun applyInventory(context: Context) {
        val on = inventory(context)
        val stored = prefs(context)
        if (!on && !stored.contains("screen_prev")) return
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, DeviceAdminReceiver::class.java)
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        val targets = velocityPackages(context)
        if (on) {
            writeScreenTimeout(context, 60 * 60 * 1000)
            try {
                dpm.setGlobalSetting(admin, "wifi_sleep_policy", "2")
                dpm.setGlobalSetting(admin, "low_power", "0")
                dpm.setGlobalSetting(admin, Settings.Global.WIFI_ON, "1")
            } catch (e: Exception) {
                Log.w(TAG, "Inventário: ${e.message}")
            }
            try {
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                if (!wifi.isWifiEnabled) wifi.setWifiEnabled(true)
            } catch (e: Exception) {
                Log.w(TAG, "Wi-Fi inventário: ${e.message}")
            }
            targets.forEach {
                setPowerWhitelist(it, true)
                keepAppActive(it)
            }
        } else {
            writeScreenTimeout(context, stored.getInt("screen_prev", 30000))
            stored.edit().remove("screen_prev").commit()
            try {
                dpm.setGlobalSetting(admin, "wifi_sleep_policy", "0")
            } catch (e: Exception) {
                Log.w(TAG, "Wi-Fi sono: ${e.message}")
            }
            targets.forEach { setPowerWhitelist(it, false) }
        }
    }

    private fun velocityPackages(context: Context): List<String> {
        val found = launchers(context).map { it.packageName }.filter {
            val name = it.lowercase()
            name.contains("velocity") || name.contains("wavelink") || name.contains("ivanti")
        }
        return (found + "com.wavelink.velocity").distinct()
    }

    private fun writeScreenTimeout(context: Context, ms: Int) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, DeviceAdminReceiver::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && dpm.isDeviceOwnerApp(context.packageName)) {
            try {
                dpm.setSystemSetting(admin, Settings.System.SCREEN_OFF_TIMEOUT, ms.toString())
            } catch (e: Exception) {
                Log.w(TAG, "setSystemSetting tela: ${e.message}")
            }
        }
        try {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, ms)
        } catch (e: Exception) {
            Log.w(TAG, "Tempo de tela: ${e.message}")
        }
        val read = try {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT)
        } catch (e: Exception) {
            -1
        }
        if (read == ms) Log.i(TAG, "Tempo de tela em ${ms / 60000} min.")
        else Log.w(TAG, "Tempo de tela ficou $read, pedido $ms.")
    }

    private fun setPowerWhitelist(packageName: String, add: Boolean) {
        try {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val binder = serviceManager.getMethod("getService", String::class.java)
                .invoke(null, "deviceidle") as? android.os.IBinder ?: return
            val stub = Class.forName("android.os.IDeviceIdleController" + '$' + "Stub")
            val idle = stub.getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder) ?: return
            val method = if (add) "addPowerSaveWhitelistApp" else "removePowerSaveWhitelistApp"
            idle.javaClass.getMethod(method, String::class.java).invoke(idle, packageName)
        } catch (e: Exception) {
            Log.w(TAG, "Economia de bateria ($packageName): ${e.message}")
        }
    }

    private fun keepAppActive(packageName: String) {
        try {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val binder = serviceManager.getMethod("getService", String::class.java)
                .invoke(null, "usagestats") as? android.os.IBinder ?: return
            val stub = Class.forName("android.app.usage.IUsageStatsManager" + '$' + "Stub")
            val usage = stub.getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder) ?: return
            val method = usage.javaClass.methods.firstOrNull {
                it.name == "setAppInactive" && it.parameterTypes.size == 3
            } ?: return
            method.invoke(usage, packageName, false, 0)
        } catch (e: Exception) {
            Log.w(TAG, "Inatividade ($packageName): ${e.message}")
        }
    }

    fun report(context: Context) {
        val payload = JSONObject()
        payload.put("type", "kiosk_state")
        payload.put("mode", mode(context))
        payload.put("packages", JSONArray(packages(context)))
        payload.put("inventory", inventory(context))
        payload.put("device_owner", (context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager)
            .isDeviceOwnerApp(context.packageName))
        RemoteControlService.instance?.sendJson(payload.toString())
    }

    fun sendInstalledApps(context: Context) {
        val apps = launchers(context)
        val arr = JSONArray()
        for (app in apps) {
            arr.put(JSONObject().put("package", app.packageName).put("label", app.label))
        }
        val payload = JSONObject()
        payload.put("type", "installed_apps")
        payload.put("apps", arr)
        RemoteControlService.instance?.sendJson(payload.toString())
    }

    data class AppEntry(val packageName: String, val label: String)

    fun launchers(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val skip = setOf(context.packageName, "com.android.settings", "com.android.systemui")
        val out = linkedMapOf<String, String>()
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val found = pm.queryIntentActivities(main, PackageManager.MATCH_ALL)
        for (info in found) {
            val name = info.activityInfo?.packageName ?: continue
            if (name in skip || out.containsKey(name)) continue
            val label = info.loadLabel(pm)?.toString()?.trim().orEmpty()
            out[name] = if (label.isEmpty()) name else label
        }
        val installed = pm.getInstalledApplications(0)
        for (app in installed) {
            val name = app.packageName
            if (name in skip || out.containsKey(name)) continue
            val label = pm.getApplicationLabel(app)?.toString()?.trim().orEmpty().ifEmpty { name }
            val named = name.contains("velocity", true) || name.contains("wavelink", true) ||
                name.contains("ivanti", true) || label.contains("velocity", true)
            if (named || pm.getLaunchIntentForPackage(name) != null) {
                out[name] = label
            }
        }
        return out.entries.sortedBy { it.value.lowercase() }.map { AppEntry(it.key, it.value) }
    }

    fun launchIntent(context: Context, packageName: String): Intent? {
        val pm = context.packageManager
        pm.getLaunchIntentForPackage(packageName)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return it
        }
        return try {
            val info = pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
            val activity = info.activities?.firstOrNull { it.exported } ?: return null
            Intent().setClassName(packageName, activity.name)
        } catch (e: Exception) {
            null
        }
    }

    private fun storedOrDefault(context: Context): Pair<String, String> {
        val hash = prefs(context).getString("pass_hash", "") ?: ""
        val salt = prefs(context).getString("pass_salt", "") ?: ""
        return if (hash.isNotEmpty() && salt.isNotEmpty()) Pair(salt, hash) else Pair(DEFAULT_SALT, DEFAULT_HASH)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun parsePackages(raw: String): List<String> {
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val name = arr.optString(i).trim()
                if (name.isNotEmpty() && !out.contains(name)) out.add(name)
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun pbkdf2(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        val out = ByteArray(clean.length / 2)
        var i = 0
        while (i < out.size) {
            out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            i += 1
        }
        return out
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
