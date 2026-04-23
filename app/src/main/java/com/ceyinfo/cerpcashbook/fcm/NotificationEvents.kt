package com.ceyinfo.cerpcashbook.fcm

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object NotificationEvents {
    private val _flow = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val flow = _flow.asSharedFlow()

    fun signalPush() {
        _flow.tryEmit(Unit)
    }
}
