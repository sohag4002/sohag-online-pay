package com.sohagonline.payment.data

data class SmsRecord(
    val id: Long = 0,
    val address: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    var status: String = STATUS_PENDING, // SUCCESS, PENDING, FAILED
    var responseMessage: String = ""
) {
    companion object {
        const val STATUS_SUCCESS = "SUCCESS"
        const val STATUS_PENDING = "PENDING"
        const val STATUS_FAILED = "FAILED"
    }
}
