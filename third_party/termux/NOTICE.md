# Termux terminal libraries

This project uses only `terminal-view` and `terminal-emulator` from
https://github.com/termux/termux-app at commit
`8629e632fcb95da272221be327db653fb24befe9`.
It does not include the Termux app or termux-shared module.

The Java modules and their native terminal library are built from source.
`terminal-emulator/src/main/jni/termux.c` comes from that same pinned commit.
The Gradle build uses Android NDK `26.3.11579264` and the module's `Android.mk`;
64-bit native LOAD segments are linked with 16 KiB alignment. No upstream
binary AAR or `.so` file is needed in this checkout.

DualStackLab modifications to TerminalSession.java (October 13, 2026):
- Use independently duplicated file descriptors for the reader and writer.
- Poll the PTY reader and wait for both process exit and reader completion
  before draining the remaining queue, cleaning up, and notifying the UI.
- Continue draining all readable bytes after parent exit without a fixed
  wall-clock cutoff. An idle poll after parent exit ends reading when a
  detached descendant retains the PTY. Continuously producing descendants
  can keep the session open until explicit stop or natural process exit.
- Dispatch copied input bytes in order on a background executor with a
  1 MiB pending-byte limit. Blocking PTY stdin no longer blocks the UI thread.
The original source is available at the pinned revision above.

Upstream declares GPL-3.0-only for the repository, with an Apache-2.0
exception for Android Terminal Emulator code in these two modules.
The upstream license statement and both license texts are included in
this directory. This notice does not relicense upstream code.

Source archive:
https://codeload.github.com/termux/termux-app/zip/8629e632fcb95da272221be327db653fb24befe9

No code or resources from MT Manager are bundled.

The terminal-view module uses sources from the same 8629e632fc revision. Local changes keep text selection separate from body touch gestures and localize its actions.
