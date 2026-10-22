package com.bright.onepluscare.firewall

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.bright.onepluscare.R
import com.bright.onepluscare.core.dns.Dns
import com.bright.onepluscare.core.dns.DnsPolicy
import com.bright.onepluscare.core.dns.DnsVerdict
import com.bright.onepluscare.core.dns.Packets
import com.bright.onepluscare.core.dns.UdpPacket
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * On-device ad/tracker blocking. A local VPN whose only route is its own DNS address, so apps' normal
 * traffic never enters the tunnel; only DNS queries do. Blocked hosts get NXDOMAIN; other queries are
 * forwarded unchanged to the current network's own DNS servers. Queries are not logged or stored: only
 * counters and the most-blocked hosts are kept in memory while it runs. This app is excluded from the
 * tunnel so its forwarding sockets go straight to the network.
 */
class DnsFilterVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var reader: Thread? = null
    private var out: FileOutputStream? = null
    @Volatile private var policy: DnsPolicy? = null
    private val pool = ThreadPoolExecutor(2, 8, 30, TimeUnit.SECONDS, LinkedBlockingQueue(256), ThreadPoolExecutor.DiscardPolicy())
    private val queries = AtomicInteger()
    private val blocked = AtomicInteger()
    private val top = ConcurrentHashMap<String, AtomicInteger>()
    @Volatile private var lastPublish = 0L
    @Volatile private var noUpstream = false
    @Volatile private var upstream: Pair<Long, List<InetAddress>>? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        val store = AdBlockStore(this)
        val p = store.policy()
        if (store.info.value?.entries ?: 0 == 0) {
            shutdown()
            return START_NOT_STICKY
        }
        policy = p
        if (intent?.action == ACTION_RELOAD && tun != null) return START_STICKY
        return if (establish()) START_STICKY else {
            shutdown()
            START_NOT_STICKY
        }
    }

    private fun establish(): Boolean = synchronized(this) {
        val builder = Builder()
            .setSession(getString(R.string.ab_session_name))
            .addAddress(TUN_V4, 32)
            .addDnsServer(DNS_V4)
            .addRoute(DNS_V4, 32)
            .addAddress(TUN_V6, 128)
            .addDnsServer(DNS_V6)
            .addRoute(DNS_V6, 128)
            .setBlocking(true)
        runCatching { builder.addDisallowedApplication(packageName) }
        val fd = try {
            builder.establish()
        } catch (_: Exception) {
            null
        } ?: return false
        closeTunnel()
        tun = fd
        out = FileOutputStream(fd.fileDescriptor)
        reader = Thread({ readLoop(fd) }, "dns-filter").apply { isDaemon = true; start() }
        publish(force = true)
        true
    }

    private fun readLoop(fd: ParcelFileDescriptor) {
        val buf = ByteArray(32 * 1024)
        try {
            FileInputStream(fd.fileDescriptor).use { input ->
                while (!Thread.currentThread().isInterrupted) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    val pkt = Packets.parseUdp(buf, n) ?: continue
                    if (pkt.dstPort != 53 || !isOurDns(pkt)) continue
                    handle(pkt)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun isOurDns(p: UdpPacket): Boolean = if (p.ipVersion == 4) p.dst.contentEquals(DNS_V4_BYTES) else p.dst.contentEquals(DNS_V6_BYTES)

    private fun handle(pkt: UdpPacket) {
        val q = Dns.parseQuery(pkt.payload) ?: return
        queries.incrementAndGet()
        val verdict = policy?.decide(q.name) ?: DnsVerdict.ALLOW
        if (verdict == DnsVerdict.BLOCK) {
            blocked.incrementAndGet()
            if (top.size < 500 || top.containsKey(q.name)) top.getOrPut(q.name) { AtomicInteger() }.incrementAndGet()
            Dns.errorReply(pkt.payload, Dns.RCODE_NXDOMAIN)?.let { write(Packets.buildReply(pkt, it)) }
            publish(force = false)
            return
        }
        pool.execute { forward(pkt) }
        publish(force = false)
    }

    private fun forward(pkt: UdpPacket) {
        val servers = upstreamServers()
        noUpstream = servers.isEmpty()
        for (server in servers) {
            try {
                DatagramSocket().use { s ->
                    protect(s)
                    s.soTimeout = 4_000
                    s.send(DatagramPacket(pkt.payload, pkt.payload.size, InetSocketAddress(server, 53)))
                    val resp = ByteArray(4096)
                    val dp = DatagramPacket(resp, resp.size)
                    s.receive(dp)
                    write(Packets.buildReply(pkt, resp.copyOf(dp.length)))
                    return
                }
            } catch (_: Exception) {
                // try the next server
            }
        }
        Dns.errorReply(pkt.payload, Dns.RCODE_SERVFAIL)?.let { write(Packets.buildReply(pkt, it)) }
    }

    /** The real network's DNS servers (this app is outside the tunnel, so its active network is the real one). */
    private fun upstreamServers(): List<InetAddress> {
        val now = SystemClock.elapsedRealtime()
        upstream?.let { (at, list) -> if (now - at < 10_000) return list }
        val cm = getSystemService(ConnectivityManager::class.java)
        val list = cm?.activeNetwork?.let { cm.getLinkProperties(it)?.dnsServers }.orEmpty()
            .filter { a -> a.hostAddress != DNS_V4 && !a.address.contentEquals(DNS_V6_BYTES) }
        upstream = now to list
        return list
    }

    private fun write(packet: ByteArray) {
        val o = out ?: return
        synchronized(o) { runCatching { o.write(packet) } }
    }

    private fun publish(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastPublish < 1_000) return
        lastPublish = now
        AdBlockStore.status.value = AdBlockStatus(
            running = tun != null,
            queries = queries.get(),
            blocked = blocked.get(),
            topBlocked = top.entries.map { it.key to it.value.get() }.sortedByDescending { it.second }.take(20),
            noUpstream = noUpstream,
        )
    }

    private fun closeTunnel() {
        reader?.interrupt()
        reader = null
        runCatching { tun?.close() }
        tun = null
        out = null
    }

    private fun shutdown() {
        synchronized(this) { closeTunnel() }
        AdBlockStore.status.value = AdBlockStatus()
        stopSelf()
    }

    override fun onRevoke() = shutdown()

    override fun onDestroy() {
        synchronized(this) { closeTunnel() }
        pool.shutdownNow()
        AdBlockStore.status.value = AdBlockStatus()
        super.onDestroy()
    }

    companion object {
        private const val TUN_V4 = "10.111.223.1"
        const val DNS_V4 = "10.111.223.2"
        private const val TUN_V6 = "fd00:2:fd00:2:fd00:2:fd00:1"
        private const val DNS_V6 = "fd00:2:fd00:2:fd00:2:fd00:2"
        private val DNS_V4_BYTES = byteArrayOf(10, 111, 223.toByte(), 2)
        private val DNS_V6_BYTES = byteArrayOf(0xfd.toByte(), 0, 0, 2, 0xfd.toByte(), 0, 0, 2, 0xfd.toByte(), 0, 0, 2, 0xfd.toByte(), 0, 0, 2)
        private const val ACTION_START = "com.bright.onepluscare.adblock.START"
        private const val ACTION_RELOAD = "com.bright.onepluscare.adblock.RELOAD"
        private const val ACTION_STOP = "com.bright.onepluscare.adblock.STOP"

        fun start(context: Context) {
            context.startService(Intent(context, DnsFilterVpnService::class.java).setAction(ACTION_START))
        }

        fun reload(context: Context) {
            context.startService(Intent(context, DnsFilterVpnService::class.java).setAction(ACTION_RELOAD))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, DnsFilterVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}
