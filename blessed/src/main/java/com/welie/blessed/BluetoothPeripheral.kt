/*
 *   Copyright (c) 2021 Martijn van Welie
 *
 *   Permission is hereby granted, free of charge, to any person obtaining a copy
 *   of this software and associated documentation files (the "Software"), to deal
 *   in the Software without restriction, including without limitation the rights
 *   to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *   copies of the Software, and to permit persons to whom the Software is
 *   furnished to do so, subject to the following conditions:
 *
 *   The above copyright notice and this permission notice shall be included in all
 *   copies or substantial portions of the Software.
 *
 *   THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *   IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *   FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *   AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *   LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *   OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *   SOFTWARE.
 *
 */
package com.welie.blessed

import android.bluetooth.*
import android.bluetooth.BluetoothGattDescriptor.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.*
import timber.log.Timber
import java.lang.Runnable
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Represents a remote Bluetooth peripheral and replaces BluetoothDevice and BluetoothGatt
 *
 *
 * A [BluetoothPeripheral] lets you create a connection with the peripheral or query information about it.
 * This class is a wrapper around the [BluetoothDevice] and takes care of operation queueing, some Android bugs, and provides several convenience functions.
 */
class BluetoothPeripheral internal constructor(
    private val context: Context,
    private var device: BluetoothDevice,
    private val listener: InternalCallback,
    internal var peripheralCallback: BluetoothPeripheralCallback
) {
    private val commandQueue: Queue<Runnable> = ConcurrentLinkedQueue()

    @Volatile
    private var bluetoothGatt: BluetoothGatt? = null
    private var cachedName = ""
    private var currentWriteBytes = ByteArray(0)
    private var currentCommand = ""
    private var currentResultCallback: BluetoothPeripheralCallback = BluetoothPeripheralCallback.NULL()
    private val notifyingCharacteristics: MutableSet<BluetoothGattCharacteristic> = HashSet()
    private var observeMap: MutableMap<BluetoothGattCharacteristic, (value: ByteArray) -> Unit> = HashMap()

    @Volatile
    private var commandQueueBusy = false
    private var isRetrying = false
    private var bondLost = false
    private var manuallyBonding = false
    private var discoveryStarted = false

    @Volatile
    private var state = BluetoothProfile.STATE_DISCONNECTED
    private var nrTries = 0
    private var connectTimestamp: Long = 0

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Returns the currently set MTU
     *
     * @return the MTU
     */
    var currentMtu = DEFAULT_MTU
        private set

    /**
     * This abstract class is used to implement BluetoothGatt callbacks.
     */
    private val bluetoothGattCallback: BluetoothGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            cancelConnectionTimer()
            val previousState = state
            state = newState

            val hciStatus = HciStatus.fromValue(status)
            if (hciStatus == HciStatus.SUCCESS) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> successfullyConnected()
                    BluetoothProfile.STATE_DISCONNECTED -> successfullyDisconnected(previousState)
                    BluetoothProfile.STATE_DISCONNECTING -> Timber.i("peripheral is disconnecting")
                    BluetoothProfile.STATE_CONNECTING -> Timber.i("peripheral is connecting")
                    else -> Timber.e("unknown state received")
                }
            } else {
                connectionStateChangeUnsuccessful(hciStatus, previousState, newState)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val gattStatus = GattStatus.fromValue(status)
            if (gattStatus != GattStatus.SUCCESS) {
                Timber.e("service discovery failed due to internal error '%s', disconnecting", gattStatus)
                disconnect()
                return
            }
            val services = gatt.services
            Timber.i("discovered %d services for '%s'", services.size, name)

            // Issue 'connected' since we are now fully connect incl service discovery
            listener.connected(this@BluetoothPeripheral)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val gattStatus = GattStatus.fromValue(status)
            val parentCharacteristic = descriptor.characteristic
            if (gattStatus != GattStatus.SUCCESS) {
                Timber.e(
                    "failed to write <%s> to descriptor of characteristic <%s> for device: '%s', status '%s' ",
                    BluetoothBytesParser.bytes2String(currentWriteBytes),
                    parentCharacteristic.uuid,
                    address,
                    gattStatus
                )
            }

            if (descriptor.uuid == CCC_DESCRIPTOR_UUID) {
                if (gattStatus == GattStatus.SUCCESS) {
                    val value = nonnullOf(descriptor.value)
                    if (value.contentEquals(ENABLE_NOTIFICATION_VALUE) ||
                        value.contentEquals(ENABLE_INDICATION_VALUE)
                    ) {
                        notifyingCharacteristics.add(parentCharacteristic)
                    } else if (value.contentEquals(DISABLE_NOTIFICATION_VALUE)) {
                        notifyingCharacteristics.remove(parentCharacteristic)
                    }
                }
                scope.launch { currentResultCallback.onNotificationStateUpdate(this@BluetoothPeripheral, parentCharacteristic, gattStatus) }
            } else {
                scope.launch { currentResultCallback.onDescriptorWrite(this@BluetoothPeripheral, currentWriteBytes, descriptor, gattStatus) }
            }
            completedCommand()
        }

        override fun onDescriptorRead(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val gattStatus = GattStatus.fromValue(status)
            if (gattStatus != GattStatus.SUCCESS) {
                Timber.e("reading descriptor <%s> failed for device '%s, status '%s'", descriptor.uuid, address, gattStatus)
            }

            val value = nonnullOf(descriptor.value)
            scope.launch { currentResultCallback.onDescriptorRead(this@BluetoothPeripheral, value, descriptor, gattStatus) }
            completedCommand()
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = nonnullOf(characteristic.value)
            scope.launch { observeMap[characteristic]?.invoke(value) }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val gattStatus = GattStatus.fromValue(status)
            if (gattStatus != GattStatus.SUCCESS) {
                Timber.e("read failed for characteristic <%s>, status '%s'", characteristic.uuid, gattStatus)
            }

            val value = nonnullOf(characteristic.value)
            scope.launch { currentResultCallback.onCharacteristicRead(this@BluetoothPeripheral, value, characteristic, gattStatus) }
            completedCommand()
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val gattStatus = GattStatus.fromValue(status)
            if (gattStatus != GattStatus.SUCCESS) {
                Timber.e("writing <%s> to characteristic <%s> failed, status '%s'", BluetoothBytesParser.bytes2String(currentWriteBytes), characteristic.uuid, gattStatus)
            }

            val value = currentWriteBytes
            currentWriteBytes = ByteArray(0)
            scope.launch { currentResultCallback.onCharacteristicWrite(this@BluetoothPeripheral, value, characteristic, gattStatus) }
            completedCommand()
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            val gattStatus = GattStatus.fromValue(status)
            if (gattStatus != GattStatus.SUCCESS) {
                Timber.e("reading RSSI failed, status '%s'", gattStatus)
            }

            scope.launch { currentResultCallback.onReadRemoteRssi(this@BluetoothPeripheral, rssi, gattStatus) }
            completedCommand()
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val gattStatus = GattStatus.fromValue(status)
            if (gattStatus != GattStatus.SUCCESS) {
                Timber.e("change MTU failed, status '%s'", gattStatus)
            }

            currentMtu = mtu
            scope.launch { currentResultCallback.onMtuChanged(this@BluetoothPeripheral, mtu, gattStatus) }

            // Only complete the command if we initiated the operation. It can also be initiated by the remote peripheral...
            if (currentCommand == REQUEST_MTU_COMMAND) {
                currentCommand = ""
                completedCommand()
            }
        }

        override fun onPhyRead(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            val gattStatus = GattStatus.fromValue(status)
            if (gattStatus != GattStatus.SUCCESS) {
                Timber.e("read Phy failed, status '%s'", gattStatus)
            } else {
                Timber.i("updated Phy: tx = %s, rx = %s", PhyType.fromValue(txPhy), PhyType.fromValue(rxPhy))
            }
            scope.launch { currentResultCallback.onPhyUpdate(this@BluetoothPeripheral, PhyType.fromValue(txPhy), PhyType.fromValue(rxPhy), gattStatus) }
            completedCommand()
        }

        override fun onPhyUpdate(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            val gattStatus = GattStatus.fromValue(status)
            if (gattStatus != GattStatus.SUCCESS) {
                Timber.e("update Phy failed, status '%s'", gattStatus)
            } else {
                Timber.i("updated Phy: tx = %s, rx = %s", PhyType.fromValue(txPhy), PhyType.fromValue(rxPhy))
            }
            scope.launch { currentResultCallback.onPhyUpdate(this@BluetoothPeripheral, PhyType.fromValue(txPhy), PhyType.fromValue(rxPhy), gattStatus) }
        }

        /**
         * This callback is only called from Android 8 (Oreo) or higher
         */
        fun onConnectionUpdated(gatt: BluetoothGatt, interval: Int, latency: Int, timeout: Int, status: Int) {
            val gattStatus = GattStatus.fromValue(status)
            if (gattStatus == GattStatus.SUCCESS) {
                val msg = String.format(Locale.ENGLISH, "connection parameters: interval=%.1fms latency=%d timeout=%ds", interval * 1.25f, latency, timeout / 100)
                Timber.d(msg)
            } else {
                Timber.e("connection parameters update failed with status '%s'", gattStatus)
            }
            scope.launch { peripheralCallback.onConnectionUpdated(this@BluetoothPeripheral, interval, latency, timeout, gattStatus) }
        }
    }

    private fun successfullyConnected() {
        val bondstate = bondState
        val timePassed = SystemClock.elapsedRealtime() - connectTimestamp
        Timber.i("connected to '%s' (%s) in %.1fs", name, bondstate, timePassed / 1000.0f)
        if (bondstate == BondState.NONE || bondstate == BondState.BONDED) {
            discoverServices()
        } else if (bondstate == BondState.BONDING) {
            // Apparently the bonding process has already started, so let it complete. We'll do discoverServices once bonding finished
            Timber.i("waiting for bonding to complete")
        }
    }

    private var discoverJob: Job? = null

    private fun discoverServices() {
        discoverJob = scope.launch {
            Timber.d("discovering services of '%s'", name)
            if (bluetoothGatt != null && bluetoothGatt!!.discoverServices()) {
                discoveryStarted = true
            } else {
                Timber.e("discoverServices failed to start")
            }
        }
    }

    private fun successfullyDisconnected(previousState: Int) {
        if (previousState == BluetoothProfile.STATE_CONNECTED || previousState == BluetoothProfile.STATE_DISCONNECTING) {
            Timber.i("disconnected '%s' on request", name)
        } else if (previousState == BluetoothProfile.STATE_CONNECTING) {
            Timber.i("cancelling connect attempt")
        }
        if (bondLost) {
            completeDisconnect(false, HciStatus.SUCCESS)

            // Consider the loss of the bond a connection failure so that a connection retry will take place
            scope.launch {
                // Give the stack some time to register the bond loss internally. This is needed on most phones...
                delay(DELAY_AFTER_BOND_LOST)

                listener.connectFailed(this@BluetoothPeripheral, HciStatus.SUCCESS)
            }
        } else {
            completeDisconnect(true, HciStatus.SUCCESS)
        }
    }

    private fun connectionStateChangeUnsuccessful(status: HciStatus, previousState: Int, newState: Int) {
        cancelPendingServiceDiscovery()
        val servicesDiscovered = !services.isEmpty()

        // See if the initial connection failed
        if (previousState == BluetoothProfile.STATE_CONNECTING) {
            val timePassed = SystemClock.elapsedRealtime() - connectTimestamp
            val isTimeout = timePassed > timoutThreshold
            val adjustedStatus = if (status == HciStatus.ERROR && isTimeout) HciStatus.CONNECTION_FAILED_ESTABLISHMENT else status
            Timber.i("connection failed with status '%s'", adjustedStatus)
            completeDisconnect(false, adjustedStatus)
            listener.connectFailed(this@BluetoothPeripheral, adjustedStatus)
        } else if (previousState == BluetoothProfile.STATE_CONNECTED && newState == BluetoothProfile.STATE_DISCONNECTED && !servicesDiscovered) {
            // We got a disconnection before the services were even discovered
            Timber.i("peripheral '%s' disconnected with status '%s' (%d) before completing service discovery", name, status, status.value)
            completeDisconnect(false, status)
            listener.connectFailed(this@BluetoothPeripheral, status)
        } else {
            // See if we got connection drop
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Timber.i("peripheral '%s' disconnected with status '%s' (%d)", name, status, status.value)
            } else {
                Timber.i("unexpected connection state change for '%s' status '%s' (%d)", name, status, status.value)
            }
            completeDisconnect(true, status)
        }
    }

    private fun cancelPendingServiceDiscovery() {
//        if (discoverServicesRunnable != null) {
//            mainHandler.removeCallbacks(discoverServicesRunnable!!)
//            discoverServicesRunnable = null
//        }
        discoverJob?.cancel()
        discoverJob = null
    }

    private val bondStateReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            val receivedDevice = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return

            // Ignore updates for other devices
            if (!receivedDevice.address.equals(address, ignoreCase = true)) return
            if (action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                val previousBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
                handleBondStateChange(bondState, previousBondState)
            }
        }
    }

    private fun handleBondStateChange(bondState: Int, previousBondState: Int) {
        when (bondState) {
            BluetoothDevice.BOND_BONDING -> {
                Timber.d("starting bonding with '%s' (%s)", name, address)
                scope.launch { peripheralCallback.onBondingStarted(this@BluetoothPeripheral) }
            }
            BluetoothDevice.BOND_BONDED -> {
                Timber.d("bonded with '%s' (%s)", name, address)
                scope.launch { peripheralCallback.onBondingSucceeded(this@BluetoothPeripheral) }

                // If bonding was started at connection time, we may still have to discover the services
                // Also make sure we are not starting a discovery while another one is already in progress
                if (services.isEmpty() && !discoveryStarted) {
                    discoverServices()
                }

                // If we are doing a manual bond, complete the command
                if (manuallyBonding) {
                    manuallyBonding = false
                    completedCommand()
                }
            }
            BluetoothDevice.BOND_NONE -> {
                if (previousBondState == BluetoothDevice.BOND_BONDING) {
                    Timber.e("bonding failed for '%s', disconnecting device", name)
                    scope.launch { peripheralCallback.onBondingFailed(this@BluetoothPeripheral) }
                } else {
                    Timber.e("bond lost for '%s'", name)
                    bondLost = true

                    // Cancel the discoverServiceRunnable if it is still pending
                    cancelPendingServiceDiscovery()
                    scope.launch { peripheralCallback.onBondLost(this@BluetoothPeripheral) }
                }
                disconnect()
            }
        }
    }

    private val pairingRequestBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val receivedDevice = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return

            // Skip other devices
            if (!receivedDevice.address.equals(address, ignoreCase = true)) return
            val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.ERROR)
            Timber.d("pairing request received: " + pairingVariantToString(variant) + " (" + variant + ")")
            if (variant == PAIRING_VARIANT_PIN) {
                val pin = listener.getPincode(this@BluetoothPeripheral)
                if (pin != null) {
                    Timber.d("setting PIN code for this peripheral using '%s'", pin)
                    receivedDevice.setPin(pin.toByteArray())
                    abortBroadcast()
                }
            }
        }
    }

    fun setDevice(bluetoothDevice: BluetoothDevice) {
        device = bluetoothDevice
    }

    /**
     * Connect directly with the bluetooth device. This call will timeout in max 30 seconds (5 seconds on Samsung phones)
     */
    fun connect() {
        // Make sure we are disconnected before we start making a connection
        if (state == BluetoothProfile.STATE_DISCONNECTED) {
            scope.launch {
                delay(DIRECT_CONNECTION_DELAY_IN_MS)

                // Connect to device with autoConnect = false
                Timber.i("connect to '%s' (%s) using TRANSPORT_LE", name, address)
                registerBondingBroadcastReceivers()
                state = BluetoothProfile.STATE_CONNECTING
                discoveryStarted = false
                bluetoothGatt = device.connectGatt(context, false, bluetoothGattCallback, BluetoothDevice.TRANSPORT_LE)
                connectTimestamp = SystemClock.elapsedRealtime()
                startConnectionTimer(this@BluetoothPeripheral)
            }
        } else {
            Timber.e("peripheral '%s' not yet disconnected, will not connect", name)
        }
    }

    /**
     * Try to connect to a device whenever it is found by the OS. This call never times out.
     * Connecting to a device will take longer than when using connect()
     */
    fun autoConnect() {
        // Note that this will only work for devices that are known! After turning BT on/off Android doesn't know the device anymore!
        // https://stackoverflow.com/questions/43476369/android-save-ble-device-to-reconnect-after-app-close
        if (state == BluetoothProfile.STATE_DISCONNECTED) {
            scope.launch {
                // Connect to device with autoConnect = true
                Timber.i("autoConnect to '%s' (%s) using TRANSPORT_LE", name, address)
                registerBondingBroadcastReceivers()
                state = BluetoothProfile.STATE_CONNECTING
                discoveryStarted = false
                bluetoothGatt = device.connectGatt(context, true, bluetoothGattCallback, BluetoothDevice.TRANSPORT_LE)
                connectTimestamp = SystemClock.elapsedRealtime()
            }
        } else {
            Timber.e("peripheral '%s' not yet disconnected, will not connect", name)
        }
    }

    private fun registerBondingBroadcastReceivers() {
        context.registerReceiver(bondStateReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        context.registerReceiver(pairingRequestBroadcastReceiver, IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST))
    }

    /**
     * Create a bond with the peripheral.
     *
     *
     * If a (auto)connect has been issued, the bonding command will be enqueued and you will
     * receive updates via the [BluetoothPeripheralCallback]. Otherwise the bonding will
     * be done immediately and no updates via the callback will happen.
     *
     * @return true if bonding was started/enqueued, false if not
     */
    fun createBond(): Boolean {
        // Check if we have a Gatt object
        if (bluetoothGatt == null) {
            // No gatt object so no connection issued, do create bond immediately
            return device.createBond()
        }

        // Enqueue the bond command because a connection has been issued or we are already connected
        val result = commandQueue.add(Runnable {
            manuallyBonding = true
            if (!device.createBond()) {
                Timber.e("bonding failed for %s", address)
                completedCommand()
            } else {
                Timber.d("manually bonding %s", address)
                nrTries++
            }
        })
        if (result) {
            nextCommand()
        } else {
            Timber.e("could not enqueue bonding command")
        }
        return result
    }

    /**
     * Cancel an active or pending connection.
     *
     *
     * This operation is asynchronous and you will receive a callback on onDisconnectedPeripheral.
     */
    fun cancelConnection() {
        // Check if we have a Gatt object
        if (bluetoothGatt == null) {
            Timber.w("cannot cancel connection because no connection attempt is made yet")
            return
        }

        // Check if we are not already disconnected or disconnecting
        if (state == BluetoothProfile.STATE_DISCONNECTED || state == BluetoothProfile.STATE_DISCONNECTING) {
            return
        }

        // Cancel the connection timer
        cancelConnectionTimer()

        // Check if we are in the process of connecting
        if (state == BluetoothProfile.STATE_CONNECTING) {
            // Cancel the connection by calling disconnect
            disconnect()

            // Since we will not get a callback on onConnectionStateChange for this, we issue the disconnect ourselves
            scope.launch {
                delay(50)
                bluetoothGattCallback.onConnectionStateChange(bluetoothGatt, HciStatus.SUCCESS.value, BluetoothProfile.STATE_DISCONNECTED)
            }
        } else {
            // Cancel active connection and onConnectionStateChange will be called by Android
            disconnect()
        }
    }

    /**
     * Disconnect the bluetooth peripheral.
     *
     *
     * When the disconnection has been completed [BluetoothCentralManagerCallback.onDisconnectedPeripheral] will be called.
     */
    private fun disconnect() {
        if (state == BluetoothProfile.STATE_CONNECTED || state == BluetoothProfile.STATE_CONNECTING) {
            state = BluetoothProfile.STATE_DISCONNECTING
            scope.launch {
                if (state == BluetoothProfile.STATE_DISCONNECTING && bluetoothGatt != null) {
                    bluetoothGatt!!.disconnect()
                    Timber.i("force disconnect '%s' (%s)", name, address)
                }
            }
        } else {
            listener.disconnected(this@BluetoothPeripheral, HciStatus.SUCCESS)
        }
    }

    fun disconnectWhenBluetoothOff() {
        bluetoothGatt = null
        completeDisconnect(true, HciStatus.SUCCESS)
    }

    /**
     * Complete the disconnect after getting connectionstate == disconnected
     */
    private fun completeDisconnect(notify: Boolean, status: HciStatus) {
        if (bluetoothGatt != null) {
            bluetoothGatt!!.close()
            bluetoothGatt = null
        }
        commandQueue.clear()
        commandQueueBusy = false
        notifyingCharacteristics.clear()
        try {
            context.unregisterReceiver(bondStateReceiver)
            context.unregisterReceiver(pairingRequestBroadcastReceiver)
        } catch (e: IllegalArgumentException) {
            // In case bluetooth is off, unregisering broadcast receivers may fail
        }
        bondLost = false
        if (notify) {
            listener.disconnected(this@BluetoothPeripheral, status)
        }
    }

    /**
     * Get the mac address of the bluetooth peripheral.
     *
     * @return Address of the bluetooth peripheral
     */
    val address: String
        get() = device.address

    /**
     * Get the type of the peripheral.
     *
     * @return the PeripheralType
     */
    val type: PeripheralType
        get() = PeripheralType.fromValue(device.type)// Cache the name so that we even know it when bluetooth is switched off

    /**
     * Get the name of the bluetooth peripheral.
     *
     * @return name of the bluetooth peripheral
     */
    val name: String
        get() {
            val name = device.name
            if (name != null) {
                // Cache the name so that we even know it when bluetooth is switched off
                cachedName = name
                return name
            }
            return cachedName
        }

    /**
     * Get the bond state of the bluetooth peripheral.
     *
     * @return the bond state
     */
    val bondState: BondState
        get() = BondState.fromValue(device.bondState)

    /**
     * Get the services supported by the connected bluetooth peripheral.
     * Only services that are also supported by [BluetoothCentralManager] are included.
     *
     * @return Supported services.
     */
    val services: List<BluetoothGattService>
        get() = bluetoothGatt?.services ?: emptyList()

    /**
     * Get the BluetoothGattService object for a service UUID.
     *
     * @param serviceUUID the UUID of the service
     * @return the BluetoothGattService object for the service UUID or null if the peripheral does not have a service with the specified UUID
     */
    fun getService(serviceUUID: UUID): BluetoothGattService? {
        return bluetoothGatt?.getService(serviceUUID)
    }

    /**
     * Get the BluetoothGattCharacteristic object for a characteristic UUID.
     *
     * @param serviceUUID        the service UUID the characteristic is part of
     * @param characteristicUUID the UUID of the chararacteristic
     * @return the BluetoothGattCharacteristic object for the characteristic UUID or null if the peripheral does not have a characteristic with the specified UUID
     */
    fun getCharacteristic(serviceUUID: UUID, characteristicUUID: UUID): BluetoothGattCharacteristic? {
        return getService(serviceUUID)?.getCharacteristic(characteristicUUID)
    }

    /**
     * Returns the connection state of the peripheral.
     *
     * @return the connection state.
     */
    fun getState(): ConnectionState {
        return ConnectionState.fromValue(state)
    }

    /**
     * Get maximum length of byte array that can be written depending on WriteType
     *
     *
     *
     * This value is derived from the current negotiated MTU or the maximum characteristic length (512)
     */
    fun getMaximumWriteValueLength(writeType: WriteType): Int {
        return when (writeType) {
            WriteType.WITH_RESPONSE -> 512
            WriteType.SIGNED -> currentMtu - 15
            else -> currentMtu - 3
        }
    }

    /**
     * Boolean to indicate if the specified characteristic is currently notifying or indicating.
     *
     * @param characteristic the characteristic to check
     * @return true if the characteristic is notifying or indicating, false if it is not
     */
    fun isNotifying(characteristic: BluetoothGattCharacteristic): Boolean {
        return notifyingCharacteristics.contains(characteristic)
    }

    /**
     * Get all notifying/indicating characteristics
     *
     * @return Set of characteristics or empty set
     */
    fun getNotifyingCharacteristics(): Set<BluetoothGattCharacteristic> {
        return Collections.unmodifiableSet(notifyingCharacteristics)
    }

    private val isConnected: Boolean
        get() = bluetoothGatt != null && state == BluetoothProfile.STATE_CONNECTED

    private fun notConnected(): Boolean {
        return !isConnected
    }

    /**
     * Check if the peripheral is uncached by the Android BLE stack
     *
     * @return true if unchached, otherwise false
     */
    val isUncached: Boolean
        get() = type == PeripheralType.UNKNOWN

    /**
     * Read the value of a characteristic.
     *
     * The characteristic must support reading it, otherwise the operation will not be enqueued.
     *
     * @param serviceUUID        the service UUID the characteristic belongs to
     * @param characteristicUUID the characteristic's UUID
     * @return the bytes that were read or an empty byte array if the characteristic was not found
     * @throws IllegalArgumentException if the characteristic is not readable
     */
    suspend fun readCharacteristic(serviceUUID: UUID, characteristicUUID: UUID): ByteArray {
        val characteristic = getCharacteristic(serviceUUID, characteristicUUID)
        return if (characteristic != null) {
            readCharacteristic(characteristic)
        } else {
            ByteArray(0)
        }
    }

    /**
     * Read the value of a characteristic.
     *
     * @param characteristic Specifies the characteristic to read.
     * @return the bytes that were read or an empty byte array if the characteristic was not found
     * @throws IllegalArgumentException if the characteristic is not readable
     */
    suspend fun readCharacteristic(characteristic: BluetoothGattCharacteristic): ByteArray =
        suspendCoroutine {
            try {
                val result = readCharacteristic(characteristic, object : BluetoothPeripheralCallback() {
                    override fun onCharacteristicRead(peripheral: BluetoothPeripheral, value: ByteArray, characteristic: BluetoothGattCharacteristic, status: GattStatus) {
                        if (status == GattStatus.SUCCESS) {
                            it.resume(value)
                        } else {
                            it.resumeWithException(GattException(status))
                        }
                    }
                })
                if (!result) {
                    it.resume(ByteArray(0))
                }
            } catch (e: IllegalArgumentException) {
                it.resumeWithException(e)
            }
        }

    /**
     * Read the value of a characteristic.
     *
     * The characteristic must support reading it, otherwise the operation will not be enqueued.
     * [BluetoothPeripheralCallback.onCharacteristicUpdate]   will be triggered as a result of this call.
     *
     * @param characteristic Specifies the characteristic to read.
     * @return true if the operation was enqueued, false if the characteristic does not support reading or the characteristic was invalid
     */
    private fun readCharacteristic(characteristic: BluetoothGattCharacteristic, resultCallback: BluetoothPeripheralCallback): Boolean {
        require(characteristic.supportsReading()) { "characteristic does not have read property" }
        if (notConnected()) return false

        val result = commandQueue.add(Runnable {
            if (isConnected) {
                currentResultCallback = resultCallback
                if (bluetoothGatt!!.readCharacteristic(characteristic)) {
                    Timber.d("reading characteristic <%s>", characteristic.uuid)
                    nrTries++
                } else {
                    Timber.e("readCharacteristic failed for characteristic: %s", characteristic.uuid)
                    completedCommand()
                }
            } else {
                completedCommand()
            }
        })
        if (result) {
            nextCommand()
        } else {
            Timber.e("could not enqueue read characteristic command")
        }
        return result
    }


    suspend fun writeCharacteristic(serviceUUID: UUID, characteristicUUID: UUID, value: ByteArray, writeType: WriteType): ByteArray {
        val characteristic = getCharacteristic(serviceUUID, characteristicUUID)
        return if (characteristic != null) {
            writeCharacteristic(characteristic, value, writeType)
        } else {
            ByteArray(0)
        }
    }


    /**
     * Write a value to a characteristic using the specified write type.
     *
     * All parameters must have a valid value in order for the operation to be enqueued.
     * If the characteristic does not support writing with the specified writeType, the operation will not be enqueued.
     *
     * @param characteristic the characteristic to write to
     * @param value              the byte array to write
     * @param writeType          the write type to use when writing. Must be WRITE_TYPE_DEFAULT, WRITE_TYPE_NO_RESPONSE or WRITE_TYPE_SIGNED
     * @return the byte array that was written or an empty byte array if the characteristic was not valid
     */
    suspend fun writeCharacteristic(characteristic: BluetoothGattCharacteristic, value: ByteArray, writeType: WriteType): ByteArray =
        suspendCoroutine {
            try {
                val result = writeCharacteristic(characteristic, value, writeType, object : BluetoothPeripheralCallback() {
                    override fun onCharacteristicWrite(peripheral: BluetoothPeripheral, value: ByteArray, characteristic: BluetoothGattCharacteristic, status: GattStatus) {
                        if (status == GattStatus.SUCCESS) {
                            it.resume(value)
                        } else {
                            it.resumeWithException(GattException(status))
                        }
                    }
                })

                if (!result) {
                    it.resume(ByteArray(0))
                }
            } catch (e: IllegalArgumentException) {
                it.resumeWithException(e)
            }
        }


    /**
     * Write a value to a characteristic using the specified write type.
     *
     *
     * All parameters must have a valid value in order for the operation
     * to be enqueued. If the characteristic does not support writing with the specified writeType, the operation will not be enqueued.
     * The length of the byte array to write must be between 1 and getMaximumWriteValueLength(writeType).
     *
     *
     * [BluetoothPeripheralCallback.onCharacteristicWrite] will be triggered as a result of this call.
     *
     * @param characteristic the characteristic to write to
     * @param value          the byte array to write
     * @param writeType      the write type to use when writing.
     * @return true if a write operation was succesfully enqueued, otherwise false
     */
    private fun writeCharacteristic(characteristic: BluetoothGattCharacteristic, value: ByteArray, writeType: WriteType, resultCallback: BluetoothPeripheralCallback): Boolean {
        require(isConnected) { PERIPHERAL_NOT_CONNECTED }
        require(value.isNotEmpty()) { VALUE_BYTE_ARRAY_IS_EMPTY }
        require(value.size <= getMaximumWriteValueLength(writeType)) { VALUE_BYTE_ARRAY_IS_TOO_LONG }
        require(characteristic.supportsWriteType(writeType)) { "characteristic <${characteristic.uuid}> does not support writeType '$writeType'" }

        // Copy the value to avoid race conditions
        val bytesToWrite = copyOf(value)
        val result = commandQueue.add(Runnable {
            if (isConnected) {
                currentResultCallback = resultCallback
                currentWriteBytes = bytesToWrite
                characteristic.writeType = writeType.writeType
                if (willCauseLongWrite(bytesToWrite, writeType)) {
                    // Android will turn this into a Long Write because it is larger than the MTU - 3.
                    // When doing a Long Write the byte array will be automatically split in chunks of size MTU - 3.
                    // However, the peripheral's firmware must also support it, so it is not guaranteed to work.
                    // Long writes are also very inefficient because of the confirmation of each write operation.
                    // So it is better to increase MTU if possible. Hence a warning if this write becomes a long write...
                    // See https://stackoverflow.com/questions/48216517/rxandroidble-write-only-sends-the-first-20b
                    Timber.w("value byte array is longer than allowed by MTU, write will fail if peripheral does not support long writes")
                }
                characteristic.value = bytesToWrite
                if (bluetoothGatt?.writeCharacteristic(characteristic) ?: false) {
                    Timber.d("writing <%s> to characteristic <%s>", BluetoothBytesParser.bytes2String(bytesToWrite), characteristic.uuid)
                    nrTries++
                } else {
                    Timber.e("writeCharacteristic failed for characteristic: %s", characteristic.uuid)
                    completedCommand()
                }
            } else {
                completedCommand()
            }
        })
        if (result) {
            nextCommand()
        } else {
            Timber.e("could not enqueue write characteristic command")
        }
        return result
    }

    private fun willCauseLongWrite(value: ByteArray, writeType: WriteType): Boolean {
        return value.size > currentMtu - 3 && writeType == WriteType.WITH_RESPONSE
    }

    suspend fun readDescriptor(descriptor: BluetoothGattDescriptor): ByteArray =
        suspendCoroutine {
            try {
                val result = readDescriptor(descriptor, object : BluetoothPeripheralCallback() {
                    override fun onDescriptorRead(peripheral: BluetoothPeripheral, value: ByteArray, descriptor: BluetoothGattDescriptor, status: GattStatus) {
                        if (status == GattStatus.SUCCESS) {
                            it.resume(value)
                        } else {
                            it.resumeWithException(GattException(status))
                        }
                    }
                })

                if (!result) {
                    it.resume(ByteArray(0))
                }
            } catch (e: IllegalArgumentException) {
                it.resumeWithException(e)
            }
        }


    /**
     * Read the value of a descriptor.
     *
     * @param descriptor the descriptor to read
     * @return true if a write operation was succesfully enqueued, otherwise false
     */
    private fun readDescriptor(descriptor: BluetoothGattDescriptor, resultCallback: BluetoothPeripheralCallback): Boolean {
        require(isConnected) { PERIPHERAL_NOT_CONNECTED }

        val result = commandQueue.add(Runnable {
            if (isConnected) {
                currentResultCallback = resultCallback
                if (bluetoothGatt!!.readDescriptor(descriptor)) {
                    Timber.d("reading descriptor <%s>", descriptor.uuid)
                    nrTries++
                } else {
                    Timber.e("readDescriptor failed for characteristic: %s", descriptor.uuid)
                    completedCommand()
                }
            } else {
                completedCommand()
            }
        })
        if (result) {
            nextCommand()
        } else {
            Timber.e("could not enqueue read descriptor command")
        }
        return result
    }

    suspend fun writeDescriptor(descriptor: BluetoothGattDescriptor, value: ByteArray): ByteArray =
        suspendCoroutine {
            try {
                val result = writeDescriptor(descriptor, value, object : BluetoothPeripheralCallback() {
                    override fun onDescriptorWrite(peripheral: BluetoothPeripheral, value: ByteArray, descriptor: BluetoothGattDescriptor, status: GattStatus) {
                        if (status == GattStatus.SUCCESS) {
                            it.resume(value)
                        } else {
                            it.resumeWithException(GattException(status))
                        }
                    }
                })

                if (!result) {
                    it.resume(ByteArray(0))
                }
            } catch (e: IllegalArgumentException) {
                it.resumeWithException(e)
            }
        }

    /**
     * Write a value to a descriptor.
     *
     *
     * For turning on/off notifications use [BluetoothPeripheral.setNotify] instead.
     *
     * @param descriptor the descriptor to write to
     * @param value      the value to write
     * @return true if a write operation was succesfully enqueued, otherwise false
     */
    private fun writeDescriptor(descriptor: BluetoothGattDescriptor, value: ByteArray, resultCallback: BluetoothPeripheralCallback): Boolean {
        require(isConnected) { PERIPHERAL_NOT_CONNECTED }
        require(value.isNotEmpty()) { VALUE_BYTE_ARRAY_IS_EMPTY }
        require(value.size <= getMaximumWriteValueLength(WriteType.WITH_RESPONSE)) { VALUE_BYTE_ARRAY_IS_TOO_LONG }

        // Copy the value to avoid race conditions
        val bytesToWrite = copyOf(value)

        val result = commandQueue.add(Runnable {
            if (isConnected) {
                currentResultCallback = resultCallback
                currentWriteBytes = bytesToWrite
                descriptor.value = bytesToWrite
                if (bluetoothGatt!!.writeDescriptor(descriptor)) {
                    Timber.d("writing <%s> to descriptor <%s>", BluetoothBytesParser.bytes2String(bytesToWrite), descriptor.uuid)
                    nrTries++
                } else {
                    Timber.e("writeDescriptor failed for descriptor: %s", descriptor.uuid)
                    completedCommand()
                }
            } else {
                completedCommand()
            }
        })

        if (result) {
            nextCommand()
        } else {
            Timber.e("could not enqueue write descriptor command")
        }
        return result
    }


    suspend fun observe(characteristic: BluetoothGattCharacteristic, callback: (value: ByteArray) -> Unit): Boolean =
        suspendCoroutine {
            try {
                val result = setNotify(characteristic, true, object : BluetoothPeripheralCallback() {
                    override fun onNotificationStateUpdate(peripheral: BluetoothPeripheral, characteristic: BluetoothGattCharacteristic, status: GattStatus) {
                        if (status == GattStatus.SUCCESS) {
                            observeMap[characteristic] = callback
                            Timber.d("observing <${characteristic.uuid}> succeeded")
                            it.resume(true)
                        } else {
                            it.resumeWithException(GattException(status))
                        }
                    }
                })

                if (!result) {
                    it.resume(false)
                }
            } catch (e : IllegalArgumentException) {
                it.resumeWithException(e)
            }
        }

    suspend fun stopObserving(characteristic: BluetoothGattCharacteristic): Boolean =
        suspendCoroutine {
            try {
                val result = setNotify(characteristic, false, object : BluetoothPeripheralCallback() {
                    override fun onNotificationStateUpdate(peripheral: BluetoothPeripheral, characteristic: BluetoothGattCharacteristic, status: GattStatus) {
                        if (status == GattStatus.SUCCESS) {
                            observeMap.remove(characteristic)
                            it.resume(true)
                        } else {
                            it.resumeWithException(GattException(status))
                        }
                    }
                })

                if (!result) {
                    it.resume(false)
                }
            } catch (e : IllegalArgumentException) {
                it.resumeWithException(e)
            }
        }

    /**
     * Set the notification state of a characteristic to 'on' or 'off'. The characteristic must support notifications or indications.
     *
     * @param serviceUUID        the service UUID the characteristic belongs to
     * @param characteristicUUID the characteristic's UUID
     * @param enable             true for setting notification on, false for turning it off
     * @return true if the operation was enqueued, false the characteristic could not be found or does not support notifications
     */
    private fun setNotify(serviceUUID: UUID, characteristicUUID: UUID, enable: Boolean, resultCallback: BluetoothPeripheralCallback): Boolean {
        return getCharacteristic(serviceUUID, characteristicUUID)?.let { setNotify(it, enable, resultCallback) } ?: false
    }

    /**
     * Set the notification state of a characteristic to 'on' or 'off'. The characteristic must support notifications or indications.
     *
     * [BluetoothPeripheralCallback.onNotificationStateUpdate] will be triggered as a result of this call.
     *
     * @param characteristic the characteristic to turn notification on/off for
     * @param enable         true for setting notification on, false for turning it off
     * @return true if the operation was enqueued, false if the characteristic doesn't support notification or indications or
     */
    private fun setNotify(characteristic: BluetoothGattCharacteristic, enable: Boolean, resultCallback: BluetoothPeripheralCallback): Boolean {
        require(isConnected) { PERIPHERAL_NOT_CONNECTED }
        require(characteristic.supportsNotifying()) { "characteristic <${characteristic.uuid}> does not have notify or indicate property" }

        val descriptor = characteristic.getDescriptor(CCC_DESCRIPTOR_UUID)
        if (descriptor == null) {
            Timber.e("could not get CCC descriptor for characteristic %s", characteristic.uuid)
            return false
        }

        // Check if characteristic has NOTIFY or INDICATE properties and set the correct byte value to be written
        val properties = characteristic.properties
        val value = when {
            properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY > 0 -> {
                ENABLE_NOTIFICATION_VALUE
            }
            properties and BluetoothGattCharacteristic.PROPERTY_INDICATE > 0 -> {
                ENABLE_INDICATION_VALUE
            }
            else -> {
                Timber.e("characteristic %s does not have notify or indicate property", characteristic.uuid)
                return false
            }
        }
        val finalValue = if (enable) value else DISABLE_NOTIFICATION_VALUE

        val result = commandQueue.add(Runnable {
            if (notConnected()) {
                completedCommand()
                return@Runnable
            }

            // First try to set notification for Gatt object
            currentResultCallback = resultCallback
            if (!bluetoothGatt!!.setCharacteristicNotification(characteristic, enable)) {
                Timber.e("setCharacteristicNotification failed for characteristic: %s", characteristic.uuid)
                completedCommand()
                return@Runnable
            }

            currentWriteBytes = finalValue
            descriptor.value = finalValue
            if (bluetoothGatt!!.writeDescriptor(descriptor)) {
                nrTries++
            } else {
                Timber.e("writeDescriptor failed for descriptor: %s", descriptor.uuid)
                completedCommand()
            }
        })
        if (result) {
            nextCommand()
        } else {
            Timber.e("could not enqueue setNotify command")
        }
        return result
    }

    suspend fun readRemoteRssi(): Int =
        suspendCoroutine {
            val result = readRemoteRssi(object : BluetoothPeripheralCallback() {
                override fun onReadRemoteRssi(peripheral: BluetoothPeripheral, rssi: Int, status: GattStatus) {
                    if (status == GattStatus.SUCCESS) {
                        it.resume(rssi)
                    } else {
                        it.resumeWithException(GattException(status))
                    }
                }
            })

            if (!result) {
                it.resume(-255)
            }
        }

    /**
     * Read the RSSI for a connected remote peripheral.
     *
     *
     * [BluetoothPeripheralCallback.onReadRemoteRssi] will be triggered as a result of this call.
     *
     * @return true if the operation was enqueued, false otherwise
     */
    fun readRemoteRssi(resultCallback: BluetoothPeripheralCallback): Boolean {
        require(isConnected) { PERIPHERAL_NOT_CONNECTED }

        val result = commandQueue.add(Runnable {
            if (isConnected) {
                currentResultCallback = resultCallback
                if (!bluetoothGatt!!.readRemoteRssi()) {
                    Timber.e("readRemoteRssi failed")
                    completedCommand()
                }
            } else {
                completedCommand()
            }
        })
        if (result) {
            nextCommand()
        } else {
            Timber.e("could not enqueue readRemoteRssi command")
        }
        return result
    }

    suspend fun requestMtu(mtu: Int): Int =
        suspendCoroutine {
            try {
                val result = requestMtu(mtu, object : BluetoothPeripheralCallback() {
                    override fun onMtuChanged(peripheral: BluetoothPeripheral, mtu: Int, status: GattStatus) {
                        it.resume(mtu)
                    }
                })

                if (!result) {
                    it.resume(currentMtu)
                }
            } catch (exception: IllegalArgumentException) {
                it.resumeWithException(exception)
            }
        }

    /**
     * Request an MTU size used for a given connection.
     *
     *
     * When performing a write request operation (write without response),
     * the data sent is truncated to the MTU size. This function may be used
     * to request a larger MTU size to be able to send more data at once.
     *
     *
     * Note that requesting an MTU should only take place once per connection, according to the Bluetooth standard.
     *
     * [BluetoothPeripheralCallback.onMtuChanged] will be triggered as a result of this call.
     *
     * @param mtu the desired MTU size
     * @return true if the operation was enqueued, false otherwise
     */
    private fun requestMtu(mtu: Int, resultCallback: BluetoothPeripheralCallback): Boolean {
        require(mtu in DEFAULT_MTU..MAX_MTU) { "mtu must be between 23 and 517" }
        require(isConnected) { PERIPHERAL_NOT_CONNECTED }

        val result = commandQueue.add(Runnable {
            if (isConnected) {
                currentResultCallback = resultCallback
                if (bluetoothGatt!!.requestMtu(mtu)) {
                    currentCommand = REQUEST_MTU_COMMAND
                    Timber.i("requesting MTU of %d", mtu)
                } else {
                    Timber.e("requestMtu failed")
                    completedCommand()
                }
            } else {
                completedCommand()
            }
        })
        if (result) {
            nextCommand()
        } else {
            Timber.e("could not enqueue requestMtu command")
        }
        return result
    }


    suspend fun requestConnectionPriority(priority: ConnectionPriority): Boolean =
        suspendCoroutine {
            try {
                val result = requestConnectionPriority(priority, object : BluetoothPeripheralCallback() {
                    override fun onRequestedConnectionPriority(peripheral: BluetoothPeripheral) {
                        it.resume(true)
                    }
                })

                if (!result) {
                    it.resume(false)
                }
            } catch (e: IllegalArgumentException) {
                it.resumeWithException(e)
            }
        }

    /**
     * Request a different connection priority.
     *
     * @param priority the requested connection priority
     * @return true if request was enqueued, false if not
     */
    private fun requestConnectionPriority(priority: ConnectionPriority, resultCallback: BluetoothPeripheralCallback): Boolean {
        require(isConnected) { PERIPHERAL_NOT_CONNECTED }

        val result = commandQueue.add(Runnable {
            if (isConnected) {
                currentResultCallback = resultCallback
                if (bluetoothGatt!!.requestConnectionPriority(priority.value)) {
                    Timber.d("requesting connection priority %s", priority)
                } else {
                    Timber.e("could not request connection priority")
                }
            }

            scope.launch {
                delay(500)
                currentResultCallback.onRequestedConnectionPriority(this@BluetoothPeripheral)
                completedCommand()
            }
        })

        if (result) {
            nextCommand()
        } else {
            Timber.e("could not enqueue request connection priority command")
        }
        return result
    }

    /**
     * Set the preferred connection PHY for this app. Please note that this is just a
     * recommendation, whether the PHY change will happen depends on other applications preferences,
     * local and remote controller capabilities. Controller can override these settings.
     *
     *
     * [BluetoothPeripheralCallback.onPhyUpdate] will be triggered as a result of this call, even
     * if no PHY change happens. It is also triggered when remote device updates the PHY.
     *
     * @param txPhy      the desired TX PHY
     * @param rxPhy      the desired RX PHY
     * @param phyOptions the desired optional sub-type for PHY_LE_CODED
     * @return true if request was enqueued, false if not
     */
    fun setPreferredPhy(txPhy: PhyType, rxPhy: PhyType, phyOptions: PhyOptions): Boolean {

        if (notConnected()) {
            Timber.e(PERIPHERAL_NOT_CONNECTED)
            return false
        }

        val result = commandQueue.add(Runnable {
            if (isConnected) {
                Timber.i("setting preferred Phy: tx = %s, rx = %s, options = %s", txPhy, rxPhy, phyOptions)
                bluetoothGatt?.setPreferredPhy(txPhy.mask, rxPhy.mask, phyOptions.value)
            }

            // complete command immediately as this command is not blocking
            completedCommand()
        })

        if (result) {
            nextCommand()
        } else {
            Timber.e("could not enqueue setPreferredPhy command")
        }
        return result
    }

    /**
     * Read the current transmitter PHY and receiver PHY of the connection. The values are returned
     * in [BluetoothPeripheralCallback.onPhyUpdate]
     */
    fun readPhy(): Boolean {
        require(isConnected) { PERIPHERAL_NOT_CONNECTED }

        val result = commandQueue.add(Runnable {
            if (isConnected) {
                bluetoothGatt?.readPhy()
                Timber.d("reading Phy")
                return@Runnable
            } else {
                completedCommand()
            }
        })
        if (result) {
            nextCommand()
        } else {
            Timber.e("could not enqueue readyPhy command")
        }
        return result
    }

    /**
     * Asynchronous method to clear the services cache. Make sure to add a delay when using this!
     *
     * @return true if the method was executed, false if not executed
     */
    fun clearServicesCache(): Boolean {
        if (bluetoothGatt == null) return false
        var result = false
        try {
            val refreshMethod = bluetoothGatt!!.javaClass.getMethod("refresh")
            if (refreshMethod != null) {
                result = refreshMethod.invoke(bluetoothGatt) as Boolean
            }
        } catch (e: Exception) {
            Timber.e("could not invoke refresh method")
        }
        return result
    }

    /**
     * The current command has been completed, move to the next command in the queue (if any)
     */
    private fun completedCommand() {
        isRetrying = false
        commandQueue.poll()
        commandQueueBusy = false
        nextCommand()
    }

    /**
     * Execute the next command in the subscribe queue.
     * A queue is used because the calls have to be executed sequentially.
     * If the read or write fails, the next command in the queue is executed.
     */
    private fun nextCommand() {
        synchronized(this) {

            // If there is still a command being executed, then bail out
            if (commandQueueBusy) return

            // Check if there is something to do at all
            val bluetoothCommand = commandQueue.peek() ?: return

            // Check if we still have a valid gatt object
            if (bluetoothGatt == null) {
                Timber.e("gatt is 'null' for peripheral '%s', clearing command queue", address)
                commandQueue.clear()
                commandQueueBusy = false
                return
            }

            // Execute the next command in the queue
            commandQueueBusy = true
            if (!isRetrying) {
                nrTries = 0
            }
            scope.launch {
                try {
                    bluetoothCommand.run()
                } catch (ex: Exception) {
                    Timber.e(ex, "command exception for device '%s'", name)
                    completedCommand()
                }
            }
        }
    }

    private fun pairingVariantToString(variant: Int): String {
        return when (variant) {
            PAIRING_VARIANT_PIN -> "PAIRING_VARIANT_PIN"
            PAIRING_VARIANT_PASSKEY -> "PAIRING_VARIANT_PASSKEY"
            PAIRING_VARIANT_PASSKEY_CONFIRMATION -> "PAIRING_VARIANT_PASSKEY_CONFIRMATION"
            PAIRING_VARIANT_CONSENT -> "PAIRING_VARIANT_CONSENT"
            PAIRING_VARIANT_DISPLAY_PASSKEY -> "PAIRING_VARIANT_DISPLAY_PASSKEY"
            PAIRING_VARIANT_DISPLAY_PIN -> "PAIRING_VARIANT_DISPLAY_PIN"
            PAIRING_VARIANT_OOB_CONSENT -> "PAIRING_VARIANT_OOB_CONSENT"
            else -> "UNKNOWN"
        }
    }

    interface InternalCallback {
        /**
         * [BluetoothPeripheral] has successfully connected.
         *
         * @param peripheral [BluetoothPeripheral] that connected.
         */
        fun connected(peripheral: BluetoothPeripheral)

        /**
         * Connecting with [BluetoothPeripheral] has failed.
         *
         * @param peripheral [BluetoothPeripheral] of which connect failed.
         */
        fun connectFailed(peripheral: BluetoothPeripheral, status: HciStatus)

        /**
         * [BluetoothPeripheral] has disconnected.
         *
         * @param peripheral [BluetoothPeripheral] that disconnected.
         */
        fun disconnected(peripheral: BluetoothPeripheral, status: HciStatus)
        fun getPincode(peripheral: BluetoothPeripheral): String?
    }

    private var timeoutJob : Job? = null
    private fun startConnectionTimer(peripheral: BluetoothPeripheral) {
        cancelConnectionTimer()
//        timeoutRunnable = Runnable {
//            Timber.e("connection timout, disconnecting '%s'", peripheral.name)
//            disconnect()
//            mainHandler.postDelayed({ bluetoothGattCallback.onConnectionStateChange(bluetoothGatt, HciStatus.CONNECTION_FAILED_ESTABLISHMENT.value, BluetoothProfile.STATE_DISCONNECTED) }, 50)
//            timeoutRunnable = null
//        }
//        mainHandler.postDelayed(timeoutRunnable!!, CONNECTION_TIMEOUT_IN_MS.toLong())

        timeoutJob = scope.launch {
            delay(CONNECTION_TIMEOUT_IN_MS)
            Timber.e("connection timout, disconnecting '%s'", peripheral.name)
            disconnect()
            scope.launch {
                delay(50)
                bluetoothGattCallback.onConnectionStateChange(bluetoothGatt, HciStatus.CONNECTION_FAILED_ESTABLISHMENT.value, BluetoothProfile.STATE_DISCONNECTED)
            }
        }
    }

    private fun cancelConnectionTimer() {
//        if (timeoutRunnable != null) {
//            mainHandler.removeCallbacks(timeoutRunnable!!)
//            timeoutRunnable = null
//        }
        timeoutJob?.cancel()
        timeoutJob = null
    }

    private val timoutThreshold: Int
        get() {
            val manufacturer = Build.MANUFACTURER
            return if (manufacturer.equals("samsung", ignoreCase = true)) {
                TIMEOUT_THRESHOLD_SAMSUNG
            } else {
                TIMEOUT_THRESHOLD_DEFAULT
            }
        }

    /**
     * Make a safe copy of a nullable byte array
     *
     * @param source byte array to copy
     * @return non-null copy of the source byte array or an empty array if source was null
     */
    private fun copyOf(source: ByteArray?): ByteArray {
        return if (source == null) ByteArray(0) else Arrays.copyOf(source, source.size)
    }

    /**
     * Make a byte array nonnull by either returning the original byte array if non-null or an empty bytearray
     *
     * @param source byte array to make nonnull
     * @return the source byte array or an empty array if source was null
     */
    fun nonnullOf(source: ByteArray?): ByteArray {
        return source ?: ByteArray(0)
    }

    companion object {
        private val CCC_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /**
         * Max MTU that Android can handle
         */
        const val MAX_MTU = 517

        // Minimal and default MTU
        private const val DEFAULT_MTU = 23

        // Maximum number of retries of commands
        private const val MAX_TRIES = 2

        // Delay to use when doing a connect
        private const val DIRECT_CONNECTION_DELAY_IN_MS = 100L

        // Timeout to use if no callback on onConnectionStateChange happens
        private const val CONNECTION_TIMEOUT_IN_MS = 35000L

        // Samsung phones time out after 5 seconds while most other phone time out after 30 seconds
        private const val TIMEOUT_THRESHOLD_SAMSUNG = 4500

        // Most other phone time out after 30 seconds
        private const val TIMEOUT_THRESHOLD_DEFAULT = 25000

        // When a bond is lost, the bluetooth stack needs some time to update its internal state
        private const val DELAY_AFTER_BOND_LOST = 1000L

        // Error message constants
        private const val PERIPHERAL_NOT_CONNECTED = "peripheral not connected"
        private const val VALUE_BYTE_ARRAY_IS_EMPTY = "value byte array is empty"
        private const val VALUE_BYTE_ARRAY_IS_TOO_LONG = "value byte array is too long"

        // String constants for commands where the callbacks can also happen because the remote peripheral initiated the command
        private const val REQUEST_MTU_COMMAND = "REQUEST_MTU"

        // Pairing variant codes
        private const val PAIRING_VARIANT_PIN = 0
        private const val PAIRING_VARIANT_PASSKEY = 1
        private const val PAIRING_VARIANT_PASSKEY_CONFIRMATION = 2
        private const val PAIRING_VARIANT_CONSENT = 3
        private const val PAIRING_VARIANT_DISPLAY_PASSKEY = 4
        private const val PAIRING_VARIANT_DISPLAY_PIN = 5
        private const val PAIRING_VARIANT_OOB_CONSENT = 6
    }
}