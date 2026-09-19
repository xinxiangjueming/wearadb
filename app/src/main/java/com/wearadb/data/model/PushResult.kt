package com.wearadb.data.model

/**
 * 文件推送结果：把「成功与否」与「展示文案」拆开。
 *
 * 背景：此前 pushFile() 直接返回中文文案，调用方一律用
 * `pushFile(...).contains("成功")` 判定成功——一旦文案走多语言（英文 "Success"），
 * 这个判据会静默失效、所有 push 都被判成失败。故改为返回结构化结果：
 *  - [success] 供逻辑判断，唯一权威来源；
 *  - [message] 仅供展示，可安全本地化。
 *
 * 注意：success 的取值刻意沿用旧判据的语义（仅 OKAY 视为成功），
 * 未知响应码虽文案为"推送完成"，旧代码同样判为失败，此处保持一致。
 */
data class PushResult(
    val success: Boolean,
    val message: String
)
