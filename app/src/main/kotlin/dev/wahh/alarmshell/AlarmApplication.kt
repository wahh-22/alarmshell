package dev.wahh.alarmshell

import android.app.Application
import android.os.Handler
import android.os.Looper
import dev.wahh.alarmshell.core.HikClient
import dev.wahh.alarmshell.core.HttpsTransport
import dev.wahh.alarmshell.core.PhantomTheme
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Composition root: the UI never builds HTTP clients, reads Keystore records or logs secrets. */
class AlarmApplication : Application() {
    lateinit var sessions: SecureSessionStore
        private set
    lateinit var armState: ArmStateStore
        private set

    /** Read-only cloud client. There is no control client to configure. */
    val client: HikClient by lazy { HikClient(HttpsTransport()) }
    val theme: PhantomTheme by lazy { PhantomTheme.default() }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        sessions = SecureSessionStore(this)
        armState = ArmStateStore(this)
        // Reconcile launcher-cached board with the stored state after process death or an update.
        ArmWidget.updateAll(this)
    }

    /**
     * Every cloud call leaves the main thread. Callers must treat [complete] as main-thread only
     * and must not log the failure: cloud bodies can contain account identifiers.
     */
    fun <T> background(
        work: () -> T,
        complete: (Result<T>) -> Unit,
    ) {
        check(Looper.myLooper() == Looper.getMainLooper())
        executor.execute {
            val result =
                try {
                    Result.success(work())
                } catch (error: Throwable) {
                    Result.failure(error)
                }
            main.post { complete(result) }
        }
    }
}
