package com.example.familyapp.model

data class FamilyMember(
    val id: Long = 0,
    val name: String,
    val age: Int,
    val relationship: String?, // ⚠️ 修改为可空 String?
    val phone: String?,
    val email: String?
)