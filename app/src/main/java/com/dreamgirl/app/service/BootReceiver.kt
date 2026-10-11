package com.dreamgirl.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dreamgirl.app.data.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }
        if (!ServiceLocator.isInitialized) ServiceLocator.init(context.applicationContext)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val enabled = ServiceLocator.settings.current().proactiveEnabled
                if (enabled) DreamForegroundService.start(context)
            } finally {
                pending.finish()
            }
        }
    }
}
