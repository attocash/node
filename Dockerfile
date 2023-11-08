FROM eclipse-temurin:21-alpine as jdk

COPY ./build/libs/node.jar /node.jar

RUN jar -xvf node.jar && jlink --add-modules $(jdeps --recursive --multi-release 21 --ignore-missing-deps --print-module-deps -cp 'BOOT-INF/lib/*' node.jar) --output /java

FROM alpine

LABEL org.opencontainers.image.source https://github.com/attocash/node

ENV JAVA_HOME=/java
ENV PATH "${JAVA_HOME}/bin:${PATH}"

RUN adduser -D atto
USER atto

COPY ./build/libs/node.jar /home/atto/node.jar

COPY --from=jdk /java /java

ENTRYPOINT ["java","-XX:+UseZGC","-jar","/home/atto/node.jar"]