# Local patches (baseline: upstream v1.9.8)

`patches/ime/` contains every IME / candidate-window change. After each upstream tag, run `git apply --check patches/ime/local-ime-v198.patch` before merging upstream into `local`; a failure means upstream touched protected code and requires manual resolution. Never use `-X ours` or `merge=ours` as a substitute for this check.

`patches/features/` holds the remaining local features. Both files were generated from the `v1.9.8...local` comparison on GitHub.
