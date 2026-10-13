# dream 少女

一个**独立运行的 Android 聊天 App**（Kotlin + Compose + Room），接 DeepSeek 云端，
把大模型组织成「一个有自己生活、有情绪、会记事的她」，而不是一个聊天机器人套壳。

不是助手，不是客服。她有自己的脾气、会记仇、会赌气，也会在你说累的时候嘴上损你两句、转头问你吃没吃。

---

## 一、她是什么样的

- **人格来自一份逐字锁定的原文**（`docs/设计方案.md` 2.1 节）—— 构建期强制校验，改动即构建失败
- **记得你们聊过什么**：按天分会话 → 日总结三层（L1 概要 / L2 要点 / L3 细节）→ 关键词检索
- **有自己的情绪**：情绪来自她自己的一天和相处中真发生的事；**不满只有对方的行为能加**
- **会主动找你**：作息感知（熬夜、长时间用机、久未解锁）→ 她自己开口
- **说人话**：一行一条消息（像真人连发），不用括号写动作，不说客服腔
- **不知道就不说**：没记忆依据的事绝不编造（有代码兜底）

## 二、环境要求

| 项 | 版本 |
|---|---|
| JDK | **17**（本项目用 17.0.20 验证） |
| Android SDK | **compileSdk 36 / minSdk 26 / targetSdk 36** |
| Gradle | **8.14.3**（用仓库自带的配置；本机没验证过 wrapper） |
| AGP / Kotlin | 8.13.2 / 2.2.20 |

### ⚠️ 三个环境注意

1. **`gradle.properties` 里的 `android.aapt2FromMavenOverride` 默认已注释掉。**
   那是原始开发机（aarch64）专用 —— 它的 aapt2 只有 x86_64，所以用 qemu 包了一层。
   **x86_64 机器不需要这行；如果你在 aarch64 上构建，需要自己准备可用的 aapt2。**
2. **本项目没有 git wrapper（`gradlew`）**，用系统 `gradle`。第一次构建前确认 `gradle -v` 是 8.14.x。
3. **Robolectric 在本项目里不可用** —— 它依赖 Conscrypt，而 Conscrypt 没有 linux-aarch64 原生库。
   测试策略因此是**依赖倒置 + 纯 JVM 替身**（`app/src/test/java/com/dreamgirl/app/fake/Fakes.kt`）。

## 三、构建与测试

```bash
# 跑全部单元测试
gradle testDebugUnitTest

# 出调试包
gradle assembleDebug

# 出正式包（需要签名配置，见下）
gradle assembleRelease

# lint
gradle lintDebug
```

产物：`app/build/outputs/apk/release/app-release.apk`

### 签名

正式包需要 `keystore.properties`（**未入库**，请自备）：

```properties
storeFile=/绝对路径/your.jks
storePassword=...
keyAlias=...
keyPassword=...
```

> ⚠️ **签名密钥换了就不能覆盖升级** —— 用户必须先卸载（数据全丢）。
> 如果你只是自己构建来跑，直接用 `assembleDebug` 即可，不需要配密钥。

## 四、⚠️ 改人设之前必读（三道防线）

**人格相关的内容全部是"生成"的，不是手写的。手改生成物会让构建直接失败。**

```
docs/设计方案.md  2.1 节  ──(tools/extract_persona.py)──▶  PersonaVerbatim.kt
persona_src/性格层.md     ──(tools/extract_character.py)──▶  CharacterLayerVerbatim.kt
persona_src/知识卡.md     ──(tools/cards.py)──▶  KnowledgeCardsVerbatim.kt
```

三道防线都挂在 `preBuild` 上：

| 脚本 | 检查什么 |
|---|---|
| `tools/verify_persona.py` | 人格原文逐字一致 + 源文件 sha256 |
| `tools/verify_character.py` | 性格层源 sha256 + 生成物逐字节一致 + 八小节齐全 |
| `tools/verify_cards.py` | 知识卡源 sha256 + 生成物一致 + 每张准则 ≤120 字 |

### 正确的改法

