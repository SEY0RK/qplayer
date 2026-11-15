# Upstream sync policy

**A GitHub fork does NOT sync automatically.** Upstream pushes after the fork was created never enter this repository on their own. Documented options are the web **Sync fork** button, `gh repo sync`, and `git fetch upstream` + `git merge`.

This repository does **not** run any scheduled sync. To pick up upstream changes, manually run the `sync-upstream` workflow (`workflow_dispatch`) or use one of the three manual methods above.

Every sync MUST pass the IME guard before merging into `local`: `patches/ime/*` must apply cleanly, `.github/IME_BASELINE.sha256` must match, deleted IME files must stay deleted, and `mvn test` must pass. On conflict or guard failure the workflow opens a PR/issue and stops; it never auto-merges into `local`.

Forks also do NOT inherit Secrets, Variables, branch protection or the Actions enabled switch; all were reconfigured after forking.
