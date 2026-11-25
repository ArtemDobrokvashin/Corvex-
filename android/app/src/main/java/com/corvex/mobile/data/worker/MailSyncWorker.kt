package com.corvex.mobile.data.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.corvex.mobile.R
import com.corvex.mobile.data.imap.ImapMailService
import com.corvex.mobile.data.security.TokenStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

/**
 * Фоновая синхронизация входящих.
 *
 * Было: `doWork()` возвращал `Result.success()` без единого запроса — задача
 * существовала, но ничего не делала, и уведомлений о новых письмах не приходило
 * никогда. Теперь раз в 15 минут (минимум, который разрешает WorkManager)
 * проверяется INBOX, а о новых письмах приходит уведомление.
 *
 * Что важно знать про ограничения:
 *  - это **не push**: письмо придёт с задержкой до 15 минут, IDLE не используется;
 *  - WorkManager не запустит задачу, если система в глубоком сне и не разрешила
 *    исключение из оптимизации батареи. Для «пришло сразу» нужен FCM, а его в
 *    проекте нет;
 *  - уведомление показывается, только если пользователь дал разрешение на
 *    уведомления (Android 13+). Без разрешения синхронизация всё равно идёт.
 */
class MailSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    /**
     * Зависимости берём через EntryPoint, а не `@HiltWorker`: тот требует ещё
     * `hilt-work` и настройку `HiltWorkerFactory` в Application. Для одной
     * периодической задачи это лишний слой, который к тому же нельзя проверить
     * без сборки.
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SyncDependencies {
        fun imapMailService(): ImapMailService
        fun tokenStore(): TokenStore
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(
            applicationContext,
            SyncDependencies::class.java
        )
        val tokenStore = deps.tokenStore()
        val imapMailService = deps.imapMailService()
        if (tokenStore.imapEmail.isNullOrBlank()) return Result.success()

        val previous = previousKnown(applicationContext)
        val result = imapMailService.fetchFoldersAndInbox("INBOX")
        return result.fold(
            onSuccess = { (_, mails) ->
                val unread = mails.count { !it.isRead }
                rememberKnown(applicationContext, mails.map { it.id }.toSet(), unread)
                notifyIfNew(applicationContext, previous.totalUnread, unread)
                Result.success()
            },
            onFailure = { error ->
                // Сеть пропала — не ошибка пользователя: WorkManager повторит.
                if (runAttemptCount < 3) Result.retry() else Result.failure()
            }
        )
    }

    private fun notifyIfNew(context: Context, before: Int, after: Int) {
        if (after <= before) return
        val added = after - before
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Новые письма",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "Приходят при фоновой проверке ящика" }
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Новые письма: $added")
            .setContentText("Откройте Corvex, чтобы прочитать")
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun previousKnown(context: Context): Known {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Known(
            ids = prefs.getStringSet(KEY_IDS, emptySet()).orEmpty(),
            totalUnread = prefs.getInt(KEY_UNREAD, 0)
        )
    }

    private fun rememberKnown(context: Context, ids: Set<String>, unread: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_IDS, ids)
            .putInt(KEY_UNREAD, unread)
            .putLong(KEY_LAST_RUN, System.currentTimeMillis())
            .apply()
    }

    private data class Known(val ids: Set<String>, val totalUnread: Int)

    companion object {
        private const val WORK_NAME = "corvex_mail_periodic_sync"
        private const val PREFS = "corvex_sync"
        private const val KEY_IDS = "known_ids"
        private const val KEY_UNREAD = "unread"
        private const val KEY_LAST_RUN = "last_run"
        private const val CHANNEL_ID = "corvex_mail"
        private const val NOTIFICATION_ID = 1001

        /**
         * 15 минут — минимальный интервал периодической задачи в WorkManager.
         * Чаще нельзя: система отклонит запрос.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<MailSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
