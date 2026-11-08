package dev.rooni.zefir

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.PausableMonotonicFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import dev.rooni.zefir.ui.App
import dev.rooni.zefir.ui.AppMotion
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val vpnApp = application as ZefirApp
        vpnApp.platform.attach(this)
        handleLink(intent)
        readSystemScale()
        val ui = AndroidUiDispatcher.CurrentThread
        val clock = PausableMonotonicFrameClock(ui[androidx.compose.runtime.MonotonicFrameClock]!!)
        val context = ui + clock + AppMotion
        val recomposer = Recomposer(context)
        lifecycleScope.launch(context) { recomposer.runRecomposeAndApplyChanges() }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = clock.resume()
            override fun onStop(owner: LifecycleOwner) = clock.pause()
            override fun onDestroy(owner: LifecycleOwner) = recomposer.cancel()
        })
        val view = ComposeView(this).apply {
            setParentCompositionContext(recomposer)
            setContent { App(vpnApp.controller) }
        }
        setContentView(view)
    }

    private fun readSystemScale() {
        AppMotion.system = runCatching {
            android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
    }

    override fun onResume() {
        super.onResume()
        readSystemScale()
        (application as ZefirApp).platform.onResume()
    }

    override fun onPause() {
        super.onPause()
        (application as ZefirApp).platform.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    private fun handleLink(intent: Intent?) {
        val controller = (application as ZefirApp).controller
        if (intent?.action == ACTION_PROFILE) {
            intent.getStringExtra(EXTRA_PROFILE)?.let(controller::openShortcut)
            return
        }
        if (intent?.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let {
                controller.requestImport(it)
                return
            }
            val file = androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, android.net.Uri::class.java) ?: return
            Thread {
                runCatching {
                    contentResolver.openInputStream(file)?.use { String(it.readBytes().take(MAX_SHARED_FILE).toByteArray()) }
                }.getOrNull()?.takeIf { it.isNotBlank() }?.let(controller::requestImport)
            }.start()
            return
        }
        val link = intent?.dataString ?: return
        controller.requestImport(link)
    }

    private companion object {
        const val MAX_SHARED_FILE = 4 * 1024 * 1024
        const val ACTION_PROFILE = "dev.rooni.zefir.PROFILE"
        const val EXTRA_PROFILE = "profile"
    }
}
