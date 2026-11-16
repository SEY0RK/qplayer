# Modification notice (Apache-2.0 section 4b)

This branch is an **unofficial, personal fork** of `TIMER-err/qplayer`, rebuilt on upstream tag `v2.1.1`.

Files in this branch have been modified. Local functionality includes, but is not limited to:

- Windows IME, functionally reproduced on v2.1.1: in-field pinyin preedit, a candidate window that follows the composing caret, and two input-context reconnect rounds after first focus. `ImePositioner` is the single writer of the candidate form; upstream `WinIme.java` is retained as a coordinate helper for `MacInput`.
- qml4j engine: local IME and text-rendering patches rebased onto `v0.2.39` in the private repository `SEY0RK/qml4j-ime`, branch `main`.
- Search source filter, local playlist search, playlist empty states, Bilibili plugin.
- Android `perf` variant labelled "QPlayer 本地版" with an explicit signing config and certificate-fingerprint assertion.
- Desktop packaging on the JDK 21 + jlink + jpackage JVM portable route (upstream ships a GraalVM native build).
- IME guard with baseline hashes, and a manual-only upstream sync policy.

Upstream `LICENSE.md` and all `NOTICE` files are retained. This fork is not affiliated with or endorsed by TIMER-err.
