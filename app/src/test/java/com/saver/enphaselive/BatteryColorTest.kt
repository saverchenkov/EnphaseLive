package com.saver.enphaselive

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryColorTest {

    @Test
    fun testBatteryColorThresholds() {
        val red = Color.rgb(240, 68, 68)
        val orange = Color.rgb(255, 140, 40)
        val yellow = Color.rgb(250, 205, 45)
        val green = Color.rgb(82, 229, 140)

        // Below 10%: Red
        assertEquals(red, EnergyFlowView.getBatteryColor(0))
        assertEquals(red, EnergyFlowView.getBatteryColor(5))
        assertEquals(red, EnergyFlowView.getBatteryColor(9))

        // Below 20%: Orange
        assertEquals(orange, EnergyFlowView.getBatteryColor(10))
        assertEquals(orange, EnergyFlowView.getBatteryColor(15))
        assertEquals(orange, EnergyFlowView.getBatteryColor(19))

        // Below 30%: Yellow
        assertEquals(yellow, EnergyFlowView.getBatteryColor(20))
        assertEquals(yellow, EnergyFlowView.getBatteryColor(25))
        assertEquals(yellow, EnergyFlowView.getBatteryColor(29))

        // 30% and above: Green
        assertEquals(green, EnergyFlowView.getBatteryColor(30))
        assertEquals(green, EnergyFlowView.getBatteryColor(50))
        assertEquals(green, EnergyFlowView.getBatteryColor(85))
        assertEquals(green, EnergyFlowView.getBatteryColor(100))

        // Negative / unknown SoC: Default Green
        assertEquals(green, EnergyFlowView.getBatteryColor(-1))
    }
}
