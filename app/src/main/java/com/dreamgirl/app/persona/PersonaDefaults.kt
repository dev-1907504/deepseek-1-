package com.dreamgirl.app.persona

import com.dreamgirl.app.data.PersonaEntity

/**
 * 人设的「出厂值」。
 *
 * 这里的每一个字都直接取自 [PersonaVerbatim]（由 tools/extract_persona.py 从
 * 方案 2.1 节机械生成）。**本文件不包含任何手写的人格描述**——手写的内容
 * 一律放到 [FewShot] 里，与原文并存，绝不覆盖原文。
 */
object PersonaDefaults {

    fun entity(now: Long = System.currentTimeMillis()): PersonaEntity = PersonaEntity(
        id = 1,
        name = PersonaVerbatim.NAME,
        image = PersonaVerbatim.IMAGE,
        character = PersonaVerbatim.CHARACTER,
        insight = PersonaVerbatim.INSIGHT,
        faith = PersonaVerbatim.FAITH,
        speech = PersonaVerbatim.SPEECH,
        principles = PersonaVerbatim.PRINCIPLES,
        customized = false,
        updatedAt = now,
    )

    /** 全部段落是否仍与方案原文逐字一致。 */
    fun isVerbatim(p: PersonaEntity): Boolean =
        p.name == PersonaVerbatim.NAME &&
            p.image == PersonaVerbatim.IMAGE &&
            p.character == PersonaVerbatim.CHARACTER &&
            p.insight == PersonaVerbatim.INSIGHT &&
            p.faith == PersonaVerbatim.FAITH &&
            p.speech == PersonaVerbatim.SPEECH &&
            p.principles == PersonaVerbatim.PRINCIPLES
}
