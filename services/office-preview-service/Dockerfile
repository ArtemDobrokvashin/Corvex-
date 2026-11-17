# syntax=docker/dockerfile:1
# --- build stage ---
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY maven-settings.xml /root/.m2/settings.xml
COPY pom.xml .
RUN --mount=type=cache,id=corvex-maven-repository,target=/root/.m2/repository,sharing=locked mvn -q -B dependency:go-offline
COPY src ./src
RUN --mount=type=cache,id=corvex-maven-repository,target=/root/.m2/repository,sharing=locked mvn -q -B package -DskipTests

# --- runtime stage: Debian + LibreOffice ---
# LibreOffice нельзя ставить в Alpine-образы остальных сервисов: пакетов нет,
# а musl-сборка не поддерживается. Отдельный слой также изолирует падения конвертера.
FROM eclipse-temurin:17-jre-jammy
ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    HOME=/tmp \
    XDG_CACHE_HOME=/tmp/.cache \
    XDG_CONFIG_HOME=/tmp/.config \
    SAL_USE_VCLPLUGIN=svp \
    JAVA_OPTS="-XX:MaxRAMPercentage=35 -XX:+UseG1GC -Duser.timezone=Europe/Moscow"

RUN apt-get update && apt-get install -y --no-install-recommends \
        libreoffice-writer \
        libreoffice-calc \
        libreoffice-impress \
        fonts-dejavu-core \
        fonts-liberation2 \
        fonts-crosextra-carlito \
        fonts-crosextra-caladea \
        fontconfig \
        ca-certificates \
        curl \
    && fc-cache -f \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --uid 10001 --create-home --shell /usr/sbin/nologin office \
    && mkdir -p /var/cache/office-preview \
    && chown -R office:office /var/cache/office-preview

WORKDIR /app
COPY --from=build /src/target/*.jar app.jar
RUN chown office:office /app/app.jar

USER office
EXPOSE 8092
HEALTHCHECK --interval=30s --timeout=5s --retries=3 --start-period=60s \
  CMD curl -fsS http://127.0.0.1:8092/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
