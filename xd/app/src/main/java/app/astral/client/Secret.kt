package app.astral.client

object Secret {
    private const val HEX = "acb9e44aedebff7a3bff7218fddeffcb271e36e9e8237bfa8a2bfca516d55b2d"
    fun bytes(): ByteArray = HEX.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
