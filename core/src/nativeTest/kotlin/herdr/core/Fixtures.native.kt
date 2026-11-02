package herdr.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
actual fun fixturesDir(): String = checkNotNull(getenv("HERDR_FIXTURES")?.toKString())
