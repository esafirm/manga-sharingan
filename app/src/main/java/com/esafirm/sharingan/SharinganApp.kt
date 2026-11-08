package com.esafirm.sharingan

import android.app.Application
import com.esafirm.sharingan.data.MangaRepository

class SharinganApp : Application() {
    val repo by lazy { MangaRepository(this) }
}
