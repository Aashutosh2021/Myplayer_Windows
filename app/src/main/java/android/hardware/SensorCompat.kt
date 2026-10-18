package android.hardware

class Sensor {
    companion object {
        const val TYPE_ACCELEROMETER = 1
        const val TYPE_GYROSCOPE = 4
        const val TYPE_MAGNETIC_FIELD = 2
    }
}

class SensorManager {
    fun getDefaultSensor(type: Int): Sensor? = null
}
