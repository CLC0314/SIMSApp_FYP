package com.example.familyapp.data

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

/**
 * Firestore 数据模型：包含 Firestore 注解和 Adapter 所需的所有字段。
 */
data class InventoryItemFirestore(
    // 1. 文档 ID：使用 @DocumentId，Firestore 会自动将文档名填入此字段，且不会将其作为普通字段存入数据库内容中
    @DocumentId
    var id: String = "",

    // 2. 链接到家庭 ID (保证数据隔离)
    var familyId: String = "",

    // 3. 物品信息
    var name: String = "",
    var category: String = "",
    var quantity: Int = 0,
    var unit: String = "Item",
    var location: String? = null,

    // 4. 过期日期
    var expiryDate: String? = null,

    // 5. 成员信息
    var ownerId: String? = null,
    var ownerName: String? = null,

    // 6. 额外信息
    var notes: String? = null,

    // 7. 自动时间戳
    @ServerTimestamp
    var createdAt: Date? = null
) {
    // 💡 提示：在 Kotlin 中，只要所有属性都有默认值（如上面的 = "" 或 = null），
    // Kotlin 就会自动生成一个无参数构造函数，因此不需要手动写 constructor()。
}