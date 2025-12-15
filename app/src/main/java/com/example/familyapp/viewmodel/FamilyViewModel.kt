package com.example.familyapp.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.MutableLiveData
import com.example.familyapp.model.FamilyMember
import com.example.familyapp.repository.FamilyRepository
import kotlinx.coroutines.launch

class FamilyViewModel(private val repository: FamilyRepository) : ViewModel() {

    private val _members = MutableLiveData<List<FamilyMember>>()
    val members = _members

    fun loadMembers() {
        viewModelScope.launch {
            val membersList = repository.getAllMembers()
            _members.value = membersList
        }
    }

    fun addMember(member: FamilyMember) {
        viewModelScope.launch {
            repository.insertMember(member)
            loadMembers()
        }
    }
    fun updateMember(member: FamilyMember) {
        viewModelScope.launch {
            repository.updateMember(member)
            loadMembers() // 更新后重新加载列表
        }
    }

    fun deleteMember(member: FamilyMember) {
        viewModelScope.launch {
            repository.deleteMember(member.id)
            loadMembers() // 删除后重新加载列表
        }
    }
}