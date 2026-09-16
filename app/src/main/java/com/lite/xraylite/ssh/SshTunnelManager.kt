package com.lite.xraylite.ssh

import com.lite.xraylite.model.ServerConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.Executors

/**
 * Tunnel SSH murni: bikin local SOCKS/port-forward via SSH direct-tcpip channel,
 * pakai `LocalPortForwarder` bawaan sshj (bukan bikin sendiri accept-loop) supaya
 * start/stop-nya benar: accept loop otomatis berhenti begitu forwarder di-close(),
 * bukannya nyangkut selamanya di accept() seperti versi sebelumnya.
 *
 * Catatan keamanan: PromiscuousVerifier() menerima host key apa saja (mirip
 * StrictHostKeyChecking=no). Untuk versi lebih matang, simpan & verifikasi
 * fingerprint host key VPS kamu sendiri agar tidak rentan MITM.
 */
class SshTunnelManager {

    private var client: SSHClient? = null
    private var forwarder: LocalPortForwarder? = null
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var stopRequested = false

    /**
     * Konek & buka local port-forward. Blocking di thread background sendiri;
     * [onConnected] dipanggil sekali begitu forward mulai listen, [onError] kalau
     * gagal konek/auth ATAU kalau forward terputus sendiri (bukan karena [disconnect]).
     */
    fun connect(cfg: ServerConfig, onConnected: () -> Unit, onError: (Throwable) -> Unit) {
        stopRequested = false
        executor.execute {
            var ssh: SSHClient? = null
            var serverSocket: ServerSocket? = null
            try {
                ssh = SSHClient()
                ssh.addHostKeyVerifier(PromiscuousVerifier()) // TODO: ganti verifier fingerprint asli
                ssh.connect(cfg.address, cfg.port)

                if (cfg.sshPrivateKeyPem.isNotBlank()) {
                    // PENTING: cfg.sshPrivateKeyPem berisi ISI PEM (bukan path file), jadi
                    // harus lewat overload loadKeys(privateKey, publicKey, passwordFinder) —
                    // overload loadKeys(String location) memperlakukan argumennya sebagai
                    // PATH FILE DI DISK, bukan konten key, dan bakal selalu gagal (FileNotFound)
                    // kalau dikasih isi PEM langsung.
                    val keyProvider = ssh.loadKeys(cfg.sshPrivateKeyPem, null, null)
                    ssh.authPublickey(cfg.sshUsername, keyProvider)
                } else {
                    ssh.authPassword(cfg.sshUsername, cfg.sshPassword)
                }

                // remoteHost/remotePort = layanan di sisi VPS yang mau ditembus lewat
                // channel SSH (default asumsi VPS sudah jalankan SOCKS lokal di
                // 127.0.0.1:1080 — sesuaikan kalau beda, lihat README bagian SSH murni).
                val params = Parameters("127.0.0.1", cfg.localSocksPort, "127.0.0.1", 1080)

                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("127.0.0.1", cfg.localSocksPort))
                }

                client = ssh
                val fwd = ssh.newLocalPortForwarder(params, serverSocket)
                forwarder = fwd

                onConnected()
                fwd.listen() // blocking; balik normal begitu forwarder.close() dipanggil dari disconnect()
            } catch (t: Throwable) {
                if (!stopRequested) onError(t)
            } finally {
                runCatching { serverSocket?.close() }
                runCatching { ssh?.disconnect() }
                client = null
                forwarder = null
            }
        }
    }

    fun disconnect() {
        stopRequested = true
        // forwarder.close() meng-interrupt thread listen() DAN menutup ServerSocket-nya,
        // jadi accept() yang lagi ngeblok langsung keluar dan loop-nya benar-benar berhenti
        // (beda dari implementasi manual sebelumnya yang tidak pernah menutup socket-nya).
        runCatching { forwarder?.close() }
        runCatching { client?.disconnect() }
    }
}
