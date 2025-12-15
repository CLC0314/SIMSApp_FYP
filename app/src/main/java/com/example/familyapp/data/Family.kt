// data/Family.kt
package com.example.familyapp.data

data class Family(
    val familyId: String = "",
    val name: String = "",
    val creatorId: String = "",
    val members: List<String> = listOf(),
    val memberLimit: Int = 5 // ✅ 新增：家庭人数限制
)