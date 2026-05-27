package com.wearadb.adb

/**
 * ADB protocol constants and message format.
 * ADB protocol is transport-agnostic - same 24-byte header format over USB and TCP.
 */
object UsbAdbProtocol {
    // ADB command tags (4 bytes, little-endian)
    const val CMD_CNXN = 0x4e584e43  // "CNXN" - Connect
    const val CMD_AUTH = 0x48545541  // "AUTH" - Authentication
    const val CMD_OPEN = 0x4e45504f  // "OPEN" - Open stream
    const val CMD_OKAY = 0x59414b4f  // "OKAY" - Acknowledge
    const val CMD_CLSE = 0x45534c43  // "CLSE" - Close stream
    const val CMD_WRTE = 0x45545257  // "WRTE" - Write data
    const val CMD_STLS = 0x534c5453  // "STLS" - Start TLS (Android 9+)

    // AUTH sub-types (arg0)
    const val AUTH_TYPE_TOKEN = 1
    const val AUTH_TYPE_SIGNATURE = 2
    const val AUTH_TYPE_PUBLIC_KEY = 3

    // Protocol version
    const val A_VERSION = 0x01000001

    // USB ADB interface descriptor
    const val ADB_INTERFACE_CLASS = 255       // 0xFF
    const val ADB_INTERFACE_SUBCLASS = 66     // 0x42
    const val ADB_INTERFACE_PROTOCOL = 1      // 0x01

    const val HEADER_SIZE = 24

    data class AdbMessage(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val data: ByteArray = ByteArray(0)
    ) {
        fun toByteArray(): ByteArray {
            val buf = ByteArray(HEADER_SIZE + data.size)
            putIntLE(buf, 0, command)
            putIntLE(buf, 4, arg0)
            putIntLE(buf, 8, arg1)
            putIntLE(buf, 12, data.size)
            putIntLE(buf, 16, dataChecksum())
            putIntLE(buf, 20, command xor -1)
            if (data.isNotEmpty()) {
                data.copyInto(buf, HEADER_SIZE)
            }
            return buf
        }

        /** Returns only the 24-byte header (without payload). */
        fun toHeaderBytes(): ByteArray {
            val buf = ByteArray(HEADER_SIZE)
            putIntLE(buf, 0, command)
            putIntLE(buf, 4, arg0)
            putIntLE(buf, 8, arg1)
            putIntLE(buf, 12, data.size)
            putIntLE(buf, 16, dataChecksum())
            putIntLE(buf, 20, command xor -1)
            return buf
        }

        /** ADB checksum: sum of all payload bytes (unsigned). */
        private fun dataChecksum(): Int {
            var sum = 0
            for (b in data) sum += (b.toInt() and 0xFF)
            return sum
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AdbMessage) return false
            return command == other.command && arg0 == other.arg0 && arg1 == other.arg1
        }
        override fun hashCode(): Int = command * 31 + arg0 * 31 + arg1
    }

    fun putIntLE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset]     = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    fun getIntLE(buf: ByteArray, offset: Int): Int {
        return (buf[offset].toInt() and 0xFF) or
                ((buf[offset + 1].toInt() and 0xFF) shl 8) or
                ((buf[offset + 2].toInt() and 0xFF) shl 16) or
                ((buf[offset + 3].toInt() and 0xFF) shl 24)
    }

    fun parseMessage(buf: ByteArray, offset: Int = 0): Pair<AdbMessage, Int>? {
        if (buf.size - offset < HEADER_SIZE) return null
        val command = getIntLE(buf, offset)
        val arg0 = getIntLE(buf, offset + 4)
        val arg1 = getIntLE(buf, offset + 8)
        val dataLen = getIntLE(buf, offset + 12)
        if (buf.size - offset < HEADER_SIZE + dataLen) return null
        val data = if (dataLen > 0) {
            ByteArray(dataLen).also { buf.copyInto(it, 0, offset + HEADER_SIZE, offset + HEADER_SIZE + dataLen) }
        } else ByteArray(0)
        return AdbMessage(command, arg0, arg1, data) to (HEADER_SIZE + dataLen)
    }

    // Convenience constructors

    fun cnxnMessage(maxData: Int = 1048576): AdbMessage {
        // CNXN(version, maxdata, "host::\0")
        val hostBytes = "host::".toByteArray()
        val systemIdentity = ByteArray(hostBytes.size + 1)
        hostBytes.copyInto(systemIdentity)
        systemIdentity[hostBytes.size] = 0x00
        return AdbMessage(CMD_CNXN, A_VERSION, maxData, systemIdentity)
    }

    fun authToken(token: ByteArray): AdbMessage {
        return AdbMessage(CMD_AUTH, AUTH_TYPE_SIGNATURE, 0, token)
    }

    fun authPublicKey(keyBytes: ByteArray): AdbMessage {
        return AdbMessage(CMD_AUTH, AUTH_TYPE_PUBLIC_KEY, 0, keyBytes)
    }

    fun openMessage(localId: Int, destination: String): AdbMessage {
        val destBytes = destination.toByteArray()
        val data = ByteArray(destBytes.size + 1)
        destBytes.copyInto(data)
        data[destBytes.size] = 0x00
        return AdbMessage(CMD_OPEN, localId, 0, data)
    }

    fun okayMessage(localId: Int, remoteId: Int): AdbMessage {
        return AdbMessage(CMD_OKAY, localId, remoteId, ByteArray(0))
    }

    fun writeMessage(localId: Int, remoteId: Int, data: ByteArray): AdbMessage {
        return AdbMessage(CMD_WRTE, localId, remoteId, data)
    }

    fun closeMessage(localId: Int, remoteId: Int): AdbMessage {
        return AdbMessage(CMD_CLSE, localId, remoteId, ByteArray(0))
    }

    fun stlsMessage(): AdbMessage {
        return AdbMessage(CMD_STLS, A_VERSION, 0, ByteArray(0))
    }

    fun commandName(cmd: Int): String = when (cmd) {
        CMD_CNXN -> "CNXN"
        CMD_AUTH -> "AUTH"
        CMD_OPEN -> "OPEN"
        CMD_OKAY -> "OKAY"
        CMD_CLSE -> "CLSE"
        CMD_WRTE -> "WRTE"
        CMD_STLS -> "STLS"
        else -> "0x${cmd.toString(16)}"
    }
}
