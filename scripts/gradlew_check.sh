#!/usr/bin/env bash
set -euo pipefail

# Build and assemble debug
./gradlew assembleDebug --no-daemon -g 2>&1 | tee build.log

# Run static analysis (Kotlin lint)
./gradlew lintDebug --no-daemon -g 2>&1 | tee lint.log

# Git check – show uncommitted changes
git status -s > git.status.txt

# Auto‑stage and commit any changes (if any)
OUTPUT=$(git diff --name-only)
if [ -n "$OUTPUT" ]; then
  git add -A
  git commit -m "Auto generated fixes (by script)" || true
  git push
fi

# Exit without error for the main CI job; the real exit status is whatever gradle returned.
exit 0
