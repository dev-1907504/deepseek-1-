package com.dreamgirl.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dreamgirl.app.data.ServiceLocator
import com.dreamgirl.app.ui.chat.ChatViewModel
import com.dreamgirl.app.ui.memory.MemoryViewModel
import com.dreamgirl.app.ui.persona.PersonaViewModel
import com.dreamgirl.app.ui.report.ReportViewModel
import com.dreamgirl.app.ui.sessions.SessionListViewModel
import com.dreamgirl.app.ui.settings.SettingsViewModel

/**
 * 所有 ViewModel 都从 [ServiceLocator] 取依赖。依赖图很浅，不值得引入 DI 框架。
 */
object AppViewModels {

    val Factory: ViewModelProvider.Factory = viewModelFactory {
        initializer {
            ChatViewModel(ServiceLocator.chat, ServiceLocator.db, ServiceLocator.settings)
        }
        initializer {
            PersonaViewModel(ServiceLocator.persona, ServiceLocator.settings)
        }
        initializer {
            MemoryViewModel(ServiceLocator.db, ServiceLocator.memory)
        }
        initializer {
            SessionListViewModel(ServiceLocator.db, ServiceLocator.settings, ServiceLocator.apiKeys)
        }
        initializer {
            SettingsViewModel(ServiceLocator.settings, ServiceLocator.apiKeys, ServiceLocator.llm)
        }
        initializer {
            ReportViewModel(ServiceLocator.usageStats, ServiceLocator.db, ServiceLocator.settings)
        }
    }
}
