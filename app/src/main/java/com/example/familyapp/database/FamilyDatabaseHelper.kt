package com.example.familyapp.database

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.familyapp.model.FamilyMember

class FamilyDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "family_app.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_MEMBERS = "family_members"
        private const val COLUMN_ID = "id"
        private const val COLUMN_NAME = "name"
        private const val COLUMN_AGE = "age"
        private const val COLUMN_RELATIONSHIP = "relationship"
        private const val COLUMN_PHONE = "phone"
        private const val COLUMN_EMAIL = "email"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTable = """
            CREATE TABLE $TABLE_MEMBERS (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_NAME TEXT NOT NULL,
                $COLUMN_AGE INTEGER,
                $COLUMN_RELATIONSHIP TEXT,
                $COLUMN_PHONE TEXT,
                $COLUMN_EMAIL TEXT
            )
        """.trimIndent()
        db.execSQL(createTable)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_MEMBERS")
        onCreate(db)
    }

    fun insertMember(member: FamilyMember): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_NAME, member.name)
            put(COLUMN_AGE, member.age)
            put(COLUMN_RELATIONSHIP, member.relationship)
            put(COLUMN_PHONE, member.phone)
            put(COLUMN_EMAIL, member.email)
        }
        return db.insert(TABLE_MEMBERS, null, values)
    }

    fun getAllMembers(): List<FamilyMember> {
        val members = mutableListOf<FamilyMember>()
        val db = readableDatabase
        val cursor = db.query(TABLE_MEMBERS, null, null, null, null, null, "$COLUMN_NAME ASC")

        cursor.use {
            while (it.moveToNext()) {
                val member = FamilyMember(
                    id = it.getLong(it.getColumnIndexOrThrow(COLUMN_ID)),
                    name = it.getString(it.getColumnIndexOrThrow(COLUMN_NAME)),
                    age = it.getInt(it.getColumnIndexOrThrow(COLUMN_AGE)),
                    relationship = it.getString(it.getColumnIndexOrThrow(COLUMN_RELATIONSHIP)),
                    phone = it.getString(it.getColumnIndexOrThrow(COLUMN_PHONE)),
                    email = it.getString(it.getColumnIndexOrThrow(COLUMN_EMAIL))
                )
                members.add(member)
            }
        }
        return members
    }

    /**
     * 更新数据库中现有家庭成员的信息。
     * @param member 包含更新数据的 FamilyMember 对象（必须包含有效的 id）。
     * @return 受影响的行数 (成功为 1, 失败为 0)。
     */
    fun updateMember(member: FamilyMember): Int {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_NAME, member.name)
            put(COLUMN_AGE, member.age)
            put(COLUMN_RELATIONSHIP, member.relationship)
            put(COLUMN_PHONE, member.phone)
            put(COLUMN_EMAIL, member.email)
        }

        // 执行更新操作
        return db.update(
            TABLE_MEMBERS,
            values,
            "$COLUMN_ID = ?",
            arrayOf(member.id.toString())
        )
    }

    /**
     * 根据 ID 删除家庭成员。
     * @param memberId 要删除的成员的 ID。
     * @return 被删除的行数 (成功为 1, 失败为 0)。
     */
    fun deleteMember(memberId: Long): Int {
        val db = writableDatabase
        return db.delete(
            TABLE_MEMBERS,
            "$COLUMN_ID = ?",
            arrayOf(memberId.toString())
        )
    }
}