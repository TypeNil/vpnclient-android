package dev.typenil.vpnclient.core.vpn

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import dev.typenil.vpnclient.core.engine.VpnEngineFactory
import dev.typenil.vpnclient.di.EngineFactoryModule
import dev.typenil.vpnclient.di.NodeConfigProviderModule
import dev.typenil.vpnclient.di.ServiceControlModule
import dev.typenil.vpnclient.di.TunProviderModule
import javax.inject.Singleton

/**
 * `@TestInstallIn` replacements for the VPN-path bindings the lifecycle
 * harness swaps out. Each replaces exactly one small production module
 * (extracted in `di/AppModule.kt` for this purpose) so the rest of the real
 * object graph — `ConnectionManager`, `SettingsRepository`, Room — stays
 * production-wired.
 *
 * The provided fakes are `@Singleton`, so the instance the service injects
 * is the same object a test reaches via `EntryPointAccessors`.
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [EngineFactoryModule::class],
)
object TestEngineFactoryModule {
    @Provides
    @Singleton
    fun provideEngineFactory(): VpnEngineFactory = FakeVpnEngineFactory()
}

@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [TunProviderModule::class],
)
object TestTunProviderModule {
    @Provides
    @Singleton
    fun provideTunProvider(): TunProvider = FakeTunProvider()
}

@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [NodeConfigProviderModule::class],
)
object TestNodeConfigProviderModule {
    @Provides
    @Singleton
    fun provideNodeConfigProvider(): NodeConfigProvider = FakeNodeConfigProvider()
}

@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [ServiceControlModule::class],
)
object TestServiceControlModule {
    @Provides
    @Singleton
    fun provideServiceControl(@ApplicationContext context: Context): ServiceControl =
        FakeServiceControl(context)
}
