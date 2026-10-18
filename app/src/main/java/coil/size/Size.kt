package coil.size

enum class Precision { EXACT, INEXACT, AUTOMATIC }

class Dimension(val px: Int)
data class Size(val width: Dimension, val height: Dimension)

fun Dimension?.pxOrElse(default: () -> Int): Int = this?.px ?: default()
