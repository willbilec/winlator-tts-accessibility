#!/usr/bin/env bash
set -euo pipefail

worktree="${1:?usage: normalize-wine-worktree.sh WINE_WORKTREE}"
cd "$worktree"
git rev-parse --is-inside-work-tree >/dev/null

while IFS= read -r -d '' file; do
    if grep -Iq . "$file"; then
        sed -i 's/\r$//' "$file"
    fi
done < <(git ls-files -z)
