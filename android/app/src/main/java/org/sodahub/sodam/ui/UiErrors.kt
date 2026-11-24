package org.sodahub.sodam.ui

import uniffi.sodam_ffi.SodaException

/// UniFFI 错误 → 用户可读文案。生成的类把原始原因放在 `reason` 字段里。
internal fun SodaException.readable(): String = when (this) {
    is SodaException.Auth -> reason
    is SodaException.Network -> reason
    is SodaException.Internal -> reason
}
