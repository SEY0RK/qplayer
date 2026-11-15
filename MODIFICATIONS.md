# Modification notice (Apache-2.0 section 4b)

This branch is an **unofficial, personal fork** of `TIMER-err/qplayer`, based on upstream tag `v1.9.8`.

Files in this branch have been modified. Local changes include, but are not limited to:/n/n- Windows IME candidate window: single-writer `ImePositioner`, in-field preedit, `WinIme.java` removed.
- `desktop-host/pom.xml`: LWJGL 3.4.3 for GLFW 3.5 preedit API.
- qml4j engine: local IME/text-rendering patches (separate private repository `qml4j-ime`).
- Local playlist, search source filter, Bilibili plugin, Android `perf` variant labelled "QPlayer 本地版".
- Desktop packaging kept on the JDK 21 + jlink + jpackage JVM portable route (upstream switched to GraalVM native in v1.9.3).

Upstream `LICENSE.md` and all `NOTICE` files are retained. This fork is not affiliated with or endorsed by TIMER-err.
