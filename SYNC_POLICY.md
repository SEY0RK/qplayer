# Upstream sync policy

**A GitHub fork does NOT sync automatically.** Upstream pushes after the fork was created never enter this repository on their own. Documented options are the web **Sync fork** button, `gh repo sync`, and `git fetch upstream` + `git merge`.

This repository does **not** run any scheduled sync. To pick up upstream changes, manually run the `sync-upstream` workflow (`workflow_dispatch`) or use one of the three manual methods above.

Every sync into `master` MUST pass the IME guard: `.github/IME_BASELINE.sha256` must match, deleted IME files must stay deleted, and the pinned patched-engine branch (`main` / `v0.2.39`) must stay unchanged. On conflict or guard failure the workflow opens a PR/issue and stops; it never auto-merges into `master`.

All feature work lives on `master`. The old `local` and `local-v2` branches were removed; upstream is an optional remote merged in through the guard, not a second branch.

Forks also do NOT inherit Secrets, Variables, branch protection or the Actions enabled switch; all were reconfigured after forking.
