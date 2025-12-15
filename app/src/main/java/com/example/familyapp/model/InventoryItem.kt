package com.example.familyapp.model

data class InventoryItem(
    val id: Long = 0,
    val name: String,
    val category: String,
    val quantity: Int = 0,
    val location: String? = null,
    val minStock: Int = 0,
    val expiredDate: String? = null,
    val familyMemberId: Long = 0,
    val familyMemberName: String? = null,
    val notes: String? = null,
    val createdAt: String = ""
)