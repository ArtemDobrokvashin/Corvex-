#!/usr/bin/env bash
# Corvex: локальные ворота готовности на хосте с JDK 17, Maven 3.9+, Node.js 22.22.2+/24.15+/26+ и Docker.
# Использование: scripts/verify.sh [--docker-build]
#   без флагов      — frontend, Python unit/static tests, mvn verify всех сервисов, compose config
#   --docker-build  — дополнительно собрать все Docker-образы (долго)
set -euo pipefail
cd "$(dirname "$0")/.."

DOCKER_BUILD=0
for arg in "$@"; do
  case "$arg" in
    --docker-build) DOCKER_BUILD=1 ;;
    *) echo "Unknown argument: $arg" >&2; exit 2 ;;
  esac
done

step() { printf '\n=== %s ===\n' "$1"; }

step "Toolchain"
java -version
mvn -version | head -1
node --version
npm --version
python3 --version
docker --version
docker compose version

step "Frontend: tests + production build (modern + legacy Chrome 109)"
(
  cd frontend
  npm ci
  npm test
  npm run build
  npm audit --omit=dev --audit-level=moderate
)

step "Java: mvn verify for all 12 services (tests + JaCoCo gates)"
mvn -B clean verify

step "Python operations + alert relay: unit/static tests"
python3 -m unittest discover -s scripts/tests -p 'test_*.py'
sh -n infra/prometheus/alertmanager-entrypoint.sh

step "Docker Compose: config validation"
docker compose config -q

if [ "$DOCKER_BUILD" = "1" ]; then
  step "Docker Compose: build all images"
  docker compose build
fi

step "ALL LOCAL GATES PASSED"
echo "Next: docker compose up -d on a test host, then the runtime smokes in docs/READINESS.md."
