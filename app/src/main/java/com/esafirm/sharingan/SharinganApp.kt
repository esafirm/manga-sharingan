package com.esafirm.sharingan

import android.app.Application
import com.esafirm.sharingan.data.AppSettings
import com.esafirm.sharingan.data.MangaRepository

class SharinganApp : Application() {
    val repo by lazy { MangaRepository(this) }
    val settings by lazy { AppSettings(this) }
}
