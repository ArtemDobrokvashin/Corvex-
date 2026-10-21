# syntax=docker/dockerfile:1
# --- build stage ---
FROM maven:3.9-eclipse-temurin-26 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

# --- runtime stage (non-root, минимальный JRE-образ) ---
FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S corvex && adduser -S corvex -G corvex
WORKDIR /app
COPY --from=build /src/target/*.jar app.jar
# Каталог данных должен существовать в образе и принадлежать corvex:
# при первом старте Docker инициализирует named volume содержимым и правами образа,
# иначе том будет root:root и non-root процесс не сможет писать файлы чата.
RUN mkdir -p /data/chat-files && chown -R corvex:corvex /data/chat-files
USER corvex
EXPOSE 8089
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -Duser.timezone=Europe/Moscow"
HEALTHCHECK --interval=30s --timeout=5s --retries=3 --start-period=60s \
  CMD wget -qO- http://127.0.0.1:8089/actuator/health || exit 1
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar app.jar"]
