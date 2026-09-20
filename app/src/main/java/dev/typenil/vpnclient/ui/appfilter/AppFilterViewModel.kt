package dev.typenil.vpnclient.ui.appfilter

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.typenil.vpnclient.core.vpn.PerAppMode
import dev.typenil.vpnclient.data.settings.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One row in the picker; icon resolved lazily by the UI. */
data class AppEntry(
    val packageName: String,
    val label: String,
)

data class AppFilterUiState(
    val mode: PerAppMode = PerAppMode.ALL,
    val selected: Set<String> = emptySet(),
    val apps: List<AppEntry> = emptyList(),
    val query: String = "",
    val loading: Boolean = true,
) {
    val filtered: List<AppEntry>
        get() = if (query.isBlank()) apps
        else apps.filter {
            it.label.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
}

@HiltViewModel
class AppFilterViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val apps = MutableStateFlow<List<AppEntry>>(emptyList())
    private val loading = MutableStateFlow(true)

    val uiState: StateFlow<AppFilterUiState> = combine(
        settings.perAppMode,
        settings.perAppPackages,
        apps,
        query,
        loading,
    ) { mode, selected, appList, q, isLoading ->
        AppFilterUiState(
            mode = mode,
            selected = selected,
            apps = appList,
            query = q,
            loading = isLoading,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppFilterUiState(),
    )

    init {
        viewModelScope.launch {
            try {
                apps.value = loadLauncherApps()
            } finally {
                loading.value = false
            }
        }
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun setMode(mode: PerAppMode) {
        viewModelScope.launch { settings.setPerAppMode(mode) }
    }

    fun toggle(packageName: String) {
        // Atomic in the repository — rapid taps must not drop each other.
        viewModelScope.launch { settings.togglePerAppPackage(packageName) }
    }

    /** Apps with a launcher entry, sorted by label. Our own package is
     *  omitted — it always bypasses the tunnel, so a checkbox would lie. */
    private suspend fun loadLauncherApps(): List<AppEntry> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(
                intent, PackageManager.ResolveInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        resolved
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .filter { it.first != context.packageName }
            .sortedBy { it.second.lowercase() }
            .map { (pkg, label) -> AppEntry(pkg, label) }
    }

    fun loadIcon(packageName: String): Drawable? =
        runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
}
