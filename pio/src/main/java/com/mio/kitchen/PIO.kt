package com.mio.kitchen

import android.app.Application
import android.content.Context

class PIO : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LanguageManager.apply(base))
    }
}
