package com.example.familyapp.data

data class ShoppingItem(
    var id: String = "",
    val name: String = "",
    val category: String = "General",
    val quantity: Int = 1,
    val unit: String = "Pcs",
    val familyId: String = "",
    val addedBy: String = "",
    val ownerId: String = "PUBLIC", // 🟢 记录所属权 (UID 或 "PUBLIC")
    val ownerName: String = "Public", // 🟢 记录所属权名称
    val inventoryId: String? = null,
    @field:JvmField
    val isChecked: Boolean = false,
    val expiryDate: Long = 0L
)