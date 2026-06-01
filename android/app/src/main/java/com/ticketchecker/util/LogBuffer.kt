package com.ticketchecker.util

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogBuffer {

    private const val MAX_LINES = 150

    private val _logs = MutableLiveData<String>("")
    val logs: LiveData<String> = _logs

    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun append(message: String) {
        synchronized(lines) {
            lines.addLast("${fmt.format(Date())}  $message")
            while (lines.size > MAX_LINES) lines.removeFirst()
            _logs.postValue(lines.joinToString("\n"))
        }
    }

    fun clear() {
        synchronized(lines) {
            lines.clear()
            _logs.postValue("")
        }
    }
}
