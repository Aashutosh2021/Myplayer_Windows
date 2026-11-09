package com.example.aria.connector

import android.os.IInterface
import android.os.IBinder
import android.os.Binder

interface IAriaConnector : IInterface {
    fun execute(command: AppCommandContainer): AppResponseContainer
    fun getCapabilities(): List<Capability>
    fun getVersionInfo(): ConnectorVersionInfo
    fun getAppName(): String
    fun registerCallback(callback: IAriaCallback)
    fun unregisterCallback(callback: IAriaCallback)
    fun executeLegacy(commandJson: String): String

    abstract class Stub : Binder(), IAriaConnector {
        override fun asBinder(): IBinder = this

        companion object {
            @JvmStatic
            fun asInterface(obj: IBinder?): IAriaConnector? = null
        }
    }
}

