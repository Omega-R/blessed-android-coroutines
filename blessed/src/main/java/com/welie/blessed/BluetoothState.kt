package com.welie.blessed

import android.bluetooth.BluetoothAdapter

enum class BluetoothState(val code: Int) {

    /**
     * Indicates the local Bluetooth adapter is off.
     */
    OFF(BluetoothAdapter.STATE_OFF),

    /**
     * Indicates the local Bluetooth adapter is turning on. However local
     * clients should wait for [.STATE_ON] before attempting to
     * use the adapter.
     */
    STATE_TURNING_ON(BluetoothAdapter.STATE_TURNING_ON),

    /**
     * Indicates the local Bluetooth adapter is on, and ready for use.
     */
    STATE_ON(BluetoothAdapter.STATE_ON),

    /**
     * Indicates the local Bluetooth adapter is turning off. Local clients
     * should immediately attempt graceful disconnection of any remote links.
     */
    STATE_TURNING_OFF(BluetoothAdapter.STATE_TURNING_OFF);

    companion object {

        fun from(code: Int) = values().firstOrNull { it.code == code }
    }
}