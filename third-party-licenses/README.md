# Third-party notices

This directory supplements the original license and copyright notices retained in the source tree. It does not establish a license for KEYBRIDGE's own code.

- Android debug runtime dependencies resolved from this source are listed in `android-debug-dependencies.txt`, with license names read from locally cached Maven POMs. AndroidX, Kotlin and coroutines entries indicate Apache 2.0; `Apache-2.0.txt` supplies the text. The `com.google.guava:listenablefuture:1.0` POM does not itself declare a license; Guava's upstream project publishes Apache 2.0 at https://github.com/google/guava/blob/master/LICENSE. POM metadata is not a complete binary-composition or compliance audit.
- Gradle wrapper scripts include their own Apache 2.0 notices. This version no longer vendors whisper.cpp or any local speech-recognition model.

The published APK is a pre-existing debug build; a dependency graph resolved later from source does not independently prove its exact composition. Model downloads have their own upstream terms and are not included here.
