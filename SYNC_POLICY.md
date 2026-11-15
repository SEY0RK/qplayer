# Upstream sync policy

**A GitHub fork does NOT sync automatically.** Upstream pushes after the fork was created never enter this repository on their own. Documented options are the web **Sync fork** button, `gh repo sync`, and `git fetch upstream` + `git merge`.

This repository does **not** run any scheduled sync. To pick up upstream changes, manually run the `sync-upstream` workflow (`workflow_dispatch`) or use one of the three manual methods above.

Every sync into `local-v2` MUST pass the IME guard: `.github/IME_BASELINE.sha256` must match, deleted IME files must stay deleted, and the pinned patched-engine branch (`v0.2.39-ime` / `v0.2.39`) must stay unchanged. On conflict or guard failure the workflow opens a PR/issue and stops; it never auto-merges into `local-v2`.

The `local` branch is the frozen v1.9.8-based build and is never modified. `master` is kept as a pristine upstream mirror.

Forks also do NOT inherit Secrets, Variables, branch protection or the Actions enabled switch; all were reconfigured after forking.
