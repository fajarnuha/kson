#!/usr/bin/env bash
# Runs every case in jq-parity-cases.txt through jq and kson and reports differences.
# Usage: scripts/jq-parity.sh <path-to-kson-binary> [jq-binary]
# Case format (one per line): flags ::: filter ::: input     ('#' starts a comment line)
set -u
KSON=${1:?path to kson binary}
JQ=${2:-jq}
CASES="$(dirname "$0")/jq-parity-cases.txt"
pass=0; fail=0
while IFS= read -r line || [ -n "$line" ]; do
  [[ -z "$line" || "$line" == \#* ]] && continue
  flags="${line%% ::: *}"; rest="${line#* ::: }"
  filter="${rest%% ::: *}"; input="${rest#* ::: }"
  # shellcheck disable=SC2086
  expected=$(printf '%s' "$input" | $JQ -c $flags "$filter" 2>/dev/null); ec_jq=$?
  # shellcheck disable=SC2086
  actual=$(printf '%s' "$input" | "$KSON" -c $flags "$filter" 2>/dev/null); ec_kson=$?
  # Both failing counts as parity (error texts differ by design); otherwise output and exit code must match.
  if [[ $ec_jq -ne 0 && $ec_kson -ne 0 && "$expected" == "$actual" ]] || [[ "$expected" == "$actual" && $ec_jq -eq $ec_kson ]]; then
    pass=$((pass+1))
  else
    fail=$((fail+1))
    printf 'MISMATCH  flags=[%s] filter=[%s] input=[%s]\n  jq   (%s): %s\n  kson (%s): %s\n' \
      "$flags" "$filter" "$input" "$ec_jq" "${expected//$'\n'/ | }" "$ec_kson" "${actual//$'\n'/ | }"
  fi
done < "$CASES"
echo "jq parity: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
