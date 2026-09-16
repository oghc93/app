package com.lite.xraylite

import android.app.Application

/**
 * go.Seq.setContext(...) WAJIB dipanggil sekali sebelum method Libv2ray/CoreController
 * apapun dipanggil — ini syarat runtime dari binding gomobile (gobind), bukan langkah
 * opsional. Paling aman dipanggil sedini mungkin di Application.onCreate(), bukan di
 * XrayVpnService (yang bisa saja belum tentu jadi komponen pertama yang start).
 *
 * Libv2ray.initCoreEnv(assetPath, xudpBaseKey) menyiapkan path asset/cert Xray-core.
 * xudpBaseKey dikosongkan karena app ini tidak pakai fitur XUDP encryption khusus.
 */
class XrayLiteApp : Application() {
    override fun onCreate() {
        super.onCreate()
        go.Seq.setContext(applicationContext)
        libv2ray.Libv2ray.initCoreEnv(filesDir.absolutePath, "")
    }
}
