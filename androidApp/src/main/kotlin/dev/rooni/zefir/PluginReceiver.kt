package dev.rooni.zefir

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

// Запуск действия плагина из Tasker, MacroDroid и прочих хостов Locale.
// Результат уходит в переменные %zefir_*: через completion intent Tasker или сразу в ordered broadcast.
class PluginReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val bundle = intent.getBundleExtra(Plugin.EXTRA_BUNDLE) ?: return
        val command = bundle.getString(Plugin.KEY_COMMAND) ?: return
        val args = Plugin.args(bundle)
        val completion = completionOf(intent)
        Log.i(TAG, "fire $command args=${args.keys} completion=${completion != null} ordered=$isOrderedBroadcast")
        val app = context.applicationContext as ZefirApp
        // хост ждёт completion intent, говорим ему «в процессе»
        if (completion != null && isOrderedBroadcast) resultCode = RESULT_PENDING
        val pending = goAsync()
        app.appScope.launch(Dispatchers.Default) {
            val answer = runCatching {
                withTimeoutOrNull(TIMEOUT) { app.controller.automation.handlePlugin(command, args) }
            }.getOrNull() ?: buildJsonObject {
                put("ok", false)
                put("error", "timeout")
            }
            val ok = answer["ok"]?.jsonPrimitive?.booleanOrNull == true
            val variables = variables(answer)
            Log.i(TAG, "done $command ok=$ok error=${answer["error"]?.jsonPrimitive?.contentOrNull} vars=${variables.keySet()}")
            if (completion != null) {
                runCatching {
                    context.sendBroadcast(
                        Intent(completion)
                            .putExtra(Plugin.RESULT_CODE, if (ok) Activity.RESULT_OK else RESULT_FAILED)
                            .putExtra(Plugin.VARIABLES, variables),
                    )
                }
            } else if (isOrderedBroadcast) {
                runCatching {
                    pending.resultCode = if (ok) Activity.RESULT_OK else RESULT_FAILED
                    pending.getResultExtras(true).putBundle(Plugin.VARIABLES, variables)
                }
            }
            pending.finish()
        }
    }

    // Tasker и MacroDroid кладут сюда строку intent:// (TaskerPlugin.signalFinish), старые хосты бывает что сам Intent
    private fun completionOf(intent: Intent): Intent? {
        @Suppress("DEPRECATION")
        return when (val raw = intent.extras?.get(Plugin.COMPLETION_INTENT)) {
            is String -> runCatching { Intent.parseUri(raw, Intent.URI_INTENT_SCHEME) }.getOrNull()
            is Intent -> raw
            else -> null
        }
    }

    private fun variables(answer: JsonObject) = Bundle().apply {
        putString("%zefir_ok", (answer["ok"]?.jsonPrimitive?.booleanOrNull == true).toString())
        putString("%zefir_json", answer.toString())
        answer["error"]?.jsonPrimitive?.contentOrNull?.let { putString("%zefir_error", it) }
        val result = answer["result"]
        if (result != null) putString("%zefir_result", if (result is JsonPrimitive) result.contentOrNull.orEmpty() else result.toString())
        // простые поля результата отдельными переменными: %zefir_state, %zefir_server и т.д.
        (result as? JsonObject)?.forEach { (name, value) ->
            val variable = "%zefir_" + name.lowercase().replace(Regex("[^a-z0-9_]"), "_")
            if (value is JsonPrimitive) putString(variable, value.contentOrNull.orEmpty())
        }
    }

    private companion object {
        const val ACTION_FIRE = "com.twofortyfouram.locale.intent.action.FIRE_SETTING"
        const val RESULT_FAILED = Activity.RESULT_FIRST_USER + 1
        const val RESULT_PENDING = Activity.RESULT_FIRST_USER + 2
        const val TIMEOUT = 28_000L
        const val TAG = "ZefirPlugin"
    }
}
