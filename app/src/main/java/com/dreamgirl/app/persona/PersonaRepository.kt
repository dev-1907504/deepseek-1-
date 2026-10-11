package com.dreamgirl.app.persona

import com.dreamgirl.app.data.AppDatabase
import com.dreamgirl.app.data.PersonaEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 人设是「数据不是代码」（方案 §7-1）：出厂值来自 [PersonaDefaults]（即方案原文），
 * 用户改了之后存 Room，改人设不需要重新编译。
 */
class PersonaRepository(private val db: AppDatabase) {

    private val dao get() = db.personaDao()

    fun observe(): Flow<PersonaEntity> = dao.observe().map { it ?: PersonaDefaults.entity() }

    suspend fun get(): PersonaEntity {
        val existing = dao.get()
        if (existing != null) return existing
        val fresh = PersonaDefaults.entity()
        dao.upsert(fresh)
        return fresh
    }

    suspend fun save(persona: PersonaEntity) {
        dao.upsert(
            persona.copy(
                id = 1,
                customized = !PersonaDefaults.isVerbatim(persona),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /** 恢复出厂：与方案 2.1 节逐字一致。 */
    suspend fun resetToVerbatim() {
        dao.upsert(PersonaDefaults.entity())
    }

    suspend fun isVerbatim(): Boolean = PersonaDefaults.isVerbatim(get())
}
