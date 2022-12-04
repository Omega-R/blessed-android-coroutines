package com.welie.blessed

import android.content.Context
import android.util.Log
import com.welie.blessed.BluetoothBytesParser.Companion.FORMAT_FLOAT
import io.mockk.*
import org.junit.*
import org.junit.Assert.*
import java.nio.ByteOrder


class BluetoothBytesParserTest {
    private val mockContext = mockk<Context>()

    @Test
    fun first_test() {
        val byteParser = BluetoothBytesParser(byteArrayOf(0xFF.toByte(), 0x00, 0x01, 0x6c),  ByteOrder.BIG_ENDIAN)
        assertEquals(byteParser.getFloatValue(FORMAT_FLOAT), 36.4f)
    }

    @Test
    fun second_test() {
        var parser = BluetoothBytesParser(ByteOrder.LITTLE_ENDIAN)
        parser.setFloatValue(364, -1, FORMAT_FLOAT, 0)
        parser.offset = 0
//        assertEquals(36.4f, parser.getFloatValue(FORMAT_FLOAT))
        val asstring = parser.value.asHexString()
        assertEquals(36.4, parser.getFloat(), 0.01)

        parser = BluetoothBytesParser(ByteOrder.LITTLE_ENDIAN)
        parser.setFloatValue(5.3f, 1)
        parser.setFloatValue(36.86f, 2)

        parser.offset = 0
        assertEquals(5.3f, parser.getFloatValue(FORMAT_FLOAT))
        assertEquals(36.86f, parser.getFloatValue(FORMAT_FLOAT))
    }
}