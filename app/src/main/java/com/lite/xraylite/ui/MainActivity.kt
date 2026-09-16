package com.lite.xraylite.ui

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.lite.xraylite.config.ConfigParser
import com.lite.xraylite.config.ServerRepository
import com.lite.xraylite.databinding.ActivityMainBinding
import com.lite.xraylite.model.ConnectionType
import com.lite.xraylite.model.ServerConfig
import com.lite.xraylite.ssh.SshTunnelManager
import com.lite.xraylite.vpn.XrayVpnService
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ServerListAdapter
    private var connected = false
    private val sshManager = SshTunnelManager()
    private val uiHandler = Handler(Looper.getMainLooper())

    // Tick tiap detik untuk update durasi + counter upload/download dari
    // ServerRepository.SessionStats (diisi XrayVpnService lewat stats API asli
    // Xray-core, atau nanti bisa disambung ke counter socket SSH juga).
    private val monitorTick = object : Runnable {
        override fun run() {
            if (connected) {
                val elapsedMs = System.currentTimeMillis() - ServerRepository.SessionStats.connectedSinceMs
                binding.tvDuration.text = formatDuration(elapsedMs)
                binding.tvUpload.text = formatBytes(ServerRepository.SessionStats.uploadBytes)
                binding.tvDownload.text = formatBytes(ServerRepository.SessionStats.downloadBytes)
            }
            uiHandler.postDelayed(this, 1000)
        }
    }

    private val vpnPermissionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) startTunnel()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = ServerListAdapter { cfg -> onServerSelected(cfg) }
        binding.rvServers.layoutManager = LinearLayoutManager(this)
        binding.rvServers.adapter = adapter

        intent?.data?.toString()?.let { handleIncomingLink(it) }

        binding.btnImportLink.setOnClickListener { showImportLinkDialog() }
        binding.btnAddSsh.setOnClickListener { showAddSshDialog() }
        binding.btnConnect.setOnClickListener { toggleConnection() }

        refreshList()
        uiHandler.post(monitorTick)
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(monitorTick)
        super.onDestroy()
    }

    private fun refreshList() {
        adapter.submit(ServerRepository.all(), ServerRepository.activeId)
        val active = ServerRepository.activeConfig()
        binding.tvServerName.text = active?.let { "${it.name} (${it.type})" } ?: "Belum ada server dipilih"
    }

    private fun onServerSelected(cfg: ServerConfig) {
        if (connected) return // jangan ganti server saat masih terhubung
        ServerRepository.setActive(cfg.id)
        refreshList()
    }

    private fun showImportLinkDialog() {
        val input = EditText(this).apply {
            hint = "Tempel link vless:// vmess:// trojan://"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle("Import link")
            .setView(input)
            .setPositiveButton("Simpan") { _, _ -> handleIncomingLink(input.text.toString().trim()) }
            .setNegativeButton("Batal", null)
            .show()
    }

    /** Form manual buat SSH murni (host/port/user + password ATAU private key PEM). */
    private fun showAddSshDialog() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val etHost = EditText(this).apply { hint = "Host / IP VPS" }
        val etPort = EditText(this).apply {
            hint = "Port SSH (default 22)"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val etUser = EditText(this).apply { hint = "Username" }
        val etPass = EditText(this).apply {
            hint = "Password (kosongkan kalau pakai private key)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val etKey = EditText(this).apply {
            hint = "Private key PEM (opsional, kosongkan kalau pakai password)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            isSingleLine = false
        }
        val etLocalPort = EditText(this).apply {
            hint = "Local SOCKS port (default 1080)"
            inputType = InputType.TYPE_CLASS_NUMBER
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            listOf(etHost, etPort, etUser, etPass, etKey, etLocalPort).forEach {
                addView(it)
                (it.layoutParams as LinearLayout.LayoutParams).topMargin = pad / 2
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Tambah server SSH murni")
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton("Simpan") { _, _ ->
                val host = etHost.text.toString().trim()
                val user = etUser.text.toString().trim()
                if (host.isEmpty() || user.isEmpty()) {
                    binding.tvServerName.text = "Host & username wajib diisi"
                    return@setPositiveButton
                }
                val cfg = ServerConfig(
                    id = UUID.randomUUID().toString(),
                    name = host,
                    type = ConnectionType.SSH,
                    address = host,
                    port = etPort.text.toString().toIntOrNull() ?: 22,
                    sshUsername = user,
                    sshPassword = etPass.text.toString(),
                    sshPrivateKeyPem = etKey.text.toString().trim(),
                    localSocksPort = etLocalPort.text.toString().toIntOrNull() ?: 1080
                )
                ServerRepository.add(cfg)
                if (ServerRepository.activeId == null) ServerRepository.setActive(cfg.id)
                refreshList()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun handleIncomingLink(link: String) {
        val cfg = ConfigParser.parse(link)
        if (cfg == null) {
            binding.tvServerName.text = "Link tidak valid"
            return
        }
        ServerRepository.add(cfg)
        if (ServerRepository.activeId == null) ServerRepository.setActive(cfg.id)
        refreshList()
    }

    private fun toggleConnection() {
        val cfg = ServerRepository.activeConfig()
        if (cfg == null) {
            binding.tvServerName.text = "Pilih atau import server dulu"
            return
        }
        if (connected) {
            stopTunnel(cfg)
        } else {
            when (cfg.type) {
                ConnectionType.SSH -> startSshTunnel(cfg)
                else -> requestVpnPermissionThenStart()
            }
        }
    }

    private fun requestVpnPermissionThenStart() {
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermissionLauncher.launch(intent) else startTunnel()
    }

    private fun startTunnel() {
        val cfg = ServerRepository.activeConfig() ?: return
        val intent = Intent(this, XrayVpnService::class.java).apply {
            action = XrayVpnService.ACTION_CONNECT
            putExtra(XrayVpnService.EXTRA_CONFIG_ID, cfg.id)
        }
        startForegroundService(intent)
        ServerRepository.SessionStats.reset()
        setConnectedUi(true)
    }

    private fun startSshTunnel(cfg: ServerConfig) {
        sshManager.connect(
            cfg,
            onConnected = {
                runOnUiThread {
                    ServerRepository.SessionStats.reset()
                    setConnectedUi(true)
                }
            },
            onError = { err ->
                runOnUiThread {
                    binding.tvStatus.text = "Gagal: ${err.message}"
                    setConnectedUi(false)
                }
            }
        )
    }

    private fun stopTunnel(cfg: ServerConfig) {
        if (cfg.type == ConnectionType.SSH) {
            sshManager.disconnect()
        } else {
            startService(Intent(this, XrayVpnService::class.java).apply {
                action = XrayVpnService.ACTION_DISCONNECT
            })
        }
        setConnectedUi(false)
    }

    private fun setConnectedUi(isConnected: Boolean) {
        connected = isConnected
        binding.tvStatus.text = if (isConnected) "Terhubung" else "Terputus"
        binding.btnConnect.text = if (isConnected) "DISCONNECT" else "CONNECT"
        if (!isConnected) {
            binding.tvDuration.text = "00:00:00"
            binding.tvUpload.text = "0 KB"
            binding.tvDownload.text = "0 KB"
        }
        refreshList()
    }

    private fun formatDuration(ms: Long): String {
        val h = TimeUnit.MILLISECONDS.toHours(ms)
        val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        return String.format("%.1f MB", mb)
    }
}
