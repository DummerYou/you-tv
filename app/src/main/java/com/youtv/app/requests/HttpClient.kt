package com.youtv.app.requests


import android.net.Uri
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit


object HttpClient {
    private const val TAG = "HttpClient"

    private val clientCache = ConcurrentHashMap<String, OkHttpClient>()
    private val streamingClientCache = ConcurrentHashMap<String, OkHttpClient>()
    private val probeClientCache = ConcurrentHashMap<String, OkHttpClient>()
    private val sharedDns = DnsCache()
    private val sharedConnectionPool = ConnectionPool(8, 5, TimeUnit.MINUTES)
    private val probeDispatcher = Dispatcher().apply {
        maxRequests = 2
        maxRequestsPerHost = 1
    }

    @Volatile
    private var configuredProxy: String = ""

    fun configureProxy(value: String) {
        configuredProxy = value
    }

    val okHttpClient: OkHttpClient by lazy {
        getClientWithProxy()
    }

    fun getClientWithProxy(proxy: String = configuredProxy): OkHttpClient {
        return getOrCreateClient(proxy, ClientMode.REGULAR)
    }

    /** 视频流不能使用全局 callTimeout，否则持续播放会在超时后被主动中断。 */
    fun getStreamingClientWithProxy(proxy: String = configuredProxy): OkHttpClient {
        return getOrCreateClient(proxy, ClientMode.STREAMING)
    }

    fun getProbeClientWithProxy(proxy: String = configuredProxy): OkHttpClient {
        return getOrCreateClient(proxy, ClientMode.PROBE)
    }

    private fun getOrCreateClient(proxy: String, mode: ClientMode): OkHttpClient {
        val proxySetting = proxy
        val cache = when (mode) {
            ClientMode.REGULAR -> clientCache
            ClientMode.STREAMING -> streamingClientCache
            ClientMode.PROBE -> probeClientCache
        }
        cache[proxySetting]?.let {
            return it
        }

        val builder = createBuilder(mode)
        if (proxySetting.isNotEmpty()) {
            try {
                val normalized = if ("://" in proxySetting) proxySetting else "http://$proxySetting"
                val proxyUri = Uri.parse(normalized)
                val proxyType = when (proxyUri.scheme) {
                    "http", "https" -> Proxy.Type.HTTP
                    "socks", "socks5" -> Proxy.Type.SOCKS
                    else -> null
                }
                proxyType?.let {
                    val port = proxyUri.port.takeIf { value -> value > 0 }
                        ?: if (proxyUri.scheme == "https") 443 else 80
                    builder.proxy(Proxy(it, InetSocketAddress(proxyUri.host, port)))
                    Log.i(TAG, "apply proxy ${proxyUri.scheme}://${proxyUri.host}:$port")
                }
            } catch (e: Exception) {
                Log.w(TAG, "ignore invalid proxy setting")
            }
        }

        val client = builder.build()
        cache[proxySetting] = client
        return client
    }

    private fun createBuilder(mode: ClientMode): OkHttpClient.Builder {
        return OkHttpClient.Builder()
            .dns(sharedDns)
            .connectionPool(sharedConnectionPool)
            .apply {
                if (mode == ClientMode.PROBE) dispatcher(probeDispatcher)
            }
            .connectTimeout(if (mode == ClientMode.PROBE) 2 else 8, TimeUnit.SECONDS)
            .readTimeout(if (mode == ClientMode.PROBE) 2500 else 20_000, TimeUnit.MILLISECONDS)
            .callTimeout(
                when (mode) {
                    ClientMode.STREAMING -> 0L
                    ClientMode.PROBE -> 2500L
                    ClientMode.REGULAR -> 30_000L
                },
                TimeUnit.MILLISECONDS,
            )
            .retryOnConnectionFailure(true)
    }

    private enum class ClientMode { REGULAR, STREAMING, PROBE }
}
