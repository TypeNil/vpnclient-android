package dev.typenil.vpnclient.data.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.typenil.vpnclient.core.common.log.SecureLog
import dev.typenil.vpnclient.core.subscription.SubscriptionRefreshScheduler
import dev.typenil.vpnclient.core.subscription.SubscriptionRepository
import dev.typenil.vpnclient.core.subscription.model.RefreshPolicy
import dev.typenil.vpnclient.core.subscription.model.SubscriptionError
import dev.typenil.vpnclient.core.subscription.resolveRefreshIntervalMinutes
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "SubRefreshWork"
private const val WORK_NAME_PREFIX = "subscription-refresh-"
/** Group tag on every refresh request — lets reconcile() find orphans. */
private const val WORK_TAG = "subscription-refresh"
internal const val KEY_SUBSCRIPTION_ID = "subscription_id"

/** Total executions per period, including the first. */
private const val MAX_ATTEMPTS = 3

/** One unique periodic job per subscription; `Result.retry` only for
 *  transient failures, bounded by [MAX_ATTEMPTS]. */
class SubscriptionRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun subscriptionRepository(): SubscriptionRepository
    }

    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_SUBSCRIPTION_ID, -1L)
        if (id < 0) return Result.failure()
        val repository = EntryPointAccessors.fromApplication(
            applicationContext, Deps::class.java,
        ).subscriptionRepository()
        // The periodic entry re-checks eligibility on the CURRENT row before
        // fetching — a job that outlived a policy flip to Disabled or an
        // enabled=false toggle must not hit the network.
        val result = repository.refreshPeriodic(id)
        if (result.isSuccess) return Result.success()
        val error = result.exceptionOrNull()
        if (error is SubscriptionError.NotFound) {
            // The row is gone — cancel ourselves or the periodic job would
            // wake forever for a subscription that no longer exists.
            WorkManager.getInstance(applicationContext)
                .cancelUniqueWork(WORK_NAME_PREFIX + id)
            return Result.success()
        }
        if (error is SubscriptionError.Superseded) {
            // Nothing was fetched or written: either a newer in-flight
            // refresh owns this row, or the row's eligibility changed — the
            // setter that disabled it already cancelled this job. Either way
            // skip the run without cancelling: Superseded must never be read
            // as "row deleted".
            return Result.success()
        }
        val transient = error is SubscriptionError.Network ||
            error is SubscriptionError.Timeout
        return if (transient && runAttemptCount < MAX_ATTEMPTS - 1) {
            Result.retry()
        } else {
            // Permanent failure — lastError already recorded by the repository.
            Result.success()
        }
    }
}

@Singleton
class WorkManagerRefreshScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : SubscriptionRefreshScheduler {

    override suspend fun schedule(
        subscriptionId: Long,
        providerMinutes: Int?,
        policy: RefreshPolicy,
        userOverrideMinutes: Int,
        enabled: Boolean,
    ) {
        val wm = WorkManager.getInstance(context)
        val name = WORK_NAME_PREFIX + subscriptionId
        val minutes = resolveRefreshIntervalMinutes(
            policy, userOverrideMinutes, providerMinutes, enabled,
        )
        if (minutes == null) {
            wm.cancelUniqueWork(name)
            return
        }
        val request = PeriodicWorkRequestBuilder<SubscriptionRefreshWorker>(
            minutes, TimeUnit.MINUTES,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .setInputData(workDataOf(KEY_SUBSCRIPTION_ID to subscriptionId))
            // Group tag for orphan pruning + per-sub tag to identify it —
            // WorkInfo doesn't expose the unique-work name.
            .addTag(WORK_TAG)
            .addTag(WORK_NAME_PREFIX + subscriptionId)
            .build()
        wm.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, request)
        SecureLog.d(TAG, "scheduled refresh sub=$subscriptionId every=${minutes}m")
    }

    override fun cancel(subscriptionId: Long) {
        WorkManager.getInstance(context)
            .cancelUniqueWork(WORK_NAME_PREFIX + subscriptionId)
    }

    override suspend fun reconcile(exists: suspend (Long) -> Boolean) {
        val wm = WorkManager.getInstance(context)
        // Blocking .get() on IO — work-runtime-ktx has no ListenableFuture
        // await for queries, only for Operation. A failed query propagates:
        // defaulting to an empty list would silently skip pruning and still
        // report a completed pass.
        val infos = withContext(Dispatchers.IO) { wm.getWorkInfosByTag(WORK_TAG).get() }
        infos.forEach { info ->
            if (info.state.isFinished) return@forEach
            val subTag = info.tags.firstOrNull { it.startsWith(WORK_NAME_PREFIX) }
                ?: return@forEach
            val id = subTag.removePrefix(WORK_NAME_PREFIX).toLongOrNull()
                ?: return@forEach
            // Current-row check, not a snapshot: a subscription added after
            // the caller listed ids must not be pruned as an orphan.
            if (!exists(id)) {
                wm.cancelWorkById(info.id)
                SecureLog.i(TAG, "pruned orphan refresh job sub=$id")
            }
        }
    }
}
