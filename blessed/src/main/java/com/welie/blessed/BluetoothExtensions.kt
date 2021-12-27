package com.welie.blessed

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.content.Context
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

private const val TRANSPORT_LE = 2

fun BluetoothDevice?.connectGattHelper(
    context: Context,
    tag: String,
    autoConnect: Boolean,
    bluetoothGattCallback: BluetoothGattCallback,
): BluetoothGatt? {
    if (this == null) {
        return null
    }
    /*
          This bug workaround was taken from the Polidea RxAndroidBle
          Issue that caused a race condition mentioned below was fixed in 7.0.0_r1
          https://android.googlesource.com/platform/frameworks/base/+/android-7.0.0_r1/core/java/android/bluetooth/BluetoothGatt.java#649
          compared to
          https://android.googlesource.com/platform/frameworks/base/+/android-6.0.1_r72/core/java/android/bluetooth/BluetoothGatt.java#739
          issue: https://android.googlesource.com/platform/frameworks/base/+/d35167adcaa40cb54df8e392379dfdfe98bcdba2%5E%21/#F0
          */return if (VERSION.SDK_INT >= VERSION_CODES.N || !autoConnect) {
        connectGattCompat(context, bluetoothGattCallback, this, autoConnect)
    } else try {
        val iBluetoothGatt = getIBluetoothGatt(getIBluetoothManager(context))
        if (iBluetoothGatt == null) {
            Logger.e(tag, "could not get iBluetoothGatt object")
            return connectGattCompat(context, bluetoothGattCallback, this, true)
        }
        val bluetoothGatt = createBluetoothGatt(context, iBluetoothGatt, this)
        if (bluetoothGatt == null) {
            Logger.e(tag, "could not create BluetoothGatt object")
            return connectGattCompat(context, bluetoothGattCallback, this, true)
        }
        val connectedSuccessfully: Boolean = connectUsingReflection(this, bluetoothGatt, bluetoothGattCallback, true)
        if (!connectedSuccessfully) {
            Logger.i(tag, "connection using reflection failed, closing gatt")
            bluetoothGatt.close()
        }
        bluetoothGatt
    } catch (exception: NoSuchMethodException) {
        Logger.e(tag, "error during reflection")
        connectGattCompat(context, bluetoothGattCallback, this, true)
    } catch (exception: IllegalAccessException) {
        Logger.e(tag, "error during reflection")
        connectGattCompat(context, bluetoothGattCallback, this, true)
    } catch (exception: IllegalArgumentException) {
        Logger.e(tag, "error during reflection")
        connectGattCompat(context, bluetoothGattCallback, this, true)
    } catch (exception: InvocationTargetException) {
        Logger.e(tag, "error during reflection")
        connectGattCompat(context, bluetoothGattCallback, this, true)
    } catch (exception: InstantiationException) {
        Logger.e(tag, "error during reflection")
        connectGattCompat(context, bluetoothGattCallback, this, true)
    } catch (exception: NoSuchFieldException) {
        Logger.e(tag, "error during reflection")
        connectGattCompat(context, bluetoothGattCallback, this, true)
    }
}

private fun connectGattCompat(
    context: Context,
    bluetoothGattCallback: BluetoothGattCallback,
    device: BluetoothDevice,
    autoConnect: Boolean,
): BluetoothGatt? {
    if (VERSION.SDK_INT >= VERSION_CODES.M) {
        return device.connectGatt(context, autoConnect, bluetoothGattCallback, TRANSPORT_LE)
    } else {
        // Try to call connectGatt with transport parameter using reflection
        try {
            val connectGattMethod: Method = device.javaClass.getMethod("connectGatt",
                Context::class.java,
                Boolean::class.javaPrimitiveType,
                BluetoothGattCallback::class.java,
                Int::class.javaPrimitiveType)
            try {
                return connectGattMethod.invoke(
                    device,
                    context,
                    autoConnect,
                    bluetoothGattCallback,
                    TRANSPORT_LE
                ) as? BluetoothGatt?
            } catch (e: IllegalAccessException) {
                e.printStackTrace()
            } catch (e: InvocationTargetException) {
                e.printStackTrace()
            }
        } catch (e: NoSuchMethodException) {
            e.printStackTrace()
        }
    }
    // Fallback on connectGatt without transport parameter
    return device.connectGatt(context, autoConnect, bluetoothGattCallback)
}

@Throws(NoSuchMethodException::class, InvocationTargetException::class, IllegalAccessException::class)
private fun getIBluetoothGatt(iBluetoothManager: Any?): Any? {
    if (iBluetoothManager == null) {
        return null
    }
    val getBluetoothGattMethod: Method = getMethodFromClass(iBluetoothManager.javaClass, "getBluetoothGatt")
    return getBluetoothGattMethod.invoke(iBluetoothManager)
}

@Throws(NoSuchMethodException::class)
private fun getMethodFromClass(cls: Class<*>, methodName: String): Method {
    val method = cls.getDeclaredMethod(methodName)
    method.isAccessible = true
    return method
}

@Throws(NoSuchMethodException::class, InvocationTargetException::class, IllegalAccessException::class)
private fun getIBluetoothManager(context: Context): Any? {
    val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter() ?: return null
    val getBluetoothManagerMethod = getMethodFromClass(bluetoothAdapter.javaClass, "getBluetoothManager")
    return getBluetoothManagerMethod.invoke(bluetoothAdapter)
}

@Throws(IllegalAccessException::class, InvocationTargetException::class, InstantiationException::class)
private fun createBluetoothGatt(context: Context, iBluetoothGatt: Any, remoteDevice: BluetoothDevice): BluetoothGatt? {
    val bluetoothGattConstructor: Constructor<*> = BluetoothGatt::class.java.declaredConstructors[0]
    bluetoothGattConstructor.isAccessible = true
    return if (bluetoothGattConstructor.parameterTypes.size == 4) ({
        bluetoothGattConstructor.newInstance(context, iBluetoothGatt, remoteDevice, TRANSPORT_LE)
    }) as? BluetoothGatt? else ({
        bluetoothGattConstructor.newInstance(context, iBluetoothGatt, remoteDevice)
    }) as? BluetoothGatt?
}

@Throws(NoSuchMethodException::class,
    InvocationTargetException::class,
    IllegalAccessException::class,
    NoSuchFieldException::class)
private fun connectUsingReflection(
    device: BluetoothDevice,
    bluetoothGatt: BluetoothGatt,
    bluetoothGattCallback: BluetoothGattCallback,
    autoConnect: Boolean,
): Boolean {
    setAutoConnectValue(bluetoothGatt, autoConnect)
    val connectMethod = bluetoothGatt.javaClass.getDeclaredMethod("connect",
        Boolean::class.java,
        BluetoothGattCallback::class.java)
    connectMethod.isAccessible = true
    return connectMethod.invoke(bluetoothGatt, true, bluetoothGattCallback) as Boolean
}

@Throws(NoSuchFieldException::class, IllegalAccessException::class)
private fun setAutoConnectValue(bluetoothGatt: BluetoothGatt, autoConnect: Boolean) {
    val autoConnectField: Field = bluetoothGatt.javaClass.getDeclaredField("mAutoConnect")
    autoConnectField.isAccessible = true
    autoConnectField.setBoolean(bluetoothGatt, autoConnect)
}