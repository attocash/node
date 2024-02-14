FROM scratch

COPY ./build/native/nativeCompile/node /app/node

WORKDIR /app

EXPOSE 8080
EXPOSE 8081
EXPOSE 8082

ENTRYPOINT ["./node"]