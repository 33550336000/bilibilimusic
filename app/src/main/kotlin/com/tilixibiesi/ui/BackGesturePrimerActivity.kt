package com.tilixibiesi.ui

import android.app.Activity
import android.os.Bundle

class BackGesturePrimerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
        overridePendingTransition(0, 0)
    }
}
