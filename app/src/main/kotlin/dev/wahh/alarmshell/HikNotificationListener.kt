package dev.wahh.alarmshell

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.wahh.alarmshell.core.NotificationInterpreter

/**
 * Mirrors how the official widget learns state: it reacts to the Hik-Connect app's own
 * notifications instead of polling or holding a connection open.
 */
class HikNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        val extras = notification.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text =
            listOfNotNull(
                extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
                extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            ).joinToString(" ")
        val channel = notification.notification?.channelId
        val state =
            NotificationInterpreter.interpret(
                packageName = sbn.packageName,
                title = title,
                text = text,
                channelId = channel,
            ) ?: return
        val app = application as? AlarmApplication ?: return
        app.armState.write(state)
        ArmWidget.updateAll(this)
    }
}
