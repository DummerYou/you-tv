package com.youtv.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.youtv.app.player.PlayerController
import com.youtv.app.data.PlaylistTextDecoder
import com.youtv.app.server.RemoteConfigServer
import com.youtv.app.ui.MainViewModel
import com.youtv.app.ui.Overlay
import com.youtv.app.ui.TvApp
import com.youtv.app.ui.theme.YouTvTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class ComposeMainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private lateinit var playerController: PlayerController
    private var remoteServer: RemoteConfigServer? = null
    private var remoteTimeout: Job? = null
    private var remoteAddress by mutableStateOf<String?>(null)
    private val openPlaylist = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        contentResolver.openInputStream(uri)?.use { input ->
            viewModel.importPlaylist(PlaylistTextDecoder.decode(input.readBytes()))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val settings = runBlocking { (application as YouTvApplication).container.settingsRepository.settings.first() }
        com.youtv.app.requests.HttpClient.configureProxy(settings.proxy)
        playerController = PlayerController(
            context = this,
            softDecode = settings.softDecode,
            onSourceSucceeded = { channelId, sourceIndex ->
                lifecycleScope.launch { viewModel.rememberSuccessfulSource(channelId, sourceIndex) }
            },
        )
        setContent {
            YouTvTheme {
                TvApp(
                    viewModel,
                    playerController,
                    remoteAddress = remoteAddress,
                    onImportFile = { openPlaylist.launch(arrayOf("text/*", "application/json", "audio/x-mpegurl")) },
                    onExit = ::finish,
                )
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    if (state.overlay == Overlay.SETTINGS) startRemoteServer() else stopRemoteServer()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (this::playerController.isInitialized) playerController.resume()
    }

    override fun onPause() {
        if (this::playerController.isInitialized) playerController.pause()
        super.onPause()
    }

    override fun onDestroy() {
        stopRemoteServer()
        if (this::playerController.isInitialized) playerController.release()
        super.onDestroy()
    }

    private fun startRemoteServer() {
        if (remoteServer != null) return
        val host = PortUtil.lan() ?: "0.0.0.0"
        val server = RemoteConfigServer(
            context = this,
            host = host,
            settingsProvider = { viewModel.state.value.settings },
            onImport = { content -> runOnUiThread { viewModel.importPlaylist(content) } },
            onImportUrl = viewModel::importFromUrl,
            onProxy = viewModel::setProxy,
            onEpg = viewModel::setEpgUrl,
            onDefaultChannel = viewModel::setDefaultChannel,
            onPreference = viewModel::setPreference,
        )
        if (runCatching { server.start() }.isFailure) return
        remoteServer = server
        remoteAddress = "http://$host:${RemoteConfigServer.PORT}"
        remoteTimeout?.cancel()
        remoteTimeout = lifecycleScope.launch {
            delay(RemoteConfigServer.SESSION_MILLIS)
            stopRemoteServer()
        }
    }

    private fun stopRemoteServer() {
        remoteTimeout?.cancel()
        remoteTimeout = null
        remoteServer?.stop()
        remoteServer = null
        remoteAddress = null
    }
}
