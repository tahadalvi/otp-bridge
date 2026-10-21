// Versions verified against the live Maven metadata on 2026-07-31 rather than
// pinned from memory. AGP 8.13.x needs Gradle 8.13+; the wrapper is on 8.14.3.
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}
