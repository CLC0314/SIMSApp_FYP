package com.example.familyapp.repository

import com.example.familyapp.model.FamilyMember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FamilyRepository(private val databaseHelper: FamilyDatabaseHelper) {

    suspend fun insertMember(member: FamilyMember): Long = withContext(Dispatchers.IO) {
        databaseHelper.insertMember(member)
    }

    suspend fun getAllMembers(): List<FamilyMember> = withContext(Dispatchers.IO) {
        databaseHelper.getAllMembers()
    }
    suspend fun updateMember(member: FamilyMember): Int = withContext(Dispatchers.IO) {
        databaseHelper.updateMember(member)
    }
    suspend fun deleteMember(memberId: Long): Int = withContext(Dispatchers.IO) {
        databaseHelper.deleteMember(memberId)
    }
}