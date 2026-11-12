package com.nexis.remoteagent

import android.content.ComponentName
import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import java.util.ArrayDeque

/**
 * Teclado sem teclas. Enquanto o analista digita pelo espelhamento, ele escreve
 * no campo focado e descarta a sugestão. O teclado anterior volta em seguida.
 */
class NexisInputMethod : InputMethodService() {
    private var imeAction = EditorInfo.IME_ACTION_UNSPECIFIED

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        if (instance == this) instance = null
        super.onDestroy()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        instance = this
        imeAction = attribute?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
            ?: EditorInfo.IME_ACTION_UNSPECIFIED
        NexisTyper.drain()
    }

    override fun onEvaluateInputViewShown(): Boolean = false

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View = View(this)

    fun ready(): Boolean = currentInputConnection != null

    fun commit(text: String): Boolean {
        val ic = currentInputConnection ?: return false
        ic.commitText(text, 1)
        return true
    }

    fun backspace(): Boolean {
        val ic = currentInputConnection ?: return false
        return key(ic, KeyEvent.KEYCODE_DEL)
    }

    fun enter(): Boolean {
        val ic = currentInputConnection ?: return false
        val action = imeAction
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            return ic.performEditorAction(action)
        }
        return key(ic, KeyEvent.KEYCODE_ENTER)
    }

    private fun key(ic: InputConnection, code: Int): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        val down = ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        val up = ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
        return down && up
    }

    companion object {
        const val ID = "com.nexis.remoteagent/.NexisInputMethod"

        @Volatile
        var instance: NexisInputMethod? = null
    }
}

object NexisTyper {
    private const val TAG = "NexisTyper"
    private const val PREF = "nexis_ime"
    private const val PREF_PREVIOUS = "previous"
    private val handler = Handler(Looper.getMainLooper())
    private val pending = ArrayDeque<Job>()
    private var failPosted = false
    private var restoreRunnable: Runnable? = null

    /** Android recusou ligar o teclado (o dono do dispositivo não pode mexer em enabled_input_methods). */
    @Volatile
    private var unavailable = false

    private class Job(
        val action: (NexisInputMethod) -> Boolean,
        val fallback: () -> Unit
    )

    fun enqueue(context: Context, action: (NexisInputMethod) -> Boolean, fallback: () -> Unit) {
        val app = context.applicationContext
        handler.post {
            if (unavailable || !safeEnsureIme(app)) {
                unavailable = true
                runSafely(fallback)
                return@post
            }
            pending.add(Job(action, fallback))
            drain()
            scheduleFail()
            armRestore(app)
        }
    }

    private fun safeEnsureIme(context: Context): Boolean =
        try {
            ensureIme(context)
        } catch (e: Exception) {
            Log.w(TAG, "Teclado do suporte indisponível; digitação segue pelo campo: ${e.message}")
            false
        }

    private fun runSafely(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao digitar no campo: ${e.message}")
        }
    }

    fun drain() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { drain() }
            return
        }
        val ime = NexisInputMethod.instance
        if (ime == null || !ime.ready()) return
        while (pending.isNotEmpty()) {
            val job = pending.removeFirst()
            val done = try {
                job.action(ime)
            } catch (e: Exception) {
                Log.w(TAG, "Teclado do suporte falhou: ${e.message}")
                false
            }
            if (!done) runSafely(job.fallback)
        }
    }

    fun restore(context: Context) {
        val app = context.applicationContext
        handler.post {
            restoreRunnable?.let { handler.removeCallbacks(it) }
            safeRestore(app)
        }
    }

    fun recover(context: Context) {
        val app = context.applicationContext
        handler.post { safeRestore(app) }
    }

    private fun safeRestore(context: Context) {
        try {
            restoreIme(context)
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível devolver o teclado anterior: ${e.message}")
        }
    }

    private fun scheduleFail() {
        if (failPosted) return
        failPosted = true
        handler.postDelayed({
            failPosted = false
            val ime = NexisInputMethod.instance
            if (ime != null && ime.ready()) {
                drain()
                return@postDelayed
            }
            while (pending.isNotEmpty()) runSafely(pending.removeFirst().fallback)
        }, 280)
    }

    private fun ensureIme(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(context.packageName)) return false
        val admin = ComponentName(context, DeviceAdminReceiver::class.java)
        val resolver = context.contentResolver
        val current = Settings.Secure.getString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        if (current.isNotEmpty() && current != NexisInputMethod.ID) {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(PREF_PREVIOUS, current).apply()
        }
        val enabled = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_INPUT_METHODS).orEmpty()
        if (!enabled.split(':').contains(NexisInputMethod.ID)) {
            val next = if (enabled.isBlank()) NexisInputMethod.ID else "$enabled:${NexisInputMethod.ID}"
            dpm.setSecureSetting(admin, Settings.Secure.ENABLED_INPUT_METHODS, next)
        }
        if (current != NexisInputMethod.ID) {
            dpm.setSecureSetting(admin, Settings.Secure.DEFAULT_INPUT_METHOD, NexisInputMethod.ID)
            Log.i(TAG, "Teclado do suporte ativo para a digitação remota.")
        }
        return true
    }

    private fun armRestore(context: Context) {
        restoreRunnable?.let { handler.removeCallbacks(it) }
        val run = Runnable { restoreIme(context) }
        restoreRunnable = run
        handler.postDelayed(run, 20_000)
    }

    private fun restoreIme(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        val previous = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(PREF_PREVIOUS, null)
            ?.takeIf { it.isNotBlank() && it != NexisInputMethod.ID }
            ?: return
        val current = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD
        ).orEmpty()
        if (current != NexisInputMethod.ID) return
        val admin = ComponentName(context, DeviceAdminReceiver::class.java)
        dpm.setSecureSetting(admin, Settings.Secure.DEFAULT_INPUT_METHOD, previous)
        Log.i(TAG, "Teclado anterior restaurado.")
    }
}
