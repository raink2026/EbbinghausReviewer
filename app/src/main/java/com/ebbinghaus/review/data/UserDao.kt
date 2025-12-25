package com.ebbinghaus.review.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Query("SELECT * FROM users")
    fun getAllUsers(): Flow<List<User>>

    @Query("SELECT * FROM users WHERE isCurrent = 1 LIMIT 1")
    fun getCurrentUser(): Flow<User?>

    @Query("SELECT * FROM users WHERE isCurrent = 1 LIMIT 1")
    suspend fun getCurrentUserSync(): User?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: User)

    @Update
    suspend fun updateUser(user: User)

    @Query("UPDATE users SET isCurrent = 0")
    suspend fun clearCurrentUser()

    @Query("UPDATE users SET isCurrent = 1 WHERE id = :userId")
    suspend fun setCurrentUser(userId: Long)

    @Transaction
    suspend fun switchUser(userId: Long) {
        clearCurrentUser()
        setCurrentUser(userId)
    }
}
