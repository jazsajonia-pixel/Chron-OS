# Display-pipeline test on a PC

Proves, without a phone, that a real Windows program draws into PocketOS's own display server and receives mouse and
keyboard input through `DisplayView` (the view shown inside a PocketOS window).

1. Unpack the runtime to `/data/data/com.chron.os/files/rootfs` (run
   `./gradlew :app:testDebugUnitTest -Ppocketos.runtimeIntegration=true --tests '*EmbeddedRuntimeIntegrationTest*'`
   as a user that can write there) and register qemu-user binfmt for aarch64.
2. `tools/runtime/host-display-test/build.sh` — builds the display server's C code for x86-64 (with small stand-ins
   for Android headers; `host_jni.py` restores the JNI arguments that Android's `@CriticalNative` methods omit) and
   `click_test.exe`.
3. `./gradlew :app:testDebugUnitTest -Ppocketos.displayPipeline=true --tests '*DisplayPipelineTest*'`

Optional: `POCKETOS_TEST_LD_PRELOAD=$(gcc -print-file-name=libasan.so)` with a build using `-fsanitize=address`
checks the native code for memory errors (this found the overlapping `memcpy` fixed in `xinput_stream.c`).
