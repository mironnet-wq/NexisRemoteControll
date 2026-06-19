package com.nexis.remoteagent

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

class PackageInstallHelper(private val context: Context) {

    companion object {
        private const val TAG = "NexisPackageInstaller"
        private const val ACTION_INSTALL_COMPLETE = "com.nexis.remoteagent.INSTALL_COMPLETE"
    }

    interface InstallCallback {
        fun onSuccess()
        fun onFailure(message: String)
    }

    /**
     * Instala silenciosamente um arquivo APK local.
     * Requisito: O app Nexis Agent deve ser o Device Owner (DPC) do aparelho.
     */
    fun installPackageSilently(apkFile: File, callback: InstallCallback) {
        if (!apkFile.exists()) {
            callback.onFailure("Arquivo APK não encontrado localmente")
            return
        }

        try {
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            
            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)

            // Escrever dados do APK na Sessão do Instalador
            val out = session.openWrite("nexis_install_session", 0, -1)
            val input: InputStream = FileInputStream(apkFile)
            val buffer = ByteArray(65536)
            var c: Int
            while (input.read(buffer).also { c = it } != -1) {
                out.write(buffer, 0, c)
            }
            session.fsync(out)
            input.close()
            out.close()

            // Registrar receptor temporário para capturar o resultado da instalação
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                    val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Erro desconhecido"

                    if (status == PackageInstaller.STATUS_SUCCESS) {
                        Log.i(TAG, "Instalação silenciosa concluída com sucesso")
                        callback.onSuccess()
                    } else {
                        Log.e(TAG, "Falha na instalação silenciosa: Status=$status, Mensagem=$message")
                        callback.onFailure("Código de Falha: $status. $message")
                    }
                    context.unregisterReceiver(this)
                }
            }

            // Registrar o broadcast receiver para capturar o status
            val filter = IntentFilter(ACTION_INSTALL_COMPLETE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }

            // Criar PendingIntent para repassar o status
            val intent = Intent(ACTION_INSTALL_COMPLETE)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(context, sessionId, intent, flags)

            // Confirmar a sessão de instalação
            session.commit(pendingIntent.intentSender)
            session.close()

            Log.i(TAG, "Sessão de instalação silenciosa $sessionId confirmada.")

        } catch (e: Exception) {
            Log.e(TAG, "Erro fatal ao tentar instalar APK: ${e.message}", e)
            callback.onFailure(e.message ?: "Exceção ao criar sessão de instalação")
        }
    }
}
