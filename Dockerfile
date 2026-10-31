# syntax=docker/dockerfile:1
# One image definition for every service. The build stage is identical for all of them, so
# Docker builds the whole reactor once and each service image only copies its own jar.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.m2 mvn -q -B package -DskipTests

FROM eclipse-temurin:21-jre
ARG MODULE
COPY --from=build /workspace/${MODULE}/target/${MODULE}-*.jar /app/app.jar
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
