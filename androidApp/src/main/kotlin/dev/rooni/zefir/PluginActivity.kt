package dev.rooni.zefir

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.rooni.zefir.ui.screens.PluginEditor
import dev.rooni.zefir.ui.screens.pluginBlurb

// Экран настройки действия для Tasker, MacroDroid и других хостов плагинов Locale.
class PluginActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as ZefirApp
        val saved = intent.getBundleExtra(Plugin.EXTRA_BUNDLE)
        val command = saved?.getString(Plugin.KEY_COMMAND)
        val args = saved?.let(Plugin::args).orEmpty()
        setContent {
            PluginEditor(app.controller, command, args, onCancel = { finish() }) { chosen, values ->
                android.util.Log.i("ZefirPlugin", "saved $chosen args=${values.keys}")
                setResult(Activity.RESULT_OK, Plugin.result(chosen, values, pluginBlurb(chosen, values)))
                finish()
            }
        }
    }
}

// Протокол плагинов Locale и расширения Tasker (переменные и ожидание результата).
object Plugin {
    const val EXTRA_BUNDLE = "com.twofortyfouram.locale.intent.extra.BUNDLE"
    private const val EXTRA_BLURB = "com.twofortyfouram.locale.intent.extra.BLURB"
    private const val TASKER = "net.dinglisch.android.tasker.extras."
    private const val REPLACE_KEYS = TASKER + "VARIABLE_REPLACE_KEYS"
    private const val REQUESTED_TIMEOUT = TASKER + "REQUESTED_TIMEOUT"
    private const val RELEVANT_VARIABLES = "net.dinglisch.android.tasker.RELEVANT_VARIABLES"
    const val COMPLETION_INTENT = TASKER + "COMPLETION_INTENT"
    const val RESULT_CODE = TASKER + "RESULT_CODE"
    const val VARIABLES = TASKER + "VARIABLES"

    const val KEY_COMMAND = "zefir_command"
    private const val ARG_PREFIX = "zefir_arg_"

    fun args(bundle: Bundle): Map<String, String> = bundle.keySet()
        .filter { it.startsWith(ARG_PREFIX) }
        .mapNotNull { name -> bundle.getString(name)?.let { name.removePrefix(ARG_PREFIX) to it } }
        .toMap()

    fun result(command: String, args: Map<String, String>, blurb: String): Intent {
        val bundle = Bundle().apply {
            putString(KEY_COMMAND, command)
            args.forEach { (name, value) -> putString(ARG_PREFIX + name, value) }
            // Tasker сам подставит %переменные в эти поля перед запуском
            if (args.isNotEmpty()) putString(REPLACE_KEYS, args.keys.joinToString(" ") { ARG_PREFIX + it })
        }
        return Intent().apply {
            putExtra(EXTRA_BUNDLE, bundle)
            putExtra(EXTRA_BLURB, blurb)
            putExtra(REQUESTED_TIMEOUT, 30_000)
            putExtra(
                RELEVANT_VARIABLES,
                arrayOf(
                    "%zefir_ok\nOK\ntrue or false",
                    "%zefir_error\nError\nWhy the command failed",
                    "%zefir_result\nResult\nResult as JSON",
                    "%zefir_state\nState\noff, starting, on, stopping or failed",
                    "%zefir_server\nServer\nCurrent server",
                    "%zefir_json\nReply\nWhole reply as JSON",
                ),
            )
        }
    }
}
