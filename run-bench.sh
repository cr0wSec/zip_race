#!/usr/bin/env bash
#
# Usage:
#   ./run-bench.sh [users]

#   ./run-bench.sh         # 1000 users (default)
#   ./run-bench.sh <number_of_users>       # 100 users¨
#
# The app must already be running.
#
# Reports are written to ./gatling-reports/ on the host.

# -e: script stops when a command fails, no silent error
# -u: throws error if undefined variable is used
# -o pipefail: return code is from the first command that fails "cmd1 | cmd2"
set -euo pipefail

# define default / given parameter
USERS="${1:-1000}"

echo "BENCHMARKING ==> Running Gatling benchmark with ${USERS} concurrent users"
echo "BENCHMARKING ==> Reports will be saved to ./gatling-reports/"
echo ""

# env variable only defined for that specific command, used in docker-compose.yml
ZIPRACE_USERS="${USERS}" docker compose --profile bench run --rm gatling

# display report file path
echo ""
echo "BENCHMARKING ==> Bench complete. Latest report:"
ls -td ./gatling-reports/*/ | head -1