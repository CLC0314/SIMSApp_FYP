// com.example.familyapp.data/InventoryItemFirestore.kt

package com.example.familyapp.data // 确保包名正确

import com.google.firebase.firestore.Exclude
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

/**
 * Firestore 数据模型：包含 Firestore 注解和 Adapter 所需的所有字段。
 */
data class InventoryItemFirestore(
    // 1. 文档 ID：不应存储在文档本身中，但用于本地追踪和更新
    @get:Exclude var id: String = "", // 保持您的 var id = ""，以便 Firestore.toObject() 可以写入

    // 2. 链接到家庭 ID (保证数据隔离)
    var familyId: String = "",

    // 3. 物品信息 (Adapter 核心字段)
    var name: String = "",
    var category: String = "",
    var quantity: Int = 0,
    var unit: String = "Item", // ✅ 新增：适配器需要此字段
    var location: String? = null,

    // 4. 过期日期 (使用 adapter 中的命名，保持一致)
    var expiryDate: String? = null,

    // 5. 成员信息 (用于筛选和显示)
    var ownerId: String? = null,
    var ownerName: String? = null, // 适配器需要，不存入 Firestore

    // 6. 额外信息
    var notes: String? = null,

    // 7. 自动时间戳 (用于排序和追踪)
    @ServerTimestamp
    var createdAt: Date? = null
) {
    // 确保有一个无参数构造函数以兼容 Firestore
    constructor() : this("")
}