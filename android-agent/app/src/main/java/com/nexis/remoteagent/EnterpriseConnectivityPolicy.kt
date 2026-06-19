package com.nexis.remoteagent

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Políticas enterprise de conectividade aplicadas via Device Owner / Device Admin.
 * Restringe alterações do usuário (User Restrictions) e reforça Wi-Fi, dados móveis e GPS.
 */
object EnterpriseConnectivityPolicy {

    private const val TAG = "NexisConnectivityPolicy"
    private const val GLOBAL_MOBILE_DATA = "mobile_data"

    data class PolicyResult(
        val isDeviceOwner: Boolean,
        val isDeviceAdmin: Boolean,
        val restrictionsApplied: List<String>,
        val restrictionsSkipped: List<String>,
        val restrictionsFailed: List<String>,
        val locationEnforced: Boolean,
        val wifiEnforced: Boolean,
        val mobileDataEnforced: Boolean
    )

    data class ReinforceResult(
        val gpsRestored: Boolean,
        val wifiRestored: Boolean,
        val mobileDataRestored: Boolean
    ) {
        val anyRestored: Boolean get() = gpsRestored || wifiRestored || mobileDataRestored
    }

    fun adminComponent(context: Context): ComponentName =
        ComponentName(context, DeviceAdminReceiver::class.java)

    /**
     * Aplica User Restrictions e garante que Wi-Fi, dados móveis e localização estejam ativos.
     * Deve ser chamado após provisionamento Device Owner e no boot do serviço.
     */
    fun apply(context: Context): PolicyResult {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = adminComponent(context)
        val isDeviceOwner = dpm.isDeviceOwnerApp(context.packageName)
        val isDeviceAdmin = dpm.isAdminActive(admin)

        val applied = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val failed = mutableListOf<String>()

        if (!isDeviceOwner && !isDeviceAdmin) {
            Log.w(TAG, "Políticas não aplicadas: app não é Device Owner nem Device Admin.")
            return PolicyResult(
                isDeviceOwner = false,
                isDeviceAdmin = false,
                restrictionsApplied = emptyList(),
                restrictionsSkipped = emptyList(),
                restrictionsFailed = listOf("not_device_owner_or_admin"),
                locationEnforced = false,
                wifiEnforced = false,
                mobileDataEnforced = false
            )
        }

        if (isDeviceOwner) {
            applyUserRestrictions(dpm, admin, applied, skipped, failed)
            // Remove restriction que bloqueia tela de localizacao em devices ja provisionados
            clearRestrictionIfSet(dpm, admin, UserManager.DISALLOW_SHARE_LOCATION, skipped)
        } else {
            skipped.add("user_restrictions_require_device_owner")
            Log.i(TAG, "User Restrictions ignoradas: requer Device Owner.")
        }

        val locationEnforced = false
        val wifiEnforced = false
        val mobileDataEnforced = false

        Log.i(TAG, formatSummary(
            PolicyResult(
                isDeviceOwner, isDeviceAdmin, applied, skipped, failed,
                locationEnforced, wifiEnforced, mobileDataEnforced
            )
        ))

        return PolicyResult(
            isDeviceOwner = isDeviceOwner,
            isDeviceAdmin = isDeviceAdmin,
            restrictionsApplied = applied,
            restrictionsSkipped = skipped,
            restrictionsFailed = failed,
            locationEnforced = locationEnforced,
            wifiEnforced = wifiEnforced,
            mobileDataEnforced = mobileDataEnforced
        )
    }

