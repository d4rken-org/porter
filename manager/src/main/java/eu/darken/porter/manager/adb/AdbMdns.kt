package eu.darken.porter.manager.adb

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
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

@RequiresApi(Build.VERSION_CODES.R)
class AdbMdns(
    context: Context, private val serviceType: String,
    private val observer: Observer<Pair<String, Int>>
) {
    private val handler = Handler(Looper.getMainLooper())
    private val nsdManager = context.getSystemService(NsdManager::class.java)
    private val client = Integer.toHexString(System.identityHashCode(nsdManager))
    private var listener: DiscoveryListener? = null

    fun start() {
        if (Looper.myLooper() != handler.looper) { handler.post { start() }; return }
        if (listener != null) return
        val next = DiscoveryListener()
        listener = next
        Log.i(TAG, "Discovery start: type=$serviceType listener=${next.id} client=$client")
        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, next)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot start discovery: listener=${next.id}", e)
            listener = null
            observer.onChanged("" to -1)
        }
    }

    fun stop() {
        if (Looper.myLooper() != handler.looper) { handler.post { stop() }; return }
        val previous = listener ?: return
        Log.i(TAG, "Discovery stop: type=$serviceType listener=${previous.id} registered=${previous.registered}")
        listener = null
        previous.scope.cancel()
        if (previous.registered) previous.stopDiscovery()
        // Registration may still be pending. Its callback will stop the old listener.
    }

    private inner class DiscoveryListener : NsdManager.DiscoveryListener {
        val id = "L${listenerIds.incrementAndGet()}"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var registered = false
        private var serviceName: String? = null
        private val found = mutableMapOf<String, NsdServiceInfo>()
        private val ordinals = mutableMapOf<String?, Int>()
        private val sightings = mutableMapOf<String?, Int>()
        private var resolves = 0
        private val active get() = listener === this

        private fun label(name: String?) = "S${ordinals.getOrPut(name) { ordinals.size + 1 }}"

        private fun staleReason(info: NsdServiceInfo) = when {
            !active -> "inactive"
            found[info.serviceName] == null -> "lost"
            found[info.serviceName] !== info -> "replaced"
            else -> null
        }

        private fun logOutcome(service: String, resolve: String, result: String) {
            Log.i(TAG, "Resolve outcome: listener=$id service=$service resolve=$resolve result=$result")
        }

        fun stopDiscovery() {
            try {
                nsdManager.stopServiceDiscovery(this)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot stop discovery: listener=$id", e)
            }
        }

        override fun onDiscoveryStarted(serviceType: String) {
            handler.post {
                Log.i(TAG, "Discovery started: listener=$id active=$active")
                registered = true
                if (!active) stopDiscovery()
            }
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            handler.post {
                Log.w(TAG, "Discovery failed: $errorCode listener=$id active=$active")
                if (active) {
                    listener = null
                    observer.onChanged("" to -1)
                }
            }
        }

        override fun onDiscoveryStopped(serviceType: String) {
            handler.post {
                Log.i(TAG, "Discovery stopped: listener=$id active=$active")
                registered = false
                if (active) {
                    listener = null
                    observer.onChanged("" to -1)
                }
            }
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "Stopping discovery failed: $errorCode listener=$id")
        }

        override fun onServiceFound(info: NsdServiceInfo) {
            handler.post {
                if (!active) return@post
                found[info.serviceName] = info
                val service = label(info.serviceName)
                val seen = (sightings[info.serviceName] ?: 0) + 1
                sightings[info.serviceName] = seen
                Log.i(TAG, "Service found: listener=$id service=$service seen=$seen")
                val resolve = "R${++resolves}"
                val request = "listener=$id client=$client service=$service resolve=$resolve"
                Log.i(TAG, "Resolve issued: $request")
                try {
                    nsdManager.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            Log.w(TAG, "Resolving service failed: $errorCode $request")
                        }
                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            Log.i(TAG, "Service resolved: $request host=${hostKind(resolved.host)} port=${resolved.port}")
                            handler.post resolved@{
                                staleReason(info)?.let { logOutcome(service, resolve, it); return@resolved }
                                val host = resolved.host?.hostAddress
                                    ?: run { logOutcome(service, resolve, "no-host"); return@resolved }
                                scope.launch {
                                    val rejected = withContext(Dispatchers.IO) {
                                        val local = NetworkInterface.getNetworkInterfaces().asSequence().any { network ->
                                            network.inetAddresses.asSequence().any { it.hostAddress == host }
                                        }
                                        when {
                                            !local -> "no-interface"
                                            !isPortAvailable(host, resolved.port) -> "port-closed"
                                            else -> null
                                        }
                                    }
                                    val result = rejected ?: staleReason(info)?.let { "recheck-$it" }
                                    logOutcome(service, resolve, result ?: "accepted")
                                    if (result == null) {
                                        serviceName = info.serviceName
                                        Log.i(TAG, "Service accepted: type=$serviceType port=${resolved.port}")
                                        observer.onChanged(host to resolved.port)
                                    }
                                }
                            }
                        }
                    })
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot resolve service: $request", e)
                }
            }
        }

        override fun onServiceLost(info: NsdServiceInfo) {
            handler.post {
                if (!active) return@post
                Log.i(TAG, "Service lost: listener=$id service=${label(info.serviceName)}")
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

    private fun hostKind(address: InetAddress?): String {
        address ?: return "none"
        val family = if (address is Inet4Address) "ipv4" else "ipv6"
        val range = when {
            address.isLoopbackAddress -> "loopback"
            address.isLinkLocalAddress -> "link-local"
            address.isSiteLocalAddress -> "site-local"
            else -> "other"
        }
        return "$family-$range"
    }

    companion object {
        const val TLS_CONNECT = "_adb-tls-connect._tcp"
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"
        const val TAG = "AdbMdns"
        private val listenerIds = AtomicInteger()
    }
}
