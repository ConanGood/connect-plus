package me.andreww7985.connectplus.bluetooth

import android.bluetooth.le.ScanResult
import android.util.SparseArray
import me.andreww7985.connectplus.App
import me.andreww7985.connectplus.dfu.DfuModel
import me.andreww7985.connectplus.helpers.HexHelper
import me.andreww7985.connectplus.manager.SpeakerManager
import me.andreww7985.connectplus.protocol.AudioChannel
import me.andreww7985.connectplus.protocol.DataToken
import me.andreww7985.connectplus.protocol.Packet
import me.andreww7985.connectplus.protocol.PacketType
import me.andreww7985.connectplus.speaker.Feature
import me.andreww7985.connectplus.speaker.ProductColor
import me.andreww7985.connectplus.speaker.ProductModel
import me.andreww7985.connectplus.speaker.SpeakerModel
import timber.log.Timber

object BluetoothProtocol {
    fun connect(scanResult: ScanResult) {
        val scanRecord = scanResult.scanRecord ?: return
        if (SpeakerManager.speakers.containsKey(scanResult.device.address)) return

        val scanRecordBytes = scanRecord.bytes

        val manufacturerData = SparseArray<ByteArray>()
        var currentPos = 0
        while (currentPos < scanRecordBytes.size) {
            val length = scanRecordBytes[currentPos].toInt() and 255 - 1
            if (length == -1) {
                break
            }

            if (scanRecordBytes[currentPos + 1].toInt() and 0xFF == 0xFF) {
                manufacturerData.put((scanRecordBytes[currentPos + 3].toInt() and 255 shl 8) + (scanRecordBytes[currentPos + 2].toInt() and 255),
                        scanRecordBytes.copyOfRange(currentPos + 4, currentPos + 4 + length))
            }

            currentPos += length + 2
        }

        val speakerData = manufacturerData[87] ?: return

        if (SpeakerManager.speakers.containsKey(scanResult.device.address)) return

        Timber.d("connect parsed ${HexHelper.bytesToHex(speakerData)}")

        val speaker = SpeakerModel(scanResult.device, HexHelper.bytesToHex(speakerData))

        SpeakerManager.speakerFound(speaker)
    }

    fun onPacket(speaker: SpeakerModel, packet: Packet) {
        Timber.d("onPacket ${packet.type} ${HexHelper.bytesToHex(packet.payload)}")

        val payload = packet.payload

        when (packet.type) {
            PacketType.ACK -> {
                speaker.featuresChanged()
            }
            PacketType.RES_SPEAKER_INFO -> {
                speaker.index = payload[0].toInt() and 0xFF

                val feature = speaker.getOrCreateFeature(Feature.Type.BATTERY_NAME) as Feature.BatteryName
                var name = feature.deviceName
                var batteryCharging = feature.batteryCharging
                var batteryLevel = feature.batteryLevel

                var productModel = ProductModel.UNKNOWN
                var productColor = ProductColor.UNKNOWN

                var pointer = 1
                while (pointer < payload.size) {
                    when (DataToken.from(payload[pointer].toInt() and 0xFF)) {
                        DataToken.TOKEN_MODEL -> {
                            productModel = ProductModel.from(
                                    (payload[pointer + 1].toInt() and 0xFF shl 8) or (payload[pointer + 2].toInt() and 0xFF))

                            pointer += 3
                        }
                        DataToken.TOKEN_COLOR -> {
                            productColor = ProductColor.from(productModel, payload[pointer + 1].toInt())

                            pointer += 2
                        }
                        DataToken.TOKEN_BATTERY_STATUS -> {
                            val batteryStatus = payload[pointer + 1].toInt() and 0xFF
                            pointer += 2

                            batteryCharging = batteryStatus > 100
                            batteryLevel = batteryStatus % 128
                        }
                        DataToken.TOKEN_LINKED_DEVICE_COUNT -> {
                            // val likedDeviceCount = payload[pointer + 1].toInt() and 0xFF
                            pointer += 2
                        }
                        DataToken.TOKEN_AUDIO_CHANNEL -> {
                            speaker.updateAudioChannel(AudioChannel.from(payload[pointer + 1].toInt() and 0xFF))
                            pointer += 2
                        }
                        DataToken.TOKEN_AUDIO_SOURCE -> {
                            pointer += 2
                        }
                        DataToken.TOKEN_MAC -> {
                            pointer += 8
                        }
                        DataToken.TOKEN_NAME -> {
                            val nameBytesLength = payload[pointer + 1].toInt() and 0xFF
                            val nameBytes = ByteArray(nameBytesLength)
                            for (i in 0 until nameBytesLength) nameBytes[i] = payload[pointer + 2 + i]
                            pointer += 2 + nameBytesLength

                            name = String(nameBytes)

                        }
                        else -> {
                            Timber.w("onPacket RES_SPEAKER_INFO unknown token ${payload[pointer].toInt() and 0xFF}")
                            pointer++
                        }
                    }
                }

                if (name != null && batteryLevel != null && batteryCharging != null) {
                    feature.batteryCharging = batteryCharging
                    feature.batteryLevel = batteryLevel
                    feature.deviceName = name

                    speaker.featuresChanged()
                }

                if (!speaker.isDiscovered) {
                    speaker.color = productColor
                    speaker.model = productModel

                    speaker.discovered()
                }
            }
            PacketType.RES_FIRMWARE_VERSION -> {
                val feature = speaker.getOrCreateFeature(Feature.Type.FIRMWARE_VERSION) as Feature.FirmwareVersion

                if (payload.size <= 2) {
                    feature.major = payload[0].toInt() shr 4 and 0xF
                    feature.minor = payload[0].toInt() and 0xF
                    feature.build = null
                } else {
                    feature.major = payload[0].toInt() and 0xFF
                    feature.minor = payload[1].toInt() and 0xFF
                    feature.build = payload[2].toInt() and 0xFF
                }

                App.analytics.logSpeakerEvent("speaker_firmware") {
                    putString("speaker_firmware", "${feature.major}.${feature.minor}${if (feature.build != null) ".${feature.build}" else ""}")
                }

                speaker.featuresChanged()
            }
            PacketType.RES_FEEDBACK_SOUNDS -> {
                val feature = speaker.getOrCreateFeature(Feature.Type.FEEDBACK_SOUNDS) as Feature.FeedbackSounds

                feature.enabled = payload[0] == 1.toByte()
                speaker.featuresChanged()
            }
            PacketType.RES_SPEAKERPHONE_MODE -> {
                val feature = speaker.getOrCreateFeature(Feature.Type.SPEAKERPHONE_MODE) as Feature.SpeakerphoneMode

                feature.enabled = payload[0] == 1.toByte()
                speaker.featuresChanged()
            }
            PacketType.RES_DFU_STATUS_CHANGE -> {
                val dfu = speaker.dfuModel
                val status = if (payload.size == 1) payload[0] else payload[1]

                when (status.toInt()) {
                    0 -> if (dfu.status != DfuModel.Status.CANCELED) dfu.dfuFinished(DfuModel.Status.ERROR)
                    1 -> dfu.dfuStarted()
                    2 -> if (dfu.state == DfuModel.State.FLASHING_DFU) dfu.sendNextPacket()
                    3 -> dfu.dfuFinished(DfuModel.Status.SUCCESS)
                }
            }
            PacketType.UNKNOWN -> Timber.w("Unknown packet type")
            else -> Timber.w("Wrong packet type")
        }
    }
}