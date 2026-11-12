package com.nexis.remoteagent

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class DeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Toast.makeText(context, "Nexis DPC: Privilégios de Administrador Habilitados", Toast.LENGTH_SHORT).show()
        setupResetPasswordToken(context)
        applyEnterprisePolicies(context)
        AccessibilitySetup.enable(context)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Toast.makeText(context, "Nexis DPC: Privilégios de Administrador Revogados", Toast.LENGTH_SHORT).show()
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        Toast.makeText(context, "Nexis DPC: Provisionamento Concluído com Sucesso", Toast.LENGTH_LONG).show()
        setupResetPasswordToken(context)
        applyEnterprisePolicies(context)
        AccessibilitySetup.enable(context)
    }

    override fun onPasswordChanged(context: Context, intent: Intent) {
        super.onPasswordChanged(context, intent)
        android.util.Log.i("NexisDPC", "Senha alterada (legacy callback). Atualizando token...")
        setupResetPasswordToken(context)
    }

    override fun onPasswordChanged(context: Context, intent: Intent, user: android.os.UserHandle) {
        super.onPasswordChanged(context, intent, user)
        android.util.Log.i("NexisDPC", "Senha alterada (user-aware callback). Atualizando token...")
        setupResetPasswordToken(context)
    }

    private fun applyEnterprisePolicies(context: Context) {
        val result = EnterpriseConnectivityPolicy.apply(context)
        android.util.Log.i("NexisDPC", "Políticas de conectividade: ${EnterpriseConnectivityPolicy.formatSummary(result)}")
    }

    private fun setupResetPasswordToken(context: Context) {
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val adminComponent = android.content.ComponentName(context, DeviceAdminReceiver::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val token = "nexis_super_secure_reset_token_32_bytes_long".toByteArray()
                val success = dpm.setResetPasswordToken(adminComponent, token)
                if (success) {
                    android.util.Log.i("NexisDPC", "Token de reset de senha configurado com sucesso.")
                } else {
                    android.util.Log.w("NexisDPC", "Não foi possível definir o token de reset de senha.")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("NexisDPC", "Erro ao definir token de reset de senha: ${e.message}")
        }
    }
}
