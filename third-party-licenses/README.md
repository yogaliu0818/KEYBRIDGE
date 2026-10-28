# Third-party notices

This directory supplements the original license and copyright notices retained in the source tree. It does not establish a license for KEYBRIDGE's own code.

- whisper.cpp and ggml: see `Android/app/src/main/cpp/whisper/LICENSE` (MIT). Its source tree also includes separately licensed third-party material. Preserve the notices in individual files and the additional LICENSE files within that tree.
- `examples/ffmpeg-transcode.cpp` has an SPDX `GPL-2.0` notice; `GPL-2.0.txt` contains the version 2 license text. The KEYBRIDGE CMake configuration disables whisper.cpp examples. This fact does not remove the license requirements when redistributing the source file.
- `ggml/src/ggml-sycl/` contains files marked Apache-2.0 with LLVM-exception; the corresponding texts are `Apache-2.0.txt` and `LLVM-exception.txt`. This backend is not enabled by the project's Android configuration.
- Android debug runtime dependencies resolved from this source are listed in `android-debug-dependencies.txt`, with license names read from locally cached Maven POMs. AndroidX, Kotlin and coroutines entries indicate Apache 2.0; `Apache-2.0.txt` supplies the text. The `com.google.guava:listenablefuture:1.0` POM does not itself declare a license; Guava's upstream project publishes Apache 2.0 at https://github.com/google/guava/blob/master/LICENSE. POM metadata is not a complete binary-composition or compliance audit.
- Gradle wrapper scripts include their own Apache 2.0 notices. Other vendored examples, tests, and libraries retain their original license files and copyright notices in the whisper.cpp tree.

The published APK is a pre-existing debug build; a dependency graph resolved later from source does not independently prove its exact composition. Model downloads have their own upstream terms and are not included here.
