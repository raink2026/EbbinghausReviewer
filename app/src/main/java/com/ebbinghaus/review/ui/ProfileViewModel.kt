package com.ebbinghaus.review.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ebbinghaus.review.R
import com.ebbinghaus.review.data.AppDatabase
import com.ebbinghaus.review.data.User
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProfileViewModel(application: Application) : AndroidViewModel(application) {
    private val userDao = AppDatabase.getDatabase(application).userDao()

    // 所有用户列表 (用于切换)
    val allUsers = userDao.getAllUsers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 当前用户
    val currentUser = userDao.getCurrentUser()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        // 初始化检查：如果没有用户，创建一个默认用户
        viewModelScope.launch {
            if (userDao.getCurrentUserSync() == null) {
                 userDao.insertUser(User(name = application.getString(R.string.default_user), isCurrent = true))
            }
        }
    }

    // 创建新用户
    fun createUser(name: String) {
        viewModelScope.launch {
            userDao.insertUser(User(name = name, isCurrent = false))
        }
    }

    // 切换用户
    fun switchUser(user: User) {
        viewModelScope.launch {
            userDao.switchUser(user.id)
            // TODO: 这里未来需要通知 ReviewItemDao 和 PlanDao 重新加载该 user 的数据
            // 目前先做 UI 切换效果
        }
    }

    // 更新当前用户的菜单设置
    fun updateMenuSettings(showLabels: Boolean? = null, homeIcon: String? = null, planIcon: String? = null, profileIcon: String? = null) {
        val current = currentUser.value ?: return
        val updatedUser = current.copy(
            showMenuLabels = showLabels ?: current.showMenuLabels,
            homeIcon = homeIcon ?: current.homeIcon,
            planIcon = planIcon ?: current.planIcon,
            profileIcon = profileIcon ?: current.profileIcon
        )
        viewModelScope.launch {
            userDao.insertUser(updatedUser) // insertUser uses OnConflictStrategy.REPLACE (implied if not specified? need to check UserDao)
        }
    }
}
