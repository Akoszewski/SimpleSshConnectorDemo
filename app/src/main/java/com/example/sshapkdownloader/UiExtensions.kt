package com.example.sshapkdownloader

import android.app.Activity
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.Toast

fun Context.showShortToast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

fun Activity.showShortToastOnUiThread(message: String) {
    runOnUiThread {
        showShortToast(message)
    }
}

fun EditText.doOnTextChanged(action: (String) -> Unit) {
    addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            action(s?.toString().orEmpty())
        }

        override fun afterTextChanged(s: Editable?) = Unit
    })
}

fun Throwable.displayMessage(): String {
    return message ?: javaClass.simpleName
}
