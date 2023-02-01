FROM eclipse-temurin:17-alpine as jdk

COPY atto-node/build/libs/atto-node.jar /app.jar

RUN jdeps --ignore-missing-deps -q --print-module-deps /app.jar > /module-deps.info
RUN jlink --add-modules $(cat /module-deps.info) --output /java

FROM alpine
ENV JAVA_HOME=/java
ENV PATH "${JAVA_HOME}/bin:${PATH}"

COPY --from=jdk /java /java
COPY --from=jdk /app.jar /app.jar

ENTRYPOINT ["java","-XX:+UseZGC","-jar","/app.jar"]