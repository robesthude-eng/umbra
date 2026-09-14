.PHONY: run build test lint test-race gc-dry-run docker-up docker-down

run:          ## запустить сервер локально (in-memory)
	go run ./cmd/server

build:        ## собрать бинарник
	CGO_ENABLED=0 go build -ldflags="-s -w" -o bin/server ./cmd/server
	CGO_ENABLED=0 go build -ldflags="-s -w" -o bin/umbra-storage ./cmd/storage

test:         ## прогнать тесты
	go test ./...

lint:         ## статический анализ
	go vet ./...

test-race:    ## регрессионные проверки с детектором гонок
	go test -race -count=1 ./...

gc-dry-run:   ## показать сироты старше суток; нужны STORE=postgres и DATABASE_URL
	go run ./cmd/gc

docker-up:    ## поднять сервер + PostgreSQL
	docker compose up --build

docker-down:
	docker compose down

.PHONY: help
help:
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-12s\033[0m %s\n", $$1, $$2}'

.PHONY: storage-init storage-migrate
storage-init: ## создать серверные ключи один раз для локального запуска
	mkdir -p data
	go run ./cmd/storage init

storage-migrate: ## перенести старые данные; остановите сервер, см. CLOUD_STORAGE.md
	go run ./cmd/storage migrate