```bash
# 1) 改源文件（性格层 / 知识卡 / 设计方案 2.1 节）
vim persona_src/性格层.md

# 2) 重新生成 + 更新基线（--accept 会同时更新 .sha256）
python3 tools/extract_character.py --accept

# 3) 自检
python3 tools/verify_character.py
```

**不要**直接改 `*Verbatim.kt` —— 下次构建会被拦下来。

### 为什么这么严

用户对人格原文有硬要求：**禁止改动原意**。机械生成 + 构建期校验 + 单测断言，
是为了保证"原文永远是原文"，而不是某次手抄时抄歪了一点点。

**唯一的例外**是显示名 `AppSettings.displayName`（默认 "dream"），它是独立字段，不碰原文。

## 五、数据库

**当前 v5**，一路都是**真迁移**（`ALTER TABLE ADD COLUMN`），聊天记录不丢。

| 版本 | 加了什么 |
|---|---|
| v1→v2 | day_summaries.skippedCount |
| v2→v3 | messages.kind / mediaPath（图片视频文件表情） |
| v3→v4 | day_summaries.herMood |
| v4→v5 | messages.quoteId / quoteText（长按引用） |

> ⚠️ **新版本发布后不能降级** —— 老版本不认新 schema 会走
> `fallbackToDestructiveMigration`，**聊天记录会被清空**。要退先备份。

改 schema 后 `app/schemas/*.json` 会重新生成，**必须一起提交**。

## 六、目录结构

```
app/src/main/java/com/dreamgirl/app/
├─ data/      Room(v5) + 6 张表 + MediaStore + SettingsStore + ApiKeyStore(Keystore 加密)
├─ llm/       LlmApi / DeepSeekClient(OkHttp+SSE) / SseStreamParser / LlmErrors
├─ domain/    ChatEngine(主编排) / MemoryEngine / PromptAssembler / PostProcessor
│             MoodParser(三段输出解析) / EmotionState(情绪与不满) / TimeSense(时间感)
│             OutputHygiene(崩坏检测) / KnowledgeEngine(知识卡检索) / TriggerEngine ...
├─ persona/   PersonaVerbatim(生成) / CharacterLayerVerbatim(生成) / FewShot / PersonaDefaults
├─ sense/     UsageStatsCollector(作息感知)
├─ service/   DreamForegroundService(specialUse 常驻) / BootReceiver
├─ work/      AppScheduler / ProactiveWorker / SummaryWorker
└─ ui/        sessions / chat(长按菜单+引用+多选) / memory / persona / settings / report / theme
```

## 七、她的输出格式（改提示词前先看这个）

她每次回复按三段写，**只有「嘴上」那一段会显示成气泡**：

```
[心想] 他心里的事我不猜，我问他。          ← 不进 UI，只用来回写情绪
[嘴上] 在收拾本子呢。                      ← 她真正说出口的（一行 = 一条消息）
```

不想理他的时候：

```
[心想] 又是这样。
[不回] ……嗯。                              ← 居中灰色小字（内心独白）
```

**两条硬约束**（都有代码兜底，不只靠提示词）：

1. **标记绝不能出现在气泡里** —— `MoodParser` 归一化各种写歪的形式 +
   `scrub` 擦残留 + 引擎再兜一道（带泄漏的段一律不发）
2. **崩坏的输出不能回灌** —— `OutputHygiene` 认出"语言崩溃"的文本，
   组历史时跳过它、入库前触发重写（否则她会沿着崩坏模式一直崩）

## 八、隐私

- **API Key 只存在本机加密容器**（Android Keystore + AES256-GCM），不打进 APK、不上传
- **原始对话不出本机** —— 只有摘要/记忆条目会进 prompt
- 人格原文、性格层、知识卡都在本地，构建期生成

## 九、许可

未声明许可。**在添加 LICENSE 之前，默认保留所有权利** —— 如需他人自由使用/修改，请自行添加
（例如 MIT）。另外注意：`persona_src/知识卡.md` 的部分素材提炼自第三方开源项目
（狗头军师，MIT），如需再分发请保留相应署名。
