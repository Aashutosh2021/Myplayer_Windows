package android.security.keystore

object KeyProperties {
    const val PURPOSE_ENCRYPT = 1
    const val PURPOSE_DECRYPT = 2
    const val BLOCK_MODE_GCM = "GCM"
    const val ENCRYPTION_PADDING_NONE = "NoPadding"
    const val KEY_ALGORITHM_AES = "AES"
}

class KeyGenParameterSpec {
    class Builder(val keystoreAlias: String, val purposes: Int) {
        fun setBlockModes(vararg modes: String): Builder = this
        fun setEncryptionPaddings(vararg paddings: String): Builder = this
        fun setKeySize(keySize: Int): Builder = this
        fun setUserAuthenticationRequired(required: Boolean): Builder = this
        fun build(): java.security.spec.AlgorithmParameterSpec = object : java.security.spec.AlgorithmParameterSpec {}
    }
}