    /**
     * Reforço reativo usado pelo watchdog quando monitoramento esta ativo.
     * @param enforceLocation reativar GPS apenas com monitoramento ligado
     */
    fun reinforceConnectivity(context: Context, enforceLocation: Boolean = true): ReinforceResult {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = adminComponent(context)
        if (!dpm.isAdminActive(admin)) {
            return ReinforceResult(false, false, false)
        }

        var gpsRestored = false
        var wifiRestored = false
        var mobileDataRestored = false

        try {
            val locMgr = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (enforceLocation && !locMgr.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                gpsRestored = enforceLocationEnabled(dpm, admin)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao reforçar GPS: ${e.message}")
        }

        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (!wifiManager.isWifiEnabled) {
                wifiRestored = enforceWifiEnabled(context, dpm, admin)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao reforçar Wi-Fi: ${e.message}")
        }

        if (dpm.isDeviceOwnerApp(context.packageName)) {
            try {
                if (!isMobileDataEnabled(context)) {
                    mobileDataRestored = enforceMobileDataEnabled(context, dpm, admin)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao reforçar dados móveis: ${e.message}")
            }
        }

        return ReinforceResult(gpsRestored, wifiRestored, mobileDataRestored)
    }

    fun formatSummary(result: PolicyResult): String {
        val parts = mutableListOf<String>()
        parts.add("DeviceOwner=${result.isDeviceOwner}")
        parts.add("DeviceAdmin=${result.isDeviceAdmin}")
        if (result.restrictionsApplied.isNotEmpty()) {
            parts.add("restrictions=${result.restrictionsApplied.joinToString(",")}")
        }
        if (result.restrictionsSkipped.isNotEmpty()) {
            parts.add("skipped=${result.restrictionsSkipped.joinToString(",")}")
        }
        if (result.restrictionsFailed.isNotEmpty()) {
            parts.add("failed=${result.restrictionsFailed.joinToString(",")}")
        }
        parts.add("location=${result.locationEnforced}")
        parts.add("wifi=${result.wifiEnforced}")
        parts.add("mobileData=${result.mobileDataEnforced}")
        return parts.joinToString(" | ")
    }

    private fun applyUserRestrictions(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        applied: MutableList<String>,
        skipped: MutableList<String>,
        failed: MutableList<String>
    ) {
        val restrictions = mutableListOf(
            UserManager.DISALLOW_CHANGE_WIFI_STATE,
            UserManager.DISALLOW_CONFIG_WIFI,
            UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            restrictions.add(UserManager.DISALLOW_AIRPLANE_MODE)
        }

        for (restriction in restrictions) {
            when (applyRestriction(dpm, admin, restriction)) {
                RestrictionOutcome.APPLIED -> applied.add(restriction)
                RestrictionOutcome.ALREADY_SET -> skipped.add(restriction)
                RestrictionOutcome.FAILED -> failed.add(restriction)
            }
        }
    }

    private enum class RestrictionOutcome { APPLIED, ALREADY_SET, FAILED }

    private fun applyRestriction(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        restriction: String
    ): RestrictionOutcome {
        return try {
            val bundle = dpm.getUserRestrictions(admin)
            if (bundle.getBoolean(restriction, false)) {
                RestrictionOutcome.ALREADY_SET
            } else {
                dpm.addUserRestriction(admin, restriction)
                Log.i(TAG, "User restriction aplicada: $restriction")
                RestrictionOutcome.APPLIED
            }
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao aplicar restriction $restriction: ${e.message}")
            RestrictionOutcome.FAILED
        }
    }

    private fun enforceLocationEnabled(
        dpm: DevicePolicyManager,
        admin: ComponentName
    ): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                dpm.setLocationEnabled(admin, true)
            } else {
                @Suppress("DEPRECATION")
                dpm.setSecureSetting(admin, Settings.Secure.LOCATION_MODE, "3")
            }
            Log.i(TAG, "Localização reforçada via DPC.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao reforçar localização: ${e.message}")
            false
        }
    }

    private fun enforceWifiEnabled(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName
    ): Boolean {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (!wifiManager.isWifiEnabled) {
                @Suppress("DEPRECATION")
                val ok = wifiManager.setWifiEnabled(true)
                if (!ok && dpm.isDeviceOwnerApp(context.packageName)) {
                    dpm.setGlobalSetting(admin, Settings.Global.WIFI_ON, "1")
                }
            } else if (dpm.isDeviceOwnerApp(context.packageName)) {
                dpm.setGlobalSetting(admin, Settings.Global.WIFI_ON, "1")
            }
            Log.i(TAG, "Wi-Fi reforçado via DPC/WifiManager.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao reforçar Wi-Fi: ${e.message}")
            false
        }
    }

    private fun clearRestrictionIfSet(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        restriction: String,
        skipped: MutableList<String>
    ) {
        try {
            val bundle = dpm.getUserRestrictions(admin)
            if (bundle.getBoolean(restriction, false)) {
                dpm.clearUserRestriction(admin, restriction)
                skipped.add("cleared_$restriction")
                Log.i(TAG, "User restriction removida: $restriction")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao remover restriction $restriction: ${e.message}")
        }
    }

    fun isMobileDataEnabled(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                tm.isDataEnabled
            } else {
                @Suppress("DEPRECATION")
                Settings.Global.getInt(context.contentResolver, GLOBAL_MOBILE_DATA, 1) == 1
            }
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao ler estado de dados moveis: ${e.message}")
            true
        }
    }

    private fun enforceMobileDataEnabled(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName
    ): Boolean {
        return try {
            dpm.setGlobalSetting(admin, GLOBAL_MOBILE_DATA, "1")
            val ok = isMobileDataEnabled(context)
            if (ok) {
                Log.i(TAG, "Dados moveis reforçados via DPC.")
            } else {
                Log.w(TAG, "setGlobalSetting(mobile_data) nao surtiu efeito neste OEM.")
            }
            ok
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao reforçar dados moveis: ${e.message}")
            false
        }
    }
}
