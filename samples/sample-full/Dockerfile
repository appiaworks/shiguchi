# Deterministic image for the sample-full gateway.
# Built in CI from the bootJar produced by `gradlew -p samples sample-full:bootJar`.
# (Buildpacks/bootBuildImage are avoided here — the rolling Paketo builders have no
# stable JVM buildpack group; a sample app does not need them.)

# Unpack the boot jar into Spring Boot's layers: dependencies change far less often than the
# application, so a new release only ships the small top layers. Running unpacked also skips the
# nested-jar loader.
FROM eclipse-temurin:25-jre AS extract
WORKDIR /build
# The CI build copies the single boot jar into the build context as app.jar.
COPY app.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=extract /build/extracted/dependencies/ ./
COPY --from=extract /build/extracted/spring-boot-loader/ ./
COPY --from=extract /build/extracted/snapshot-dependencies/ ./
COPY --from=extract /build/extracted/application/ ./

# AOT cache (JDK 25, JEP 483/514/515): a training run records the loaded and linked classes and
# method profiles, and startup then maps them in instead of redoing that work. Measured on this
# app, startup to a refreshed context drops from ~3.3 s to ~1.75 s. spring.context.exit=onRefresh
# stops the training run once the context is up, before the servers bind.
#
# The JVM only uses the cache when a few settings match the training run; otherwise it logs
# "Unable to use AOT cache" and starts normally. They are: compact object headers (on in both),
# compressed oops (on for heaps under 32 GB, so the training heap is pinned small and the build
# host's RAM cannot turn them off), and a collector that supports them, so G1, Parallel or
# Serial. ZGC does not use compressed oops and runs without the cache.
RUN java -XX:+UseCompactObjectHeaders -XX:+UseG1GC -Xmx512m \
        -XX:AOTCacheOutput=app.aot -Dspring.context.exit=onRefresh -jar app.jar

# Compact headers go in JAVA_TOOL_OPTIONS rather than the entrypoint so they stay overridable:
# the Helm chart replaces this variable with its jvm.* values, which keep the flag by default.
ENV JAVA_TOOL_OPTIONS="-XX:+UseCompactObjectHeaders"
EXPOSE 8080
ENTRYPOINT ["java", "-XX:AOTCache=app.aot", "-jar", "app.jar"]
