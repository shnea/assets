FROM gradle:9.7.1-jdk21 AS development
WORKDIR /workspace
COPY settings.gradle.kts build.gradle.kts ./
COPY src ./src
ENV GRADLE_USER_HOME=/cache/gradle
CMD ["gradle", "--no-daemon", "test"]

FROM development AS build
RUN --mount=type=cache,target=/cache/gradle gradle --no-daemon bootJar -x test

FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
RUN addgroup -S assets && adduser -S assets -G assets
COPY --from=build /workspace/build/libs/assets-api-0.1.0.jar app.jar
USER assets
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
