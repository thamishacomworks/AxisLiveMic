package com.example.axislivemic

object G711Codec {

    fun pcm16ToMuLaw(pcm: ByteArray): ByteArray {

        val sampleCount = pcm.size / 2
        val output = ByteArray(sampleCount)

        var inputIndex = 0
        var outputIndex = 0

        while (inputIndex + 1 < pcm.size) {

            val low = pcm[inputIndex].toInt() and 0xFF
            val high = pcm[inputIndex + 1].toInt()

            val sample = ((high shl 8) or low).toShort()

            output[outputIndex] = linearToMuLaw(sample)

            inputIndex += 2
            outputIndex++
        }

        return output
    }

    private fun linearToMuLaw(sampleValue: Short): Byte {

        var sample = sampleValue.toInt()

        val sign = if (sample < 0) 0x80 else 0x00

        if (sample < 0) {
            sample = -sample
        }

        if (sample > 32635) {
            sample = 32635
        }

        sample += 132

        var exponent = 7
        var mask = 0x4000

        while (
            exponent > 0 &&
            (sample and mask) == 0
        ) {
            exponent--
            mask = mask shr 1
        }

        val mantissa =
            (sample shr (exponent + 3)) and 0x0F

        val muLaw =
            (sign or (exponent shl 4) or mantissa).inv()

        return muLaw.toByte()
    }
}
