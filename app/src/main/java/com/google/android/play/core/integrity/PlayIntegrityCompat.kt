package com.google.android.play.core.integrity

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks

object IntegrityManagerFactory {
    @JvmStatic
    fun createStandard(context: Context): StandardIntegrityManager = StandardIntegrityManagerImpl()
}

interface StandardIntegrityManager {
    fun prepareIntegrityToken(request: PrepareIntegrityTokenRequest): Task<StandardIntegrityTokenProvider>

    class PrepareIntegrityTokenRequest {
        class Builder {
            fun setCloudProjectNumber(num: Long): Builder = this
            fun build(): PrepareIntegrityTokenRequest = PrepareIntegrityTokenRequest()
        }
        companion object {
            @JvmStatic fun builder(): Builder = Builder()
        }
    }

    interface StandardIntegrityTokenProvider {
        fun request(request: StandardIntegrityTokenRequest): Task<StandardIntegrityToken>
    }

    class StandardIntegrityTokenRequest {
        class Builder {
            fun setRequestHash(hash: String): Builder = this
            fun build(): StandardIntegrityTokenRequest = StandardIntegrityTokenRequest()
        }
        companion object {
            @JvmStatic fun builder(): Builder = Builder()
        }
    }

    interface StandardIntegrityToken {
        fun token(): String
    }
}

private class StandardIntegrityManagerImpl : StandardIntegrityManager {
    override fun prepareIntegrityToken(request: StandardIntegrityManager.PrepareIntegrityTokenRequest): Task<StandardIntegrityManager.StandardIntegrityTokenProvider> {
        val provider = object : StandardIntegrityManager.StandardIntegrityTokenProvider {
            override fun request(request: StandardIntegrityManager.StandardIntegrityTokenRequest): Task<StandardIntegrityManager.StandardIntegrityToken> {
                val token = object : StandardIntegrityManager.StandardIntegrityToken {
                    override fun token(): String = "desktop_integrity_token"
                }
                return Tasks.forResult(token)
            }
        }
        return Tasks.forResult(provider)
    }
}
