// com.example.familyapp/InventoryItemFirestore.kt

package com.example.familyapp

import com.google.firebase.firestore.Exclude
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

// Firestore 需要一个无参数构造函数
data class InventoryItemFirestore(
    //  Firestore 会自动使用 document ID 作为主键，我们将其存储在此变量中
    @get:Exclude var id: String = "",

    // 链接到家庭 ID (保证数据隔离)
    var familyId: String = "",

    // 物品信息
    var name: String = "",
    var category: String = "",
    var quantity: Int = 0,
    var location: String? = null,
    var expiredDate: String? = null,
    var ownerName: String? = null, // 所属家庭成员的姓名
    var ownerId: String? = null, // 所属家庭成员的 UID
    var notes: String? = null,

    // 自动时间戳 (用于排序和追踪)
    @ServerTimestamp
    var createdAt: Date? = null
)