package com.example.familyapp.data

data class ShoppingItem(
    var id: String = "",
    val name: String = "",
    val quantity: Int = 1,
    val unit: String = "pcs",
    val familyId: String = "",
    val addedBy: String = "",
    @field:JvmField // 确保 Firestore 正确映射布尔值
    val isChecked: Boolean = false,
    val category: String = "General" // 方便买完后自动归类
)