#!/usr/bin/env bash
# Benchmarks the terminal's native side as ghostty benchmarks its own
# (ghostty's src/benchmark/AGENTS.md): input made once with ghostty-gen,
# zagal-bench built optimized for each revision compared, hyperfine timing
# them on the same input.
#
#   mise run bench                    # this tree against main
#   ZAGAL_BENCH_BASE=v1.2.1 mise run bench
#   ZAGAL_BENCH_MODES="stream scroll" mise run bench
#
# The base needs zagal-bench too (src/bench.zig): revisions from when it came.
# Everything lands in build/bench, the corpora kept across runs.
set -euo pipefail
cd "$(dirname "$0")/../.."
out=build/bench
mkdir -p "$out"
base=${ZAGAL_BENCH_BASE:-main}
modes=${ZAGAL_BENCH_MODES:-stream full scroll select}

# ghostty-gen, from the submodule (ReleaseFast, no app).
gen=$out/ghostty/bin/ghostty-gen
if [[ ! -x $gen ]]; then
  (cd terminal/ghostty && zig build -Demit-bench -Doptimize=ReleaseFast \
    -Dapp-runtime=none -Demit-exe=false --prefix "$PWD/../../$out/ghostty")
fi
# Output as a TUI or a log writes it: colored text in runs, and plain lines.
[[ -s $out/styled.vt ]] || "$gen" +styled --seed=1 --style-rate=0.3 | head -c 4000000 > "$out/styled.vt"
[[ -s $out/lines.vt ]] || "$gen" +ascii --line-min=20 --line-max=120 | head -c 4000000 > "$out/lines.vt"

# zagal-bench for this tree, and for the base in a worktree of its own.
build() { (cd "$1/terminal/native" && zig build bench --prefix "$2" >/dev/null); }
build . "$PWD/$out/head"
tree=$(mktemp -d)
trap 'git -C terminal/ghostty worktree remove --force "$tree/terminal/ghostty" >/dev/null 2>&1 || true
  git worktree remove --force "$tree" >/dev/null 2>&1 || true' EXIT
git worktree add -q --detach "$tree" "$base"
# The base builds against the ghostty it pins, which a bump of the submodule
# changes: a worktree of our checkout at that commit (fetched if it's missing).
pin=$(git rev-parse "$base:terminal/ghostty")
git -C terminal/ghostty cat-file -e "$pin^{commit}" 2>/dev/null || git -C terminal/ghostty fetch -q --depth 1 origin "$pin"
rmdir "$tree/terminal/ghostty"
git -C terminal/ghostty worktree add -q --detach "$tree/terminal/ghostty" "$pin"
build "$tree" "$PWD/$out/base"

for mode in $modes; do
  for data in styled lines; do
    hyperfine --warmup 3 --runs 10 -N \
      -n "$base $mode $data" "$out/base/bin/zagal-bench $mode --data=$out/$data.vt" \
      -n "this $mode $data" "$out/head/bin/zagal-bench $mode --data=$out/$data.vt"
  done
done
