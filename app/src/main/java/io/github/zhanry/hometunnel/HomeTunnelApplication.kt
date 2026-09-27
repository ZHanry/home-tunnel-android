package io.github.zhanry.hometunnel

import android.app.Application
import io.github.zhanry.hometunnel.repository.HomeTunnelRepository
import io.github.zhanry.hometunnel.storage.SecureStateStore
import io.github.zhanry.hometunnel.ui.theme.ThemePreferences

class HomeTunnelApplication : Application() {
    lateinit var repository: HomeTunnelRepository
        private set

    override fun onCreate() {
        super.onCreate()
        ThemePreferences.applyStored(this)
        repository = HomeTunnelRepository(this, SecureStateStore(this))
    }
}
