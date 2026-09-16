package com.lite.xraylite.config

import android.net.Uri
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.lite.xraylite.model.ConnectionType
import com.lite.xraylite.model.ServerConfig
import java.util.UUID

/**
 * Parser link share standar komunitas Xray:
 *  - vless://uuid@host:port?type=ws&path=/&host=xxx&security=tls&sni=xxx&fp=chrome#nama
 *  - vless://uuid@host:port?type=tcp&security=reality&sni=xxx&pbk=xxx&sid=xxx&fp=chrome&flow=xtls-rprx-vision#nama
 *  - trojan://password@host:port?type=ws&path=/&security=tls&sni=xxx#nama
 *  - vmess://<base64 JSON>
 *  - ssh://username:password@host:port#nama   (SSH murni, bukan lewat Xray;
 *    untuk private-key auth pakai dialog "Tambah SSH manual" di layar utama,
 *    bukan format link ini)
 */
object ConfigParser {

    fun parse(link: String): ServerConfig? = when {
        link.startsWith("vless://") -> parseVlessOrTrojan(link, ConnectionType.VLESS)
        link.startsWith("trojan://") -> parseVlessOrTrojan(link, ConnectionType.TROJAN)
        link.startsWith("vmess://") -> parseVmess(link)
        link.startsWith("ssh://") -> parseSsh(link)
        else -> null
    }

