#!/usr/bin/env bash
#
# Runs every check in this directory, in order, and reports which failed.
#
# Keeps going after a failure rather than stopping at the first: the checks cover different layers,
# and knowing that (say) the Maven realm still works while introspection broke is most of the
# diagnosis.

set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

failed=()
for check in [0-9][0-9]-*.sh; do
    if ! bash "$check"; then
        failed+=("$check")
    fi
done

printf '\n\033[1m=== summary ===\033[0m\n'
if [[ ${#failed[@]} -eq 0 ]]; then
    printf '\033[32mall checks passed\033[0m\n'
    exit 0
fi
printf '\033[31mfailed:\033[0m\n'
printf '  %s\n' "${failed[@]}"
exit 1
