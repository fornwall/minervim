# Android Neovim

- Keep minSdk and targetSdk at 37; compileSdk 37.2; NDK 30.0.16248370.
- Only ship arm64-v8a and x86_64; preserve 16 KiB native library alignment.
- Neovim runs on a dedicated thread inside the app process. Keep Android's UI
  thread separate; communicate through queued RPC, not concurrent editor calls.
- The view consumes Neovim grid events directly. Do not add a terminal emulator.
- Keep grid mutations on the UI thread and publish only complete `flush` frames.
- Preserve native process-exit and subprocess guards, and Android signal ownership.
- Use Gradle for build/test entry points. Native inputs and patches belong in
  `native/`; downloaded sources and intermediates belong in ignored `native/work/`.
- Run `./gradlew build` and relevant device tests for integration changes.
- Keep prototype limitations and reproducible build requirements in README.md.
