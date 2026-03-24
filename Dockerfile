# syntax=docker/dockerfile:1.7

# ── Build Stage ───────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

# Copy pom first for dependency cache
COPY pom.xml .

# Use Docker cache for Maven dependencies (VERY FAST)
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -B -DskipTests dependency:go-offline

# Copy source after deps
COPY src ./src

# Build with cache and rename JAR to avoid glob ambiguity
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -DskipTests package \
 && mv target/*.jar target/app.jar


# ── Runtime Stage ─────────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

RUN addgroup --system betaschool \
 && adduser --system --ingroup betaschool --no-create-home betaschool

COPY --from=build /app/target/app.jar app.jar

USER betaschool

EXPOSE 8080

ENV JAVA_OPTS="-Xms256m -Xmx512m"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]