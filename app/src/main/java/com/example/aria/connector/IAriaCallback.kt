package com.example.aria.connector

import android.os.IInterface
import android.os.IBinder

interface IAriaCallback : IInterface {
    fun onEvent(event: EcosystemEventContainer)
    fun onCapabilitiesChanged(capabilities: List<Capability>)

    abstract class Stub : IAriaCallback {
        companion object {
            @JvmStatic
            fun asInterface(obj: IBinder?): IAriaCallback? = null
        }
    }
}

