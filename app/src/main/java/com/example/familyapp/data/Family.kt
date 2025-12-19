// data/Family.kt
package com.example.familyapp.data
data class Family(
    val familyId: String = "",
    val code: String = "", // 🆕 新增：用于加入家庭的6位代码
    val name: String = "",
    val creatorId: String = "",
    val members: List<String> = listOf(),
    val memberLimit: Int = 5,
    var ownerId: String = "",
    var createdAt: com.google.firebase.Timestamp? = null
)