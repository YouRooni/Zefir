package dev.rooni.zefir

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.rooni.zefir.app.Automation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

// Команды из Tasker, MacroDroid, Automate и adb.
// Ответ приходит двумя путями: resultData (его печатает `am broadcast`) и отдельной рассылкой RESULT.
class AutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_COMMAND) return
        val app = context.applicationContext as ZefirApp
        val key = intent.getStringExtra(EXTRA_KEY)
        val command = intent.getStringExtra(EXTRA_COMMAND)
        val args = intent.extras?.let { extras ->
            extras.keySet().filter { it != EXTRA_KEY && it != EXTRA_COMMAND }.mapNotNull { name ->
                @Suppress("DEPRECATION")
                extras.get(name)?.let { name to it.toString() }
            }.toMap()
        }.orEmpty()
        val pending = goAsync()
        app.appScope.launch(Dispatchers.Default) {
            val answer = try {
                withTimeoutOrNull(TIMEOUT) { app.controller.automation.handle(key, command, args) }
                    ?: buildJsonObject {
                        put("ok", false)
                        put("command", command)
                        put("error", "timeout")
                    }
            } catch (e: Exception) {
                buildJsonObject {
                    put("ok", false)
                    put("command", command)
                    put("error", e.message ?: "failed")
                }
            }
            val ok = answer["ok"]?.jsonPrimitive?.booleanOrNull == true
            runCatching {
                pending.resultCode = if (ok) Activity.RESULT_OK else RESULT_FAILED
                pending.resultData = answer.toString()
            }
            // чужому без ключа рассылкой ничего не шлём
            if (app.controller.automation.authorized(key)) reply(context, args[EXTRA_REPLY_PACKAGE], args[EXTRA_REQUEST_ID], answer)
            pending.finish()
        }
    }

    private fun reply(context: Context, target: String?, requestId: String?, answer: JsonObject) {
        val intent = Intent(ACTION_RESULT).apply {
            putExtra(EXTRA_COMMAND, answer["command"]?.jsonPrimitive?.contentOrNull)
            putExtra("ok", answer["ok"]?.jsonPrimitive?.booleanOrNull == true)
            putExtra("result", answer["result"]?.toString())
            putExtra("error", answer["error"]?.jsonPrimitive?.contentOrNull)
            putExtra("json", answer.toString())
            requestId?.let { putExtra(EXTRA_REQUEST_ID, it) }
        }
        send(context, intent, target)
    }

    companion object {
        const val ACTION_COMMAND = "dev.rooni.zefir.COMMAND"
        const val ACTION_RESULT = "dev.rooni.zefir.RESULT"
        const val ACTION_EVENT = "dev.rooni.zefir.EVENT"
        private const val EXTRA_KEY = "key"
        private const val EXTRA_COMMAND = "command"
        private const val EXTRA_REPLY_PACKAGE = "reply_package"
        private const val EXTRA_REQUEST_ID = "request_id"
        private const val RESULT_FAILED = 1
        private const val TIMEOUT = 25_000L

        // ответы и события уходят только известным приложениям автоматизации или тому, кого назвали
        private val AUTOMATION_APPS = listOf("net.dinglisch.android.taskerm", "com.arlosoft.macrodroid", "com.llamalab.automate")

        private fun send(context: Context, intent: Intent, target: String?) {
            val targets = target?.let(::listOf) ?: AUTOMATION_APPS.filter { pkg ->
                runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
            }
            targets.forEach { pkg -> runCatching { context.sendBroadcast(Intent(intent).setPackage(pkg)) } }
        }

        fun sendEvent(context: Context, state: String, server: String?, profile: String?) {
            val json = buildJsonObject {
                put("event", "state")
                put("state", state)
                put("server", server)
                put("profile", profile)
            }
            val intent = Intent(ACTION_EVENT).apply {
                putExtra("event", "state")
                putExtra("state", state)
                putExtra("server", server)
                putExtra("profile", profile)
                putExtra("json", json.toString())
            }
            send(context, intent, null)
        }

        fun stateName(state: dev.rooni.zefir.model.TunnelState) = Automation.stateName(state)
    }
}
