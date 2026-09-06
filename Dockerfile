# Umbra — сервер мессенджера
# Сборка в два этапа: кэширование зависимостей -> бинарник.

FROM golang:1.27 AS build
WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -ldflags="-s -w" -o /umbra-server ./cmd/server
RUN mkdir -p /data/blobs && chmod 0700 /data/blobs

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /umbra-server /umbra-server
COPY --from=build --chown=65532:65532 /data /data
ENV BLOB_DIR=/data/blobs
EXPOSE 8080
USER nonroot:nonroot
ENTRYPOINT ["/umbra-server"]