    private fun parseVlessOrTrojan(link: String, type: ConnectionType): ServerConfig? {
        return try {
            val uri = Uri.parse(link)
            val userInfo = uri.userInfo ?: return null // uuid (vless) atau password (trojan)
            val host = uri.host ?: return null
            val port = if (uri.port != -1) uri.port else 443
            val name = uri.fragment?.let { Uri.decode(it) } ?: host

            ServerConfig(
                id = UUID.randomUUID().toString(),
                name = name,
                type = type,
                address = host,
                port = port,
                uuidOrPassword = userInfo,
                network = uri.getQueryParameter("type") ?: "ws",
                wsPath = uri.getQueryParameter("path") ?: "/",
                wsHost = uri.getQueryParameter("host") ?: host,
                security = uri.getQueryParameter("security") ?: "tls",
                sni = uri.getQueryParameter("sni") ?: host,
                alpn = uri.getQueryParameter("alpn") ?: "",
                fingerprint = uri.getQueryParameter("fp") ?: "",
                allowInsecure = uri.getQueryParameter("allowInsecure") == "1",
                flow = uri.getQueryParameter("flow") ?: "",
                realityPublicKey = uri.getQueryParameter("pbk") ?: "",
                realityShortId = uri.getQueryParameter("sid") ?: "",
                realitySpiderX = uri.getQueryParameter("spx") ?: ""
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseVmess(link: String): ServerConfig? {
        return try {
            val payload = link.removePrefix("vmess://")
            val decoded = String(Base64.decode(payload, Base64.DEFAULT))
            val json = Gson().fromJson(decoded, JsonObject::class.java)

            ServerConfig(
                id = UUID.randomUUID().toString(),
                name = json.get("ps")?.asString ?: "vmess",
                type = ConnectionType.VMESS,
                address = json.get("add")?.asString ?: return null,
                port = json.get("port")?.asString?.toIntOrNull() ?: 443,
                uuidOrPassword = json.get("id")?.asString ?: return null,
                network = json.get("net")?.asString ?: "ws",
                wsPath = json.get("path")?.asString ?: "/",
                wsHost = json.get("host")?.asString ?: "",
                security = if ((json.get("tls")?.asString ?: "") == "tls") "tls" else "none",
                sni = json.get("sni")?.asString ?: (json.get("host")?.asString ?: ""),
                alpn = json.get("alpn")?.asString ?: "",
                fingerprint = json.get("fp")?.asString ?: "",
                allowInsecure = false,
                flow = ""
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseSsh(link: String): ServerConfig? {
        return try {
            val uri = Uri.parse(link)
            val host = uri.host ?: return null
            val port = if (uri.port != -1) uri.port else 22
            val userInfo = uri.userInfo ?: return null
            val (user, pass) = userInfo.split(":", limit = 2).let {
                it.getOrElse(0) { "" } to it.getOrElse(1) { "" }
            }
            if (user.isBlank()) return null
            val name = uri.fragment?.let { Uri.decode(it) } ?: host

            ServerConfig(
                id = UUID.randomUUID().toString(),
                name = name,
                type = ConnectionType.SSH,
                address = host,
                port = port,
                sshUsername = user,
                sshPassword = pass,
                localSocksPort = uri.getQueryParameter("localPort")?.toIntOrNull() ?: 1080
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Bangun blok "settings" outbound Xray-core, KHUSUS sesuai protokol (vless/vmess
     * pakai "vnext", trojan pakai "servers" — tidak digabung asal-asalan seperti versi
     * sebelumnya). Semua string lewat JsonObject/Gson jadi otomatis di-escape dengan benar,
     * tidak ada lagi risiko JSON rusak kalau uuid/password/path mengandung karakter `"`.
     */
    private fun buildOutboundSettings(cfg: ServerConfig): JsonObject {
        val settings = JsonObject()
        when (cfg.type) {
            ConnectionType.VLESS -> {
                val user = JsonObject().apply {
                    addProperty("id", cfg.uuidOrPassword)
                    addProperty("encryption", "none")
                    if (cfg.flow.isNotBlank()) addProperty("flow", cfg.flow)
                }
                val vnext = JsonObject().apply {
                    addProperty("address", cfg.address)
                    addProperty("port", cfg.port)
                    add("users", JsonArray().apply { add(user) })
                }
                settings.add("vnext", JsonArray().apply { add(vnext) })
            }
            ConnectionType.VMESS -> {
                val user = JsonObject().apply {
                    addProperty("id", cfg.uuidOrPassword)
                    addProperty("alterId", 0)
                }
                val vnext = JsonObject().apply {
                    addProperty("address", cfg.address)
                    addProperty("port", cfg.port)
                    add("users", JsonArray().apply { add(user) })
                }
                settings.add("vnext", JsonArray().apply { add(vnext) })
            }
            ConnectionType.TROJAN -> {
                val server = JsonObject().apply {
                    addProperty("address", cfg.address)
                    addProperty("port", cfg.port)
                    addProperty("password", cfg.uuidOrPassword)
                }
                settings.add("servers", JsonArray().apply { add(server) })
            }
            ConnectionType.SSH -> throw IllegalArgumentException("SSH bukan outbound Xray")
        }
        return settings
    }

    private fun buildStreamSettings(cfg: ServerConfig): JsonObject {
        val streamSettings = JsonObject()
        streamSettings.addProperty("network", cfg.network)
        streamSettings.addProperty("security", cfg.security)

        when (cfg.network) {
            "ws" -> {
                val wsSettings = JsonObject().apply {
                    addProperty("path", cfg.wsPath)
                    add("headers", JsonObject().apply { addProperty("Host", cfg.wsHost) })
                }
                streamSettings.add("wsSettings", wsSettings)
            }
            "grpc" -> {
                val grpcSettings = JsonObject().apply {
                    addProperty("serviceName", cfg.wsPath.trim('/'))
                }
                streamSettings.add("grpcSettings", grpcSettings)
            }
            // "tcp" tidak butuh blok settings tambahan
        }

        when (cfg.security) {
            "tls" -> {
                val tlsSettings = JsonObject().apply {
                    addProperty("serverName", cfg.sni)
                    addProperty("allowInsecure", cfg.allowInsecure)
                    if (cfg.fingerprint.isNotBlank()) addProperty("fingerprint", cfg.fingerprint)
                    if (cfg.alpn.isNotBlank()) {
                        add("alpn", JsonArray().apply { cfg.alpn.split(",").forEach { add(it.trim()) } })
                    }
                }
                streamSettings.add("tlsSettings", tlsSettings)
            }
            "reality" -> {
                // Skema field ini (serverName/fingerprint/publicKey/shortId/spiderX) adalah
                // format outbound realitySettings resmi Xray-core, bukan tebakan.
                val realitySettings = JsonObject().apply {
                    addProperty("serverName", cfg.sni)
                    // "chrome" dipakai sebagai default kalau link tidak menyertakan fp= — Reality
                    // WAJIB fingerprint yang valid (tidak boleh kosong) beda dari TLS biasa.
                    addProperty("fingerprint", cfg.fingerprint.ifBlank { "chrome" })
                    addProperty("publicKey", cfg.realityPublicKey)
                    if (cfg.realityShortId.isNotBlank()) addProperty("shortId", cfg.realityShortId)
                    if (cfg.realitySpiderX.isNotBlank()) addProperty("spiderX", cfg.realitySpiderX)
                }
                streamSettings.add("realitySettings", realitySettings)
            }
            // "none" -> tidak perlu blok tambahan
        }
        return streamSettings
    }

    /** Satu blok "outbound" Xray-core untuk [cfg], dengan tag "proxy". */
    private fun buildProxyOutbound(cfg: ServerConfig): JsonObject {
        val protocol = when (cfg.type) {
            ConnectionType.VLESS -> "vless"
            ConnectionType.VMESS -> "vmess"
            ConnectionType.TROJAN -> "trojan"
            ConnectionType.SSH -> throw IllegalArgumentException("SSH bukan outbound Xray")
        }
        return JsonObject().apply {
            addProperty("tag", "proxy")
            addProperty("protocol", protocol)
            add("settings", buildOutboundSettings(cfg))
            add("streamSettings", buildStreamSettings(cfg))
        }
    }

    /**
     * Config Xray-core lengkap siap dikirim ke `CoreController.startLoop(configContent, tunFd)`
     * dari AndroidLibXrayLite. Tidak ada "inbounds" karena trafik masuk lewat TUN fd yang
     * dikasih langsung ke StartLoop (AndroidLibXrayLite versi sekarang sudah handle TUN
     * secara internal, tidak perlu inbound SOCKS + tun2socks terpisah lagi).
     *
     * "stats" + "policy.system.statsOutbound*" diaktifkan supaya
     * `CoreController.queryAllOutboundTrafficStats()` benar-benar mengembalikan angka
     * (dipakai XrayVpnService buat isi counter upload/download di layar utama).
     */
    fun toXrayFullConfigJson(cfg: ServerConfig): String {
        val root = JsonObject()

        root.add("log", JsonObject().apply { addProperty("loglevel", "warning") })
        root.add("stats", JsonObject())
        root.add("policy", JsonObject().apply {
            add("system", JsonObject().apply {
                addProperty("statsOutboundUplink", true)
                addProperty("statsOutboundDownlink", true)
            })
        })
        root.add("inbounds", JsonArray())

        val outbounds = JsonArray().apply {
            add(buildProxyOutbound(cfg))
            add(JsonObject().apply { addProperty("protocol", "freedom"); addProperty("tag", "direct") })
            add(JsonObject().apply { addProperty("protocol", "blackhole"); addProperty("tag", "block") })
        }
        root.add("outbounds", outbounds)

        // Tidak ada "rules" eksplisit -> semua trafik default ke outbound pertama ("proxy").
        root.add("routing", JsonObject().apply {
            addProperty("domainStrategy", "AsIs")
            add("rules", JsonArray())
        })

        return Gson().toJson(root)
    }
}
