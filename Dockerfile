FROM scratch

ARG APPLICATION_VERSION

LABEL org.opencontainers.image.title="atto-node" \
      org.opencontainers.image.description="Atto node built as a static GraalVM image" \
      org.opencontainers.image.url="https://atto.cash" \
      org.opencontainers.image.source="https://github.com/attocash/node" \
      org.opencontainers.image.version="${APPLICATION_VERSION}"

ENV APPLICATION_VERSION=${APPLICATION_VERSION}

COPY ./build/native/nativeCompile/node /app/node

WORKDIR /app

USER 65532:65532

EXPOSE 8080
EXPOSE 8081
EXPOSE 8082

HEALTHCHECK --interval=60s --timeout=5s --start-period=180s --retries=5 \
    CMD ["/app/node", "healthcheck"]

ENTRYPOINT ["/app/node"]
