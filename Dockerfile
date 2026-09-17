# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn clean package -DskipTests -B

# ---- Runtime stage ----
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

RUN groupadd -r spring && useradd -r -g spring spring

COPY --from=build /build/target/*.jar app.jar

# Own the whole /app directory, not just the jar -- LocalDiskObjectStorageService
# (only active when STORAGE_PROVIDER=local, the default if unset) creates an
# ./uploads subdirectory under the working dir at startup. Chowning only
# app.jar left /app itself root-owned, so the non-root `spring` user got
# AccessDeniedException trying to mkdir inside it. This also means local
# storage still works as a fallback if STORAGE_PROVIDER is ever left unset --
# though on Render specifically, prefer STORAGE_PROVIDER=imagekit regardless,
# since a container's local filesystem doesn't survive a redeploy anyway.
RUN chown -R spring:spring /app
USER spring

EXPOSE 8080

# Explicit heap/metaspace caps instead of relying on the JVM's container-aware
# auto-sizing (~25% of the cgroup limit by default) -- firebase-admin (gRPC +
# Netty), webp-imageio's native binding, mysql-connector-j, and full
# Hibernate/JPA all classload during startup, and on Render's smaller tiers
# that default guess can be too generous, letting the JVM get killed by the
# host before it ever prints a clean OutOfMemoryError. Tune these to roughly
# 70-75% of whatever the Render plan's RAM actually is.
# TieredStopAtLevel=1 skips the JIT's optimizing compiler tiers -- trades
# steady-state peak performance (irrelevant for a low-traffic shop app) for
# faster startup, which matters on a CPU-throttled free instance. Xshare=auto
# turns on Class Data Sharing when the JDK image has an archive available,
# speeding up class loading at no cost.
ENTRYPOINT ["java", "-Xmx384m", "-XX:MaxMetaspaceSize=192m", "-XX:TieredStopAtLevel=1", "-Xshare:auto", "-jar", "/app/app.jar"]
