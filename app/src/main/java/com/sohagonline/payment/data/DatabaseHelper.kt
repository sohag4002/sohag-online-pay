package com.sohagonline.payment.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "sohag_payment.db"
        private const val DATABASE_VERSION = 1

        private const val TABLE_SMS = "sms_logs"
        private const val COL_ID = "id"
        private const val COL_ADDRESS = "address"
        private const val COL_MESSAGE = "message"
        private const val COL_TIMESTAMP = "timestamp"
        private const val COL_STATUS = "status"
        private const val COL_RESPONSE = "response_message"

        @Volatile
        private var instance: DatabaseHelper? = null

        fun getInstance(context: Context): DatabaseHelper {
            return instance ?: synchronized(this) {
                instance ?: DatabaseHelper(context.applicationContext).also { instance = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTable = """
            CREATE TABLE $TABLE_SMS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_ADDRESS TEXT NOT NULL,
                $COL_MESSAGE TEXT NOT NULL,
                $COL_TIMESTAMP INTEGER NOT NULL,
                $COL_STATUS TEXT NOT NULL,
                $COL_RESPONSE TEXT
            )
        """.trimIndent()
        db.execSQL(createTable)
        db.execSQL("CREATE INDEX idx_timestamp ON $TABLE_SMS ($COL_TIMESTAMP)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_SMS")
        onCreate(db)
    }

    fun insertSms(record: SmsRecord): Long {
        val db = writableDatabase
        val cleanMsg = record.message.replace("\r\n", " ").replace("\n", " ").trim()
        val values = ContentValues().apply {
            put(COL_ADDRESS, record.address.trim())
            put(COL_MESSAGE, cleanMsg)
            put(COL_TIMESTAMP, record.timestamp)
            put(COL_STATUS, record.status)
            put(COL_RESPONSE, record.responseMessage)
        }
        return db.insert(TABLE_SMS, null, values)
    }

    /**
     * Checks if an identical SMS from this sender was already processed in the last 2 hours
     */
    fun isSmsDuplicate(address: String, message: String): Boolean {
        val db = readableDatabase
        val cutoff = System.currentTimeMillis() - (2 * 3600 * 1000L)
        val cleanMsg = message.replace("\r\n", " ").replace("\n", " ").trim()
        val cleanAddr = address.trim()
        val cursor = db.rawQuery(
            "SELECT 1 FROM $TABLE_SMS WHERE $COL_ADDRESS = ? AND $COL_MESSAGE = ? AND $COL_TIMESTAMP > ? LIMIT 1",
            arrayOf(cleanAddr, cleanMsg, cutoff.toString())
        )
        cursor.use {
            return it.moveToFirst()
        }
    }

    fun updateStatus(id: Long, status: String, responseMsg: String = "") {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_STATUS, status)
            put(COL_RESPONSE, responseMsg)
        }
        db.update(TABLE_SMS, values, "$COL_ID = ?", arrayOf(id.toString()))
    }

    fun getPendingSms(limit: Int = 20): List<SmsRecord> {
        val list = mutableListOf<SmsRecord>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_SMS,
            null,
            "$COL_STATUS = ?",
            arrayOf(SmsRecord.STATUS_PENDING),
            null,
            null,
            "$COL_ID ASC",
            limit.toString()
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    SmsRecord(
                        id = it.getLong(it.getColumnIndexOrThrow(COL_ID)),
                        address = it.getString(it.getColumnIndexOrThrow(COL_ADDRESS)),
                        message = it.getString(it.getColumnIndexOrThrow(COL_MESSAGE)),
                        timestamp = it.getLong(it.getColumnIndexOrThrow(COL_TIMESTAMP)),
                        status = it.getString(it.getColumnIndexOrThrow(COL_STATUS)),
                        responseMessage = it.getString(it.getColumnIndexOrThrow(COL_RESPONSE)) ?: ""
                    )
                )
            }
        }
        return list
    }

    fun getRecentSms(limit: Int = 50): List<SmsRecord> {
        val list = mutableListOf<SmsRecord>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_SMS,
            null,
            null,
            null,
            null,
            null,
            "$COL_ID DESC",
            limit.toString()
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    SmsRecord(
                        id = it.getLong(it.getColumnIndexOrThrow(COL_ID)),
                        address = it.getString(it.getColumnIndexOrThrow(COL_ADDRESS)),
                        message = it.getString(it.getColumnIndexOrThrow(COL_MESSAGE)),
                        timestamp = it.getLong(it.getColumnIndexOrThrow(COL_TIMESTAMP)),
                        status = it.getString(it.getColumnIndexOrThrow(COL_STATUS)),
                        responseMessage = it.getString(it.getColumnIndexOrThrow(COL_RESPONSE)) ?: ""
                    )
                )
            }
        }
        return list
    }

    fun getCounts(): Triple<Int, Int, Int> {
        val db = readableDatabase
        var total = 0
        var success = 0
        var pending = 0

        db.rawQuery("SELECT COUNT(*) FROM $TABLE_SMS", null).use {
            if (it.moveToFirst()) total = it.getInt(0)
        }
        db.rawQuery("SELECT COUNT(*) FROM $TABLE_SMS WHERE $COL_STATUS = ?", arrayOf(SmsRecord.STATUS_SUCCESS)).use {
            if (it.moveToFirst()) success = it.getInt(0)
        }
        db.rawQuery("SELECT COUNT(*) FROM $TABLE_SMS WHERE $COL_STATUS = ?", arrayOf(SmsRecord.STATUS_PENDING)).use {
            if (it.moveToFirst()) pending = it.getInt(0)
        }
        return Triple(total, success, pending)
    }

    /**
     * Auto Data Cleanup: Removes all SMS records older than the specified hours (Default: 48 hours).
     * Keeps the app super fast, feather-light, and prevents any storage bloat!
     */
    fun purgeOldRecords(hours: Int = 48): Int {
        val cutoff = System.currentTimeMillis() - (hours * 3600 * 1000L)
        val db = writableDatabase
        val deletedRows = db.delete(TABLE_SMS, "$COL_TIMESTAMP < ?", arrayOf(cutoff.toString()))
        if (deletedRows > 0) {
            Log.d("DatabaseHelper", "Purged $deletedRows records older than $hours hours.")
        }
        return deletedRows
    }

    fun clearAll(): Int {
        val db = writableDatabase
        return db.delete(TABLE_SMS, null, null)
    }
}
