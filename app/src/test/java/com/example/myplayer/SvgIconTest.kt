package com.example.myplayer

import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertNotNull
import org.junit.Test

class SvgIconTest {
    @Test
    fun testAppIconSparkSvgLoads() {
        val stream = javaClass.getResourceAsStream("/drawable/app_icon_spark.svg")
        assertNotNull("app_icon_spark.svg must exist in resources", stream)
        val painter = loadSvgPainter(stream!!, Density(1f))
        assertNotNull("Painter should load successfully", painter)
    }

    @Test
    fun testInsideLogoSvgLoads() {
        val stream = javaClass.getResourceAsStream("/drawable/inside_logo.svg")
        assertNotNull("inside_logo.svg must exist in resources", stream)
        val painter = loadSvgPainter(stream!!, Density(1f))
        assertNotNull("Painter should load successfully", painter)
    }
}
