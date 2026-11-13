package com.nexis.remoteagent

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * Liga o serviço de acessibilidade como Device Owner.
 * Uma lista restrita de serviços permitidos deixa a tela do Android
 * com o aviso de falar com o administrador, e o usuário não consegue ativar.
 */
object AccessibilitySetup {

    private const val TAG = "NexisA11y"

    fun enable(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        val admin = ComponentName(context, DeviceAdminReceiver::class.java)
        val service = ComponentName(context, RemoteAccessibilityService::class.java)
        val flat = service.flattenToString()
        try {
            dpm.setPermittedAccessibilityServices(admin, null)
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível liberar a lista de acessibilidade: ${e.message}")
        }
        val current = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val parts = current.split(':').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        if (!parts.contains(flat)) parts.add(flat)
        val next = parts.joinToString(":")
        try {
            dpm.setSecureSetting(admin, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next)
            dpm.setSecureSetting(admin, Settings.Secure.ACCESSIBILITY_ENABLED, "1")
            Log.i(TAG, "Acessibilidade ligada pelo dono do aparelho.")
            return
        } catch (e: Exception) {
            Log.w(TAG, "setSecureSetting recusou a acessibilidade: ${e.message}")
        }
        try {
            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                next
            )
            Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            Log.i(TAG, "Acessibilidade ligada pela configuração segura.")
        } catch (e: Exception) {
            Log.w(TAG, "Acessibilidade não ligou sozinha: ${e.message}")
        }
    }
}
