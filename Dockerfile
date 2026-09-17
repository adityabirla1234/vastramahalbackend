# ---- Build stage ----
# Official Maven image with JDK 17 baked in -- matches pom.xml's
# <java.version>17</java.version> exactly, so there's no mismatch between
# what builds this and what your local IntelliJ setup targets.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Copy just the POM first and resolve dependencies before copying source.
# Docker caches each layer -- as long as pom.xml doesn't change, this
# dependency-download layer is reused on every rebuild, so only actual code
# changes trigger a re-download, not every single deploy.
COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src

# -DskipTests: your tests use @SpringBootTest, which boots the full Spring
# context and needs a real DB connection -- one isn't available in this
# build environment. Run tests in CI against a real/containerized DB
# instead (see .github/workflows); this build step is purely "produce the
# deployable jar," not "verify correctness."
RUN mvn clean package -DskipTests -B

# ---- Runtime stage ----
# jre (not jdk) -- smaller image, and nothing here needs a compiler at
# runtime. -jammy (Ubuntu-based) rather than -alpine: alpine's musl libc
# has occasionally caused subtle native-library issues with some Java
# libraries (this project pulls in several -- gRPC, Netty, WebP codecs via
# webp-imageio); jammy trades a slightly larger image for fewer surprises.
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Run as a non-root user -- standard container hardening, costs nothing.
RUN groupadd -r spring && useradd -r -g spring spring

# Spring Boot's repackage plugin renames the original pre-repackage jar to
# *.jar.original, so this wildcard safely matches only the one real
# executable jar -- not two.
COPY --from=build /build/target/*.jar app.jar
RUN chown spring:spring app.jar
USER spring

# Documents the port for anyone reading this file -- Render doesn't
# actually require EXPOSE to route traffic. What DOES matter: Render sets
# a $PORT env var at runtime and expects the app to bind to it, which
# server.port=${PORT:8080} in application.properties already handles.
# Nothing Docker-specific is needed for that part.
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
