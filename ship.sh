#!/usr/bin/env bash
# Create a GitHub repo from this template and push it.
#
#   ./ship.sh my-surface-app            private repo (default)
#   ./ship.sh my-surface-app --public
#
# Requires git. Uses the GitHub CLI (gh) when available; otherwise prints the
# two commands to finish by hand.
set -euo pipefail

NAME="${1:-pixel-surface-lab}"
VIS="--private"
[[ "${2:-}" == "--public" ]] && VIS="--public"

command -v git >/dev/null || { echo "git is required"; exit 1; }

echo "==> Verifying the project before pushing"
python3 tools/verify.py . || { echo "Verification failed - not pushing."; exit 1; }

if [ ! -d .git ]; then
  git init -b main
fi
git add -A
git diff --cached --quiet || git commit -m "Pixel Surface Lab template"

if command -v gh >/dev/null && gh auth status >/dev/null 2>&1; then
  echo "==> Creating GitHub repo '$NAME'"
  gh repo create "$NAME" $VIS --source=. --push

  OWNER=$(gh api user --jq .login)
  # Point the README badges at the real repo.
  sed -i.bak "s|OWNER/REPO|$OWNER/$NAME|g" README.md && rm -f README.md.bak
  git add README.md
  git diff --cached --quiet || { git commit -m "Point badges at $OWNER/$NAME"; git push; }

  echo
  echo "==> Done. Watch the build:"
  gh run watch --exit-status 2>/dev/null || true
  echo "    Releases: https://github.com/$OWNER/$NAME/releases/latest"
  echo "    Open that on your Pixel and tap the .apk."
else
  cat <<MANUAL

==> Committed locally. The GitHub CLI is not installed or not authenticated,
    so finish with:

      # create an empty repo at https://github.com/new  (do NOT add a README)
      git remote add origin git@github.com:<you>/$NAME.git
      git push -u origin main

    Then edit README.md and replace OWNER/REPO with <you>/$NAME so the
    badges resolve.

MANUAL
fi
