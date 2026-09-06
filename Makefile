.PHONY: run build test lint docker-up docker-down

run:          ## запустить сервер локально (in-memory)
	go run ./cmd/server

build:        ## собрать бинарник
	CGO_ENABLED=0 go build -ldflags="-s -w" -o bin/server ./cmd/server

test:         ## прогнать тесты
	go test ./...

lint:         ## статический анализ
	go vet ./...

docker-up:    ## поднять сервер + PostgreSQL
	docker compose up --build

docker-down:
	docker compose down

.PHONY: help
help:
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-12s\033[0m %s\n", $$1, $$2}'
