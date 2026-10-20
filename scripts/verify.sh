#!/usr/bin/env bash
# Corvex: локальные ворота готовности на хосте с JDK 17, Maven 3.9+, Node.js 22.12+ и Docker.
# Использование: scripts/verify.sh [--docker-build]
#   без флагов      — frontend-тесты/сборка, mvn verify всех сервисов, проверка compose-конфига
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

step "Docker Compose: config validation"
docker compose config -q

if [ "$DOCKER_BUILD" = "1" ]; then
  step "Docker Compose: build all images"
  docker compose build
fi

step "ALL LOCAL GATES PASSED"
echo "Next: docker compose up -d on a test host, then the runtime smokes in docs/READINESS.md."
