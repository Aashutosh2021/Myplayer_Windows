package android.os

interface IBinder {
    fun linkToDeath(recipient: DeathRecipient, flags: Int) {}
    fun unlinkToDeath(recipient: DeathRecipient, flags: Int): Boolean = true

    fun interface DeathRecipient {
        fun binderDied()
    }
}

interface IInterface {
    fun asBinder(): IBinder? = null
}

open class Binder : IBinder, IInterface {
    companion object {
        @JvmStatic fun getCallingUid(): Int = 1000
        @JvmStatic fun getCallingPid(): Int = 1000
    }
}

class RemoteException(msg: String? = null) : Exception(msg)

class Message {
    var what: Int = 0
    var arg1: Int = 0
    var arg2: Int = 0
    var obj: Any? = null
    var replyTo: Messenger? = null
    var data: Bundle = Bundle()
        @JvmName("setDataProperty") set

    fun setData(b: Bundle) { data = b }

    companion object {
        fun obtain(): Message = Message()
    }
}

class Messenger(val binder: IBinder = object : IBinder {}) {
    fun send(message: Message) {}
}

class RemoteCallbackList<E : IInterface> {
    private val callbacks = mutableListOf<E>()
    fun register(callback: E): Boolean = callbacks.add(callback)
    fun unregister(callback: E): Boolean = callbacks.remove(callback)
    fun beginBroadcast(): Int = callbacks.size
    fun getBroadcastItem(i: Int): E = callbacks[i]
    fun finishBroadcast() {}
    fun kill() { callbacks.clear() }
}
