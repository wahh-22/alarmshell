package dev.wahh.alarmshell

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import dev.wahh.alarmshell.core.Arming
import dev.wahh.alarmshell.core.DefenceMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/**
 * Launcher widget in the PhantomShell palette. It never invents state: it shows what the official
 * app's notifications reported, or "SIN CONFIRMAR" after a command until an event arrives.
 */
class ArmWidget : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        updateAll(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle,
    ) {
        // Re-render at the new cell size instead of letting the bitmap stretch.
        updateAll(context)
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        super.onReceive(context, intent)
        val mode =
            when (intent.action) {
                ACTION_STAY -> DefenceMode.STAY
                ACTION_AWAY -> DefenceMode.AWAY
                ACTION_DISARM -> DefenceMode.DISARM
                else -> null
            }
        if (mode == null) return
        val pending = goAsync()
        val app = context.applicationContext as AlarmApplication
        val arming =
            when (mode) {
                DefenceMode.STAY -> Arming.STAY
                DefenceMode.AWAY -> Arming.AWAY
                DefenceMode.DISARM -> Arming.DISARMED
            }
        app.armState.markSent(arming)
        updateAll(context)
        executor.execute {
            try {
                val session = app.sessions.read()
                if (app.armState.serial.isBlank() || app.armState.enUser.isBlank()) {
                    app.armState.write(
                        dev.wahh.alarmshell.core.ArmState(Arming.UNKNOWN, System.currentTimeMillis(), "error:config"),
                    )
                    return@execute
                }
                if (session == null) {
                    app.armState.write(
                        dev.wahh.alarmshell.core.ArmState(Arming.UNKNOWN, System.currentTimeMillis(), "no-session"),
                    )
                    return@execute
                }
                val code = app.client.setDefenceMode(session, app.armState.serial, mode, app.armState.enUser)
                app.armState.write(
                    dev.wahh.alarmshell.core.ArmState(Arming.UNKNOWN, System.currentTimeMillis(), "sent:$code"),
                )
            } catch (error: Exception) {
                app.armState.write(
                    dev.wahh.alarmshell.core.ArmState(Arming.UNKNOWN, System.currentTimeMillis(), "error:${error.javaClass.simpleName}"),
                )
            } finally {
                renderAll(context)
                refreshFromCloud(context, force = true)
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_STAY = "dev.wahh.alarmshell.widget.STAY"
        const val ACTION_AWAY = "dev.wahh.alarmshell.widget.AWAY"
        const val ACTION_DISARM = "dev.wahh.alarmshell.widget.DISARM"

        private val executor = Executors.newSingleThreadExecutor()
        private val clock = DateTimeFormatter.ofPattern("HH:mm:ss")

        @Volatile private var lastRefreshAt = 0L
        @Volatile private var refreshing = false

        fun updateAll(context: Context) {
            renderAll(context)
            refreshFromCloud(context)
        }

        /** Fast path: draw whatever state we already have. */
        private fun renderAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val app = context.applicationContext as? AlarmApplication ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, ArmWidget::class.java))
            if (ids.isEmpty()) return
            val state = app.armState.read()
            ids.forEach { id -> update(context, manager, app, id, state) }
        }

        /**
         * Reads the real arm state from the cloud (armed/disarmed). The official app's push adds
         * the stay/away detail on top of it. Debounced so periodic updates and taps cannot storm.
         */
        private fun refreshFromCloud(
            context: Context,
            force: Boolean = false,
        ) {
            val app = context.applicationContext as? AlarmApplication ?: return
            val now = System.currentTimeMillis()
            if (refreshing || (!force && now - lastRefreshAt < 30_000L)) return
            refreshing = true
            lastRefreshAt = now
            executor.execute {
                try {
                    val session = app.sessions.read() ?: return@execute
                    val fresh = app.client.ensureFresh(session)
                    val cloud = app.client.readArmState(fresh, app.armState.serial, app.armState.enUser)
                    if (fresh != session) app.sessions.write(fresh)
                    val current = app.armState.read()
                    // The cloud only knows armed/disarmed; keep a more specific push-derived
                    // STAY/AWAY while the cloud still agrees the panel is armed.
                    val specific = current.source == "notification" &&
                        (current.arming == Arming.STAY || current.arming == Arming.AWAY)
                    if (!(specific && cloud.raw == "armed")) app.armState.write(cloud)
                } catch (_: Exception) {
                    // Keep the last known state; the widget already shows its source honestly.
                } finally {
                    refreshing = false
                    renderAll(context)
                }
            }
        }

        private fun update(
            context: Context,
            manager: AppWidgetManager,
            app: AlarmApplication,
            id: Int,
            state: dev.wahh.alarmshell.core.ArmState,
        ) {
            val options = manager.getAppWidgetOptions(id) ?: android.os.Bundle()
            val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250).coerceIn(180, 520).toFloat()
            val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110).coerceIn(80, 400).toFloat()
            val views = RemoteViews(context.packageName, R.layout.widget_arm)
            val bitmap = AlarmBoardRenderer.render(context, app.theme, state, app.armState.serial, widthDp, heightDp)
            views.setImageViewBitmap(R.id.widget_board, bitmap)
            views.setOnClickPendingIntent(R.id.widget_hit_open, openApp(context))
            views.setOnClickPendingIntent(R.id.widget_hit_stay, action(context, ACTION_STAY))
            views.setOnClickPendingIntent(R.id.widget_hit_away, action(context, ACTION_AWAY))
            views.setOnClickPendingIntent(R.id.widget_hit_disarm, action(context, ACTION_DISARM))
            manager.updateAppWidget(id, views)
        }

        private fun action(
            context: Context,
            action: String,
        ): PendingIntent {
            val intent = Intent(context, ArmWidget::class.java).setAction(action)
            return PendingIntent.getBroadcast(
                context,
                action.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun openApp(context: Context): PendingIntent {
            val launch = context.packageManager.getLaunchIntentForPackage(NotificationInterpreterPackage)
            val intent = launch ?: Intent(Intent.ACTION_MAIN)
            return PendingIntent.getActivity(
                context,
                42,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private const val NotificationInterpreterPackage = "com.connect.enduser"
    }
}
