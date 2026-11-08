package dev.rooni.zefir

import android.content.Context
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.UpdateAvailability
import dev.rooni.zefir.app.UpdateCheck
import dev.rooni.zefir.app.Updates
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

// Zefir X: про обновления спрашиваем сам Google Play, а не GitHub
fun storeUpdates(context: Context): (suspend () -> UpdateCheck)? {
    val manager = AppUpdateManagerFactory.create(context)
    return {
        suspendCancellableCoroutine { result ->
            manager.appUpdateInfo
                .addOnSuccessListener { info ->
                    val available = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                    result.resume(if (available) UpdateCheck.Available("", Updates.PLAY_URL) else UpdateCheck.Latest)
                }
                .addOnFailureListener { result.resume(UpdateCheck.Failed) }
        }
    }
}
