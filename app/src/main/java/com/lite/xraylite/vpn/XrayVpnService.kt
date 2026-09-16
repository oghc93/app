package com.lite.xraylite.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.lite.xraylite.config.ConfigParser
import com.lite.xraylite.config.ServerRepository
import com.lite.xraylite.model.ServerConfig
import com.lite.xraylite.ui.MainActivity
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray

/**
 * VpnService ringan: bikin TUN interface, terus kasih file descriptor-nya LANGSUNG ke
 * `CoreController.startLoop(configJson, tunFd)` dari AndroidLibXrayLite. Versi
 * AndroidLibXrayLite yang sekarang sudah bisa baca trafik dari TUN fd secara internal,
 * jadi TIDAK perlu lagi proses tun2socks terpisah (hev-socks5-tunnel dkk) seperti
 * rencana awal di README — satu native dependency lebih sedikit yang harus di-build.
 *
 * CATATAN PENTING:
 * Kelas ini SUDAH memanggil API Libv2ray yang nyata (bukan TODO kosong lagi), dicek
 * langsung ke source github.com/2dust/AndroidLibXrayLite + cara pakainya di app v2rayNG.
 * Tapi karena `libs/libv2ray.aar` di-build otomatis oleh workflow CI dari source Go
 * ter-update di GitHub (lihat .github/workflows/build.yml), ADA kemungkinan kecil tanda
 * tangan method interface `CoreCallbackHandler` berubah kalau upstream mengubah API-nya
 * setelah file ini ditulis. Kalau build gagal persis di `object : CoreCallbackHandler`
 * karena "method tidak match", cek versi AAR yang ke-build lalu sesuaikan signature di
 * sini (biasanya cuma beda tipe Int/Long).
 */
class XrayVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.lite.xraylite.CONNECT"
        const val ACTION_DISCONNECT = "com.lite.xraylite.DISCONNECT"
        const val EXTRA_CONFIG_ID = "config_id"
        private const val CHANNEL_ID = "xraylite_vpn"
        private const val NOTIF_ID = 1
        private const val STATS_INTERVAL_MS = 1000L
    }

    private var tunFd: ParcelFileDescriptor? = null
    private var isRunning = false
    private var coreController: CoreController? = null
    private val statsHandler = Handler(Looper.getMainLooper())

    private val statsTick = object : Runnable {
        override fun run() {
            pollStats()
            if (isRunning) statsHandler.postDelayed(this, STATS_INTERVAL_MS)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                stopVpn()
                return START_NOT_STICKY
            }
            ACTION_CONNECT -> {
                val cfg = ServerRepository.get(intent.getStringExtra(EXTRA_CONFIG_ID))
                if (cfg != null) startVpn(cfg) else stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startVpn(cfg: ServerConfig) {
        if (isRunning) return
        startForeground(NOTIF_ID, buildNotification("Menghubungkan ke ${cfg.name}..."))

        val fd = Builder()
            .setSession("XrayLite")
            .addAddress("10.10.10.2", 30)
            .addDnsServer("1.1.1.1")
            .addRoute("0.0.0.0", 0)
            .setMtu(1500)
            .establish()

        if (fd == null) {
            updateNotification("Gagal membuat TUN interface")
            stopVpn()
            return
        }
        tunFd = fd

        val controller = Libv2ray.newCoreController(buildCallbackHandler(cfg))
        coreController = controller

        try {
            controller.startLoop(ConfigParser.toXrayFullConfigJson(cfg), fd.fd)
        } catch (e: Exception) {
            updateNotification("Gagal start core: ${e.message}")
            stopVpn()
            return
        }

        ServerRepository.SessionStats.reset()
        isRunning = true
        statsHandler.post(statsTick)
        updateNotification("Terhubung ke ${cfg.name}")
    }

    private fun stopVpn() {
        statsHandler.removeCallbacks(statsTick)
        if (isRunning) {
            runCatching { coreController?.stopLoop() }
        }
        coreController = null
        isRunning = false
        runCatching { tunFd?.close() }
        tunFd = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Sistem manggil ini kalau izin VPN dicabut (mis. user matiin dari Settings, atau app VPN lain ambil alih). */
    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    private fun buildCallbackHandler(cfg: ServerConfig): CoreCallbackHandler = object : CoreCallbackHandler {
        override fun startup(): Long = 0

        override fun shutdown(): Long = 0

        override fun onEmitStatus(status: Long, msg: String?): Long {
            updateNotification(msg?.takeIf { it.isNotBlank() } ?: "Terhubung ke ${cfg.name}")
            return 0
        }
    }

    private fun pollStats() {
        val controller = coreController ?: return
        runCatching {
            val raw = controller.queryAllOutboundTrafficStats()
            applyStats(raw)
        }
    }

    /** Format dari Xray-core: "tag,direction,value;tag,direction,value;..." (bisa kosong). */
    private fun applyStats(raw: String) {
        if (raw.isBlank()) return
        var up = 0L
        var down = 0L
        raw.split(";").forEach { entry ->
            if (entry.isBlank()) return@forEach
            val parts = entry.split(",")
            if (parts.size != 3) return@forEach
            val value = parts[2].toLongOrNull() ?: return@forEach
            when (parts[1]) {
                "uplink" -> up += value
                "downlink" -> down += value
            }
        }
        ServerRepository.SessionStats.uploadBytes += up
        ServerRepository.SessionStats.downloadBytes += down
    }

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "VPN Status", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        // NotificationCompat.Builder aman di semua API level yang didukung app ini (minSdk 24) —
        // dia sendiri yang menyesuaikan ke Notification.Builder asli sesuai API level di belakang
        // layar. Beda dari manggil android.app.Notification.Builder(ctx, channelId) langsung:
        // constructor 2-argumen itu baru ada dari API 26 dan bakal NoSuchMethodError di API 24-25.
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("XrayLite Client")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
