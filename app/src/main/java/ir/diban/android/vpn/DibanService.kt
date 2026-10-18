package ir.diban.android.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import ir.diban.android.Bridge
import ir.diban.android.MainActivity
import ir.diban.android.R
import ir.diban.android.Session
import ir.diban.android.Session.State
import ir.diban.android.Session.Tone
import java.io.File
import java.util.concurrent.Executors

/**
 * Foreground service that owns one connection:
 *
 *   apps ──► TUN ──► hev-socks5-tunnel ──► 127.0.0.1:1080 (Diban engine, Python) ──► Google front ──► relay
 *
 * In "proxy only" mode the TUN part is skipped and only the local proxy listens.
 * The service's own process is excluded from the VPN, so the engine's sockets to Google
 * never loop back into the tunnel.
 */
class DibanService : VpnService() {

    companion object {
        const val ACTION_START = "ir.diban.android.action.START"
        const val ACTION_STOP = "ir.diban.android.action.STOP"
        const val EXTRA_VPN = "vpn"

        private const val CHANNEL_ID = "diban_status"
        private const val NOTIF_ID = 1

        // Addresses inside the tunnel.  198.18.0.0/15 is reserved for benchmarking (RFC 2544),
        // so it never collides with real networks.
        private const val TUN_ADDR = "198.18.0.1"
        private const val DNS_ADDR = "198.18.0.2"
        private const val MTU = 1500
    }

    private val worker = Executors.newSingleThreadExecutor()
    private var tun: ParcelFileDescriptor? = null
    private var tunnelStarted = false
    @Volatile private var active = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                goForeground(getString(R.string.notif_connecting))
                val vpn = intent.getBooleanExtra(EXTRA_VPN, true)
                worker.execute { connect(vpn) }
            }
            ACTION_STOP -> worker.execute { disconnect(exit = true) }
            else -> stopSelf() // e.g. system-initiated start we did not ask for
        }
        return START_NOT_STICKY
    }

    /** The user turned the VPN off from system settings (or another VPN took over). */
    override fun onRevoke() {
        worker.execute { disconnect(exit = true) }
    }

    override fun onDestroy() {
        worker.execute { teardown() }
        worker.shutdown()
        super.onDestroy()
    }

    // ───────────── connect / disconnect ─────────────
    private fun connect(vpn: Boolean) {
        if (active) return
        active = true
        Session.vpnMode = vpn
        try {
            Session.set(State.CONNECTING, getString(R.string.st_starting), Tone.MUTED, probeFailed = false)
            val err = Bridge.start()
            if (err.isNotEmpty()) throw IllegalStateException(err)

            if (vpn) {
                Session.set(status = getString(R.string.st_tunnel), tone = Tone.MUTED)
                openTunnel()
            }

            Session.set(status = getString(R.string.st_probe), tone = Tone.MUTED)
            val ok = Bridge.probe()
            updateNotification(getString(if (vpn) R.string.notif_connected_vpn else R.string.notif_connected_proxy))
            if (ok) {
                Session.set(State.CONNECTED, getString(if (vpn) R.string.st_ok_vpn else R.string.st_ok_proxy),
                    Tone.GOOD, probeFailed = false)
            } else {
                Session.set(State.CONNECTED, getString(R.string.st_probe_failed), Tone.WARN, probeFailed = true)
            }
        } catch (e: Exception) {
            teardown()
            Session.set(State.IDLE, getString(R.string.st_failed, (e.message ?: e.javaClass.simpleName).trimEnd('.')),
                Tone.BAD)
            finishService()
        }
    }

    private fun disconnect(exit: Boolean) {
        teardown()
        Session.set(State.IDLE, getString(R.string.st_disconnected), Tone.MUTED, probeFailed = false)
        if (exit) finishService()
    }

    private fun teardown() {
        active = false
        try {
            if (tunnelStarted) TProxyService.TProxyStopService()
        } catch (_: Throwable) {
        }
        tunnelStarted = false
        try {
            tun?.close()
        } catch (_: Exception) {
        }
        tun = null
        try {
            Bridge.stop()
        } catch (_: Throwable) {
        }
    }

    private fun finishService() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ───────────── tunnel ─────────────
    private fun openTunnel() {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(MTU)
            .addAddress(TUN_ADDR, 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(DNS_ADDR)
        // Our own process (the Python engine) must reach Google directly, not through the tunnel.
        try {
            builder.addDisallowedApplication(packageName)
        } catch (_: Exception) {
        }
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)

        val pfd = builder.establish() ?: throw IllegalStateException(getString(R.string.err_vpn_denied))
        tun = pfd

        val conf = File(filesDir, "tun.yaml")
        conf.writeText(tunnelYaml(Bridge.socksPort()))
        if (!TProxyService.TProxyStartService(conf.absolutePath, pfd.fd)) {
            throw IllegalStateException(getString(R.string.err_tunnel))
        }
        tunnelStarted = true
    }

    /**
     * hev-socks5-tunnel config.  `mapdns` is what makes this work: apps ask our fake DNS for a
     * name, get a placeholder IP back, and when they connect to it hev hands the *hostname* to
     * the SOCKS5 proxy.  The engine routes by hostname (direct_hosts, MITM, relay), so without
     * this it would only ever see bare IPs.
     */
    private fun tunnelYaml(socksPort: Int) = """
        tunnel:
          mtu: $MTU
          ipv4: $TUN_ADDR
        socks5:
          port: $socksPort
          address: 127.0.0.1
          udp: 'udp'
        mapdns:
          address: $DNS_ADDR
          port: 53
          network: 100.64.0.0
          netmask: 255.192.0.0
          cache-size: 10000
        misc:
          log-level: warn
    """.trimIndent() + "\n"

    // ───────────── notification ─────────────
    private fun goForeground(text: String) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(text), type)
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, DibanService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.notif_disconnect), stop)
            .build()
    }
}
