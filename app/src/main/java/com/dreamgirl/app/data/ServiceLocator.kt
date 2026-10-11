package com.dreamgirl.app.data

import android.annotation.SuppressLint
import android.content.Context
import com.dreamgirl.app.domain.ChatEngine
import com.dreamgirl.app.domain.MemoryEngine
import com.dreamgirl.app.domain.PromptAssembler
import com.dreamgirl.app.domain.TriggerEngine
import com.dreamgirl.app.llm.DeepSeekClient
import com.dreamgirl.app.persona.PersonaRepository
import com.dreamgirl.app.sense.UsageStatsCollector

/**
 * 极简的依赖容器。没有用 DI 框架 —— 这个 App 的依赖图很浅，
 * 手写反而更清楚，也少一层编译期魔法。
 *
 * 关于 StaticFieldLeak：这里持有的都是 **applicationContext**（进程级、与 Activity 同寿命），
 * 不会泄漏 Activity。lint 只看到"static 里有 Context"就报，属于误报，故抑制。
 */
@SuppressLint("StaticFieldLeak")
object ServiceLocator {

    @Volatile
    private var initialized = false

    /** Application.onCreate 一定会先跑，但 Worker/Receiver 可能被系统单独拉起，所以要能自检。 */
    val isInitialized: Boolean get() = initialized

    lateinit var db: AppDatabase
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var apiKeys: ApiKeyStore
        private set
    lateinit var llm: DeepSeekClient
        private set
    lateinit var memory: MemoryEngine
        private set
    lateinit var chat: ChatEngine
        private set
    lateinit var persona: PersonaRepository
        private set
    lateinit var usageStats: UsageStatsCollector
        private set
    val triggers: TriggerEngine = TriggerEngine()
    val assembler: PromptAssembler = PromptAssembler()

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val app = context.applicationContext
            db = AppDatabase.build(app)
            settings = SettingsStore(app)
            apiKeys = ApiKeyStore(app)
            llm = DeepSeekClient(apiKeyProvider = { apiKeys.apiKey })
            memory = MemoryEngine(
                messageDao = db.messageDao(),
                summaryDao = db.daySummaryDao(),
                memoryDao = db.memoryDao(),
                monthDao = db.monthDigestDao(),
                client = llm,
                settings = settings,
                apiKeyStore = apiKeys,
            )
            chat = ChatEngine(
                messageDao = db.messageDao(),
                sessionDao = db.daySessionDao(),
                summaryDao = db.daySummaryDao(),
                client = llm,
                settings = settings,
                apiKeyStore = apiKeys,
                memoryEngine = memory,
                assembler = assembler,
            )
            persona = PersonaRepository(db)
            usageStats = UsageStatsCollector(app)
            initialized = true
        }
    }
}
