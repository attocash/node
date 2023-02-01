FROM eclipse-temurin:17-alpine as jdk

RUN ls
RUN ls ./atto-node/build/libs/
RUN ls ./atto-node/build
RUN ls ./atto-node
COPY ./atto-node/build/libs/atto-node.jar /app.jar

RUN jar -xvf app.jar
RUN jlink --add-modules $(jdeps --recursive --multi-release 17 --ignore-missing-deps --print-module-deps -cp 'BOOT-INF/lib/*' app.jar) --output /java

FROM alpine
ENV JAVA_HOME=/java
ENV PATH "${JAVA_HOME}/bin:${PATH}"

RUN adduser -D atto
USER atto

COPY --from=jdk /java /java
COPY --from=jdk /app.jar /app.jar

ENTRYPOINT ["java","-XX:+UseZGC","-jar","/app.jar"]