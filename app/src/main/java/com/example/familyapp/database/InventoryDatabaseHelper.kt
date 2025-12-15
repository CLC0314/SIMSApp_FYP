package com.example.familyapp.database

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.familyapp.model.InventoryItem
import java.text.SimpleDateFormat
import java.util.*

class InventoryDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "family_inventory.db"
        private const val DATABASE_VERSION = 1

        private const val TABLE_ITEMS = "inventory_items"
        private const val COLUMN_ID = "id"
        private const val COLUMN_NAME = "name"
        private const val COLUMN_CATEGORY = "category"
        private const val COLUMN_QUANTITY = "quantity"
        private const val COLUMN_LOCATION = "location"
        private const val COLUMN_MIN_STOCK = "min_stock"
        private const val COLUMN_EXPIRED_DATE = "expired_date"
        private const val COLUMN_FAMILY_MEMBER_ID = "family_member_id"
        private const val COLUMN_NOTES = "notes"
        private const val COLUMN_CREATED_AT = "created_at"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTable = """
            CREATE TABLE $TABLE_ITEMS (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_NAME TEXT NOT NULL,
                $COLUMN_CATEGORY TEXT,
                $COLUMN_QUANTITY INTEGER DEFAULT 0,
                $COLUMN_LOCATION TEXT,
                $COLUMN_MIN_STOCK INTEGER DEFAULT 0,
                $COLUMN_EXPIRED_DATE TEXT,
                $COLUMN_FAMILY_MEMBER_ID INTEGER DEFAULT 0,
                $COLUMN_NOTES TEXT,
                $COLUMN_CREATED_AT TEXT
            )
        """.trimIndent()
        db.execSQL(createTable)

        // 插入示例数据
        insertSampleData(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_ITEMS")
        onCreate(db)
    }

    private fun insertSampleData(db: SQLiteDatabase) {
        val sampleItems = listOf(
            mapOf(
                COLUMN_NAME to "大米",
                COLUMN_CATEGORY to "食品",
                COLUMN_QUANTITY to 2,
                COLUMN_LOCATION to "厨房",
                COLUMN_EXPIRED_DATE to "2025-12-31"
            ),
            mapOf(
                COLUMN_NAME to "洗发水",
                COLUMN_CATEGORY to "日用品",
                COLUMN_QUANTITY to 1,
                COLUMN_LOCATION to "浴室",
                COLUMN_EXPIRED_DATE to "2026-06-30"
            )
        )

        sampleItems.forEach { item ->
            val values = ContentValues().apply {
                item.forEach { (key, value) ->
                    put(key, value.toString())
                }
                put(COLUMN_CREATED_AT, getCurrentDateTime())
            }
            db.insert(TABLE_ITEMS, null, values)
        }
    }

    private fun getCurrentDateTime(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
    }

    fun insertItem(item: InventoryItem): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_NAME, item.name)
            put(COLUMN_CATEGORY, item.category)
            put(COLUMN_QUANTITY, item.quantity)
            put(COLUMN_LOCATION, item.location)
            put(COLUMN_MIN_STOCK, item.minStock)
            put(COLUMN_EXPIRED_DATE, item.expiredDate)
            put(COLUMN_FAMILY_MEMBER_ID, item.familyMemberId)
            put(COLUMN_NOTES, item.notes)
            put(COLUMN_CREATED_AT, getCurrentDateTime())
        }
        return db.insert(TABLE_ITEMS, null, values)
    }

    /**
     * 搜索物品 (根据名称、类别、位置)。
     */
    fun searchItems(query: String): List<InventoryItem> {
        val items = mutableListOf<InventoryItem>()
        val db = readableDatabase

        // 搜索名称、类别、位置
        val selection = "$COLUMN_NAME LIKE ? OR $COLUMN_CATEGORY LIKE ? OR $COLUMN_LOCATION LIKE ?"
        val selectionArgs = arrayOf("%$query%", "%$query%", "%$query%")

        val cursor = db.query(
            TABLE_ITEMS,
            null,
            selection,
            selectionArgs,
            null,
            null,
            "$COLUMN_CATEGORY ASC, $COLUMN_NAME ASC" // <--- 关键修改
        )

        cursor.use {
            while (it.moveToNext()) {
                val memberId = it.getLong(it.getColumnIndexOrThrow(COLUMN_FAMILY_MEMBER_ID))

                // 🌟 完整地读取所有字段
                val item = InventoryItem(
                    id = it.getLong(it.getColumnIndexOrThrow(COLUMN_ID)),
                    name = it.getString(it.getColumnIndexOrThrow(COLUMN_NAME)),
                    category = it.getString(it.getColumnIndexOrThrow(COLUMN_CATEGORY)),
                    quantity = it.getInt(it.getColumnIndexOrThrow(COLUMN_QUANTITY)),
                    location = it.getString(it.getColumnIndexOrThrow(COLUMN_LOCATION)),
                    minStock = it.getInt(it.getColumnIndexOrThrow(COLUMN_MIN_STOCK)),
                    expiredDate = it.getString(it.getColumnIndexOrThrow(COLUMN_EXPIRED_DATE)),
                    familyMemberId = memberId,
                    // ⚠️ familyMemberName: 数据库中未存储此列，根据 ID 映射
                    familyMemberName = if (memberId == 0L) "公共物品" else null,
                    notes = it.getString(it.getColumnIndexOrThrow(COLUMN_NOTES)),
                    createdAt = it.getString(it.getColumnIndexOrThrow(COLUMN_CREATED_AT))
                )
                items.add(item)
            }
        }
        return items
    }

    /**
     * 更新数据库中现有物品的数据。
     * @param item 包含更新数据的 InventoryItem 对象（必须包含有效的 id）。
     * @return 受影响的行数 (成功为 1, 失败为 0)。
     */
    fun updateItem(item: InventoryItem): Int {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_NAME, item.name)
            put(COLUMN_CATEGORY, item.category)
            put(COLUMN_QUANTITY, item.quantity)
            put(COLUMN_LOCATION, item.location)
            put(COLUMN_MIN_STOCK, item.minStock) // 即使 minStock 没在 UI 中显示，也要更新
            put(COLUMN_EXPIRED_DATE, item.expiredDate)
            put(COLUMN_FAMILY_MEMBER_ID, item.familyMemberId)
            put(COLUMN_NOTES, item.notes)
            // 不更新 COLUMN_CREATED_AT
        }

        // 执行更新操作
        return db.update(
            TABLE_ITEMS,
            values,
            "$COLUMN_ID = ?",
            arrayOf(item.id.toString())
        )
    }
    /**
     * 获取数据库中的所有物品。
     */
    fun getAllItems(): List<InventoryItem> {
        val items = mutableListOf<InventoryItem>()
        val db = readableDatabase

        // 按名称升序排列
        val cursor = db.query(TABLE_ITEMS, null, null, null, null, null,
            "$COLUMN_CATEGORY ASC, $COLUMN_NAME ASC")
        cursor.use {
            while (it.moveToNext()) {
                val memberId = it.getLong(it.getColumnIndexOrThrow(COLUMN_FAMILY_MEMBER_ID))

                // 🌟 完整地读取所有字段
                val item = InventoryItem(
                    id = it.getLong(it.getColumnIndexOrThrow(COLUMN_ID)),
                    name = it.getString(it.getColumnIndexOrThrow(COLUMN_NAME)),
                    category = it.getString(it.getColumnIndexOrThrow(COLUMN_CATEGORY)),
                    quantity = it.getInt(it.getColumnIndexOrThrow(COLUMN_QUANTITY)),
                    location = it.getString(it.getColumnIndexOrThrow(COLUMN_LOCATION)),
                    minStock = it.getInt(it.getColumnIndexOrThrow(COLUMN_MIN_STOCK)),
                    expiredDate = it.getString(it.getColumnIndexOrThrow(COLUMN_EXPIRED_DATE)),
                    familyMemberId = memberId,
                    // ⚠️ familyMemberName: 数据库中未存储此列，根据 ID 映射
                    familyMemberName = if (memberId == 0L) "公共物品" else null,
                    notes = it.getString(it.getColumnIndexOrThrow(COLUMN_NOTES)),
                    createdAt = it.getString(it.getColumnIndexOrThrow(COLUMN_CREATED_AT))
                )
                items.add(item)
            }
        }
        return items
    }

    // 删除物品
    fun deleteItem(itemId: Long): Int {
        val db = writableDatabase
        return db.delete(
            TABLE_ITEMS,
            "$COLUMN_ID = ?",
            arrayOf(itemId.toString())
        )
    }
}