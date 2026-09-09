package moe.shizuku.manager.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.Observer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket

@RequiresApi(Build.VERSION_CODES.R)
class AdbMdns(
    context: Context, private val serviceType: String,
    private val observer: Observer<Pair<String, Int>>
) {
    private val handler = Handler(Looper.getMainLooper())
    private val nsdManager = context.getSystemService(NsdManager::class.java)
    private var listener: DiscoveryListener? = null

    fun start() {
        if (Looper.myLooper() != handler.looper) { handler.post { start() }; return }
        if (listener != null) return
        val next = DiscoveryListener()
        listener = next
        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, next)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot start discovery", e)
            listener = null
            observer.onChanged("" to -1)
        }
    }

    fun stop() {
        if (Looper.myLooper() != handler.looper) { handler.post { stop() }; return }
        val previous = listener ?: return
        listener = null
        previous.scope.cancel()
        if (previous.registered) previous.stopDiscovery()
        // Registration may still be pending. Its callback will stop the old listener.
    }

    private inner class DiscoveryListener : NsdManager.DiscoveryListener {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var registered = false
        private var serviceName: String? = null
        private val found = mutableMapOf<String, NsdServiceInfo>()
        private val active get() = listener === this

        fun stopDiscovery() {
            try {
                nsdManager.stopServiceDiscovery(this)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot stop discovery", e)
            }
        }

        override fun onDiscoveryStarted(serviceType: String) {
            handler.post {
                registered = true
                if (!active) stopDiscovery()
            }
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            handler.post {
                Log.w(TAG, "Discovery failed: $errorCode")
                if (active) {
                    listener = null
                    observer.onChanged("" to -1)
                }
            }
        }

        override fun onDiscoveryStopped(serviceType: String) {
            handler.post {
                registered = false
                if (active) {
                    listener = null
                    observer.onChanged("" to -1)
                }
            }
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "Stopping discovery failed: $errorCode")
        }

        override fun onServiceFound(info: NsdServiceInfo) {
            handler.post {
                if (!active) return@post
                found[info.serviceName] = info
                try {
                    nsdManager.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            Log.w(TAG, "Resolving service failed: $errorCode")
                        }
                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            handler.post resolved@{
                                if (!active || found[info.serviceName] !== info) return@resolved
                                val host = resolved.host?.hostAddress ?: return@resolved
                                scope.launch {
                                    val available = withContext(Dispatchers.IO) {
                                        NetworkInterface.getNetworkInterfaces().asSequence().any { network ->
                                            network.inetAddresses.asSequence().any { it.hostAddress == host }
                                        } && isPortAvailable(host, resolved.port)
                                    }
                                    if (available && active && found[info.serviceName] === info) {
                                        serviceName = info.serviceName
                                        observer.onChanged(host to resolved.port)
                                    }
                                }
                            }
                        }
                    })
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot resolve service", e)
                }
            }
        }

        override fun onServiceLost(info: NsdServiceInfo) {
            handler.post {
                if (!active) return@post
                found.remove(info.serviceName)
                if (info.serviceName == serviceName) {
                    serviceName = null
                    observer.onChanged("" to -1)
                }
            }
        }
    }

    private fun isPortAvailable(host: String, port: Int) = try {
        ServerSocket().use {
            it.bind(InetSocketAddress(host, port), 1)
            false
        }
    } catch (e: IOException) {
        true
    }

    companion object {
        const val TLS_CONNECT = "_adb-tls-connect._tcp"
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"
        const val TAG = "AdbMdns"
    }
}
