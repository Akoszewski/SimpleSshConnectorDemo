package com.example.sshapkdownloader

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout

class ConfigActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 36, 32, 32)
        })
    }
}
