package com.dreamgirl.app.ui.persona

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreamgirl.app.data.PersonaEntity
import com.dreamgirl.app.data.SettingsStore
import com.dreamgirl.app.persona.PersonaDefaults
import com.dreamgirl.app.persona.PersonaRepository
import kotlinx.coroutines.launch

class PersonaViewModel(
    private val repo: PersonaRepository,
    private val settings: SettingsStore,
) : ViewModel() {

    /** 人格设定的七个段落（出厂 = 方案 2.1 节原文）。 */
    var draft by mutableStateOf<PersonaEntity?>(null)
        private set

    /** 界面标题用的名字。**独立于人格原文**，改它不会动人设那七段。 */
    var displayName by mutableStateOf("dream")
        private set

    /** 当前草稿是否仍与方案 2.1 节逐字一致。 */
    var verbatim by mutableStateOf(true)
        private set

    var savedAt by mutableStateOf<Long?>(null)
        private set

    init {
        viewModelScope.launch {
            val p = repo.get()
            draft = p
            verbatim = PersonaDefaults.isVerbatim(p)
        }
        viewModelScope.launch {
            settings.flow.collect { displayName = it.displayName }
        }
    }

    fun updateDisplayName(v: String) {
        displayName = v
    }

    fun edit(transform: (PersonaEntity) -> PersonaEntity) {
        val cur = draft ?: return
        val next = transform(cur)
        draft = next
        verbatim = PersonaDefaults.isVerbatim(next)
    }

    fun save() {
        val cur = draft ?: return
        viewModelScope.launch {
            repo.save(cur)
            settings.setDisplayName(displayName)
            draft = repo.get()
            savedAt = System.currentTimeMillis()
        }
    }

    /** 只把七段人设恢复成方案原文；名字是独立的显示项，不动。 */
    fun resetToVerbatim() {
        viewModelScope.launch {
            repo.resetToVerbatim()
            val p = repo.get()
            draft = p
            verbatim = true
            savedAt = System.currentTimeMillis()
        }
    }
}
