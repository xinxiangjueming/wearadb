package com.wearadb.adb

/**
 * 把路径等参数包成 shell 单引号字面量，避免被设备端 `sh` 拆词或做元字符展开。
 *
 * **为什么必须做**：设备端 `ls` 在 raw 通道下会把空格转义成 `\ `，经
 * [AdbOutputParser] 反转义后，[com.wearadb.data.model.FileEntry.path] 里是**真正的空格**。
 * 此时 `rm -rf $path` 会被 sh 拆成多个参数，而 `-f` 会让 rm **静默失败**：
 * 返回空字符串、退出码 0、文件原封不动 —— 表现就是「点了删除没反应」。
 *
 * 单引号内部除 `'` 之外全是字面量，故只需把 `'` 写成 `'\''`。
 *
 * 注意：本函数**不能**替代 libadb 的 A_OPEN 长度缺陷修复 —— 命令里含非 ASCII
 * （中文路径）时是客户端侧发包前就抛异常（见 `:libadb-android` 模块 AdbProtocol.generateOpen
 * 的 PATCH），与设备端 shell 引号无关，两件事必须都做。
 */
internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
