package dev.rooni.zefir

import android.content.Context
import dev.rooni.zefir.app.UpdateCheck

// GitHub-сборка проверяет релизы сама, магазина нет
fun storeUpdates(context: Context): (suspend () -> UpdateCheck)? = null
