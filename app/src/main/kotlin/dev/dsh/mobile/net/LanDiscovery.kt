package dev.dsh.mobile.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.util.concurrent.TimeUnit

object DiscoveryPlanner {
    /**
     * Hosts worth probing. A prefix shorter than /24 is narrowed to the /24
     * around the phone so one tap never sweeps a corporate /16.
     */
    fun hostsFor(ip: String, prefixLength: Int): List<String> {
        val numbers = ip.split('.').map { it.toIntOrNull() ?: return emptyList() }
        if (numbers.size != 4 || numbers.any { it !in 0..255 }) return emptyList()
        val own = (numbers[0].toLong() shl 24) or
            (numbers[1].toLong() shl 16) or
            (numbers[2].toLong() shl 8) or
            numbers[3].toLong()

        var prefix = prefixLength.coerceIn(0, 32)
        if (prefix < 24) prefix = 24
        if (prefix >= 31) return listOf(ip)
        val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        val network = own and mask
        val size = 1L shl (32 - prefix)
        val first = network + 1
        val last = network + size - 2
        val result = ArrayList<String>()
        var value = first
        while (value <= last) {
            if (value != own) result.add(toIpv4(value))
            value += 1
        }
        return result
    }

    private fun toIpv4(value: Long): String =
        "${(value ushr 24) and 0xFF}.${(value ushr 16) and 0xFF}.${(value ushr 8) and 0xFF}.${value and 0xFF}"
}

class LanDiscovery(context: Context) {
    private val appContext = context.applicationContext
    private val client = OkHttpClient.Builder()
        .connectTimeout(400, TimeUnit.MILLISECONDS)
        .readTimeout(700, TimeUnit.MILLISECONDS)
        .callTimeout(1200, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    data class Candidate(val host: String, val baseUrl: String)

    suspend fun discover(): List<Candidate> = withContext(Dispatchers.IO) {
        val subnets = localSubnets()
        val hosts = subnets.firstOrNull()?.let { (ip, prefix) -> DiscoveryPlanner.hostsFor(ip, prefix) }
            ?: return@withContext emptyList()
        if (hosts.isEmpty()) return@withContext emptyList()
        val semaphore = Semaphore(32)
        coroutineScope {
            hosts.map { host ->
                async(Dispatchers.IO) {
                    semaphore.withPermit { probe(host) }
                }
            }.awaitAll().filterNotNull().sortedBy { it.host }
        }
    }

    fun localSubnets(): List<Pair<String, Int>> {
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val result = LinkedHashSet<Pair<String, Int>>()
        for (network in manager.allNetworks) {
            val capabilities = manager.getNetworkCapabilities(network) ?: continue
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            ) {
                continue
            }
            val link = manager.getLinkProperties(network) ?: continue
            for (address in link.linkAddresses) {
                val host = address.address.hostAddress ?: continue
                if (address.address is java.net.Inet4Address && !address.address.isLoopbackAddress) {
                    result.add(host to address.prefixLength)
                }
            }
        }
        return result.toList()
    }

    private fun probe(host: String): Candidate? {
        val baseUrl = "http://$host:3081"
        try {
            val request = Request.Builder()
                .url("$baseUrl/")
                .get()
                .header("Accept", "text/html")
                .build()
            client.newCall(request).execute().use { response ->
                val body = readCapped(response.body?.source(), 8L * 1024)
                if (dev.dsh.mobile.web.PageDetector.looksLikeDsh(body)) {
                    return Candidate(host, baseUrl)
                }
            }
        } catch (_: Exception) {
            // Not a DSH host, or not reachable.
        }
        return null
    }

    private fun readCapped(source: okio.BufferedSource?, limit: Long): String {
        if (source == null) return ""
        val buffer = Buffer()
        while (buffer.size < limit) {
            val read = source.read(buffer, limit - buffer.size)
            if (read == -1L) break
        }
        return buffer.readUtf8()
    }
}
