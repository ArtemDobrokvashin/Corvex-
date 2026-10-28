# syntax=docker/dockerfile:1
# --- build stage ---
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY maven-settings.xml /root/.m2/settings.xml
COPY pom.xml .
RUN --mount=type=cache,id=corvex-maven-repository,target=/root/.m2/repository,sharing=locked mvn -q -B dependency:go-offline
COPY src ./src
RUN --mount=type=cache,id=corvex-maven-repository,target=/root/.m2/repository,sharing=locked mvn -q -B package -DskipTests

# --- runtime stage (non-root, минимальный JRE-образ) ---
FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S -g 101 corvex && adduser -S -u 100 -G corvex corvex
WORKDIR /app
COPY --from=build /src/target/*.jar app.jar
USER corvex
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -Duser.timezone=Europe/Moscow"
HEALTHCHECK --interval=30s --timeout=5s --retries=3 --start-period=60s \
  CMD wget -qO- http://127.0.0.1:8080/actuator/health || exit 1
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar app.jar"]
