package com.youtv.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val application = context.applicationContext as YouTvApplication
        val bootStartup = runBlocking { application.container.settingsRepository.settings.first().bootStartup }
        if (bootStartup) {
            try {
                context.startActivity(
                    Intent(context, ComposeMainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

}
