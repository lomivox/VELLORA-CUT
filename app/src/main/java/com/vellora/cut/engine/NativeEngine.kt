package com.vellora.cut.engine

/**
 * Kotlin-side bridge to the native (C++/FFmpeg) engine.
 * The System.loadLibrary + external fun calls are added once the
 * NDK/CMake build is enabled in app/build.gradle.kts (Phase 0 milestone).
 *
 * Left intentionally empty for now — no native lib to load yet.
 */
object NativeEngine {
    // external fun processVideo(inputPath: String, outputPath: String): Boolean
    // init { System.loadLibrary("vellora_engine") }
}
