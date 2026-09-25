package dev.typenil.vpnclient.di

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.typenil.vpnclient.core.engine.VpnEngineFactory
import dev.typenil.vpnclient.core.engine.singbox.SingBoxEngineFactory
import dev.typenil.vpnclient.core.subscription.SubscriptionCandidateValidator
import dev.typenil.vpnclient.core.subscription.SubscriptionExpiryNotifier
import dev.typenil.vpnclient.core.subscription.SubscriptionRefreshScheduler
import dev.typenil.vpnclient.core.subscription.SubscriptionSettings
import dev.typenil.vpnclient.core.vpn.AndroidServiceControl
import dev.typenil.vpnclient.core.vpn.ConnectionManager
import dev.typenil.vpnclient.core.vpn.NodeConfigProvider
import dev.typenil.vpnclient.core.vpn.ServiceControl
import dev.typenil.vpnclient.data.CandidateValidatorImpl
import dev.typenil.vpnclient.data.ExpiryAlertNotifier
import dev.typenil.vpnclient.data.NodeConfigProviderImpl
import dev.typenil.vpnclient.data.db.DbTransactionRunner
import dev.typenil.vpnclient.data.db.RoomDbTransactionRunner
import dev.typenil.vpnclient.data.settings.SettingsRepository
import dev.typenil.vpnclient.data.work.WorkManagerRefreshScheduler
import dev.typenil.vpnclient.data.db.AppDatabase
import dev.typenil.vpnclient.data.db.NodeDao
import dev.typenil.vpnclient.data.db.NodePreferenceDao
import dev.typenil.vpnclient.data.db.SubscriptionDao
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAppScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "vpnclient.db")
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
            )
            // Last resort only: every version jump must ship a real migration
            // (schema JSONs are committed for exactly this reason). Destructive
            // fallback loses user-entered subscriptions but beats a crash loop.
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideSubscriptionDao(db: AppDatabase): SubscriptionDao = db.subscriptionDao()

    @Provides
    fun provideNodeDao(db: AppDatabase): NodeDao = db.nodeDao()

    @Provides
    fun provideNodePreferenceDao(db: AppDatabase): NodePreferenceDao =
        db.nodePreferenceDao()

    @Provides
    @Singleton
    fun provideOkHttpClient(): okhttp3.OkHttpClient =
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            // Whole-call deadline: connect+read timeouts alone let a slow
            // trickle (or redirect chain) run forever.
            .callTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    fun provideConnectionManager(
        serviceControl: ServiceControl,
        configProvider: NodeConfigProvider,
    ): ConnectionManager = ConnectionManager(serviceControl, configProvider)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AppBindsModule {

    @Binds
    @Singleton
    abstract fun bindServiceControl(impl: AndroidServiceControl): ServiceControl

    @Binds
    @Singleton
    abstract fun bindSubscriptionCandidateValidator(
        impl: CandidateValidatorImpl,
    ): SubscriptionCandidateValidator

    @Binds
    @Singleton
    abstract fun bindSubscriptionExpiryNotifier(
        impl: ExpiryAlertNotifier,
    ): SubscriptionExpiryNotifier

    @Binds
    @Singleton
    abstract fun bindDbTransactionRunner(impl: RoomDbTransactionRunner): DbTransactionRunner

    @Binds
    @Singleton
    abstract fun bindSubscriptionRefreshScheduler(
        impl: WorkManagerRefreshScheduler,
    ): SubscriptionRefreshScheduler

    @Binds
    @Singleton
    abstract fun bindSubscriptionSettings(impl: SettingsRepository): SubscriptionSettings

    @Binds
    @Singleton
    abstract fun bindNodeConfigProvider(impl: NodeConfigProviderImpl): NodeConfigProvider

    @Binds
    @Singleton
    abstract fun bindVpnEngineFactory(impl: SingBoxEngineFactory): VpnEngineFactory
}
