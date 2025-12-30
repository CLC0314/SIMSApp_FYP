package com.example.familyapp.data

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

/**
 * Firestore 数据模型：包含 Firestore 注解和 Adapter 所需的所有字段。
 */
data class InventoryItemFirestore(
    var id: String = "",
    val familyId: String = "",
    val name: String = "",
    val category: String = "",
    val quantity: Int = 0,
    val unit: String = "",
    // 🟢 修改点：使用 Long 并赋予默认值 0L，防止解析 null 时崩溃
    val expiryDate: Long = 0L,
    // 🟢 修改点：所有者信息建议也给默认值
    val ownerId: String = "PUBLIC",
    val ownerName: String = "Public",
    val notes: String = "",
    val location: String = ""
)