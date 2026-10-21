package com.example.service

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

data class CachedNotification(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val postTime: Long,
    val sbn: StatusBarNotification
)

class AuraNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "Aura"
        private var instance: AuraNotificationListener? = null
        private val notificationCache = ConcurrentHashMap<String, CachedNotification>()

        fun isRunning(): Boolean = instance != null

        fun getActiveNotificationsCached(): List<CachedNotification> {
            return notificationCache.values.sortedByDescending { it.postTime }
        }

        fun replyToNotification(key: String, message: String): Boolean {
            val inst = instance ?: return false
            val item = notificationCache[key] ?: return false
            val notif = item.sbn.notification ?: return false

            val actions = notif.actions ?: return false
            for (action in actions) {
                val remoteInputs = action.remoteInputs ?: continue
                for (remoteInput in remoteInputs) {
                    val bundle = Bundle().apply {
                        putCharSequence(remoteInput.resultKey, message)
                    }
                    val intent = Intent()
                    RemoteInput.addResultsToIntent(arrayOf(remoteInput), intent, bundle)
                    try {
                        action.actionIntent.send(inst, 0, intent)
                        return true
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send notification reply", e)
                        return false
                    }
                }
            }
            return false
        }

        fun dismissNotification(key: String): Boolean {
            val inst = instance ?: return false
            return try {
                inst.cancelNotification(key)
                notificationCache.remove(key)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to dismiss notification $key", e)
                false
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        refreshNotifications()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        notificationCache.clear()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        cacheSbn(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        notificationCache.remove(sbn.key)
    }

    private fun refreshNotifications() {
        try {
            val active = activeNotifications ?: return
            notificationCache.clear()
            for (sbn in active) {
                cacheSbn(sbn)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh active notifications", e)
        }
    }

    private fun cacheSbn(sbn: StatusBarNotification) {
        val notif = sbn.notification ?: return
        val extras = notif.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val pkg = sbn.packageName ?: ""
        val appName = try {
            val pm = packageManager
            val ai = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(ai).toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get app label for $pkg", e)
            pkg
        }

        if (title.isNotBlank() || text.isNotBlank()) {
            notificationCache[sbn.key] = CachedNotification(
                key = sbn.key,
                packageName = pkg,
                appName = appName,
                title = title,
                text = text,
                postTime = sbn.postTime,
                sbn = sbn
            )
        }
    }
}
