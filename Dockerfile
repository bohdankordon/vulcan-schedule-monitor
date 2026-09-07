# syntax=docker/dockerfile:1
FROM eclipse-temurin:21.0.12_8-jdk-noble@sha256:75ce56643243c3db632be2ef259625fb42ee3be1334389659f7a1a61acb78783 AS dependencies
WORKDIR /build
COPY .mvn/ .mvn/
COPY --chmod=755 mvnw ./mvnw
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp dependency:go-offline

FROM dependencies AS build
COPY src/main/ src/main/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -Dmaven.test.skip=true package \
    && mv target/vulcan-schedule-monitor-*.jar application.jar \
    && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM eclipse-temurin:21.0.12_8-jre-noble@sha256:96975602e131485862eb8cd32927face8a06d7591a5e865944b634a701d9df72 AS runtime
ENV PLAYWRIGHT_BROWSERS_PATH=/opt/playwright \
    PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 \
    HOME=/tmp
# Use the application's resolved Playwright Java CLI: pom.xml is the single version source.
# The mount does not retain a second copy of the application libraries in this layer.
RUN --mount=type=bind,from=build,source=/build/extracted/dependencies/lib,target=/tmp/playwright-lib \
    java -cp '/tmp/playwright-lib/*' com.microsoft.playwright.CLI install --with-deps chromium \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* /tmp/playwright-java-* \
    && chmod -R a+rX,go-w /opt/playwright \
    && groupadd --gid 10001 app \
    && useradd --uid 10001 --gid app --home-dir /tmp --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./
USER 10001:10001
EXPOSE 8080
STOPSIGNAL SIGTERM
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD ["curl", "--fail", "--silent", "--show-error", "--max-time", "4", "--output", "/dev/null", "http://127.0.0.1:8080/actuator/health/liveness"]
ENTRYPOINT ["java", "-jar", "application.jar"]

# Optional test target: same runtime, with only a compiled offline smoke helper added.
FROM build AS smoke-classes
COPY scripts/container/ChromiumSmoke.java /smoke/ChromiumSmoke.java
RUN javac -cp 'extracted/dependencies/lib/*' -d /smoke/classes /smoke/ChromiumSmoke.java

FROM runtime AS browser-smoke
COPY --from=smoke-classes /smoke/classes/ /smoke/
ENTRYPOINT ["java", "-cp", "/smoke:/app/lib/*", "ChromiumSmoke"]

# Default final image contains no smoke helper, compiler, Maven, or test sources.
FROM runtime AS production
