package com.lite.xraylite.config

import com.lite.xraylite.model.ServerConfig

/**
 * Penyimpanan daftar semua profil server (multi-profile), plus profil aktif
 * dan statistik sesi berjalan (dipakai panel monitor di UI).
 *
 * Catatan: ini in-memory saja. Untuk persist antar restart app, ganti
 * implementasi simpan/load ke SharedPreferences atau Room — struktur
 * data ServerConfig sudah data class jadi gampang di-serialize (Gson).
 */
object ServerRepository {

    private val servers = LinkedHashMap<String, ServerConfig>()
    var activeId: String? = null
        private set

    // Statistik sesi berjalan (byte). Diupdate dari XrayVpnService / SshTunnelManager
    // lewat SessionStats saat data mengalir. Placeholder sampai dihubungkan ke
    // API stats asli Xray-core (StatsManager) atau counter socket SSH.
    object SessionStats {
        var uploadBytes: Long = 0L
        var downloadBytes: Long = 0L
        var connectedSinceMs: Long = 0L

        fun reset() {
            uploadBytes = 0L
            downloadBytes = 0L
            connectedSinceMs = System.currentTimeMillis()
        }
    }

    // Hasil ping terakhir tiap server (ms), null = belum pernah dites
    private val pingResults = HashMap<String, Int?>()

    fun setPing(id: String, ms: Int?) { pingResults[id] = ms }
    fun getPing(id: String): Int? = pingResults[id]

    fun add(cfg: ServerConfig) {
        servers[cfg.id] = cfg
    }

    fun remove(id: String) {
        servers.remove(id)
        if (activeId == id) activeId = null
    }

    fun all(): List<ServerConfig> = servers.values.toList()

    fun get(id: String?): ServerConfig? = id?.let { servers[it] }

    fun setActive(id: String) {
        activeId = id
    }

    fun activeConfig(): ServerConfig? = get(activeId)
}
