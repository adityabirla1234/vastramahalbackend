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

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
