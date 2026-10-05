.PHONY: up down logs ps db-shell psql shell-backend shell-ai shell-frontend \
        test test-backend test-frontend test-ai clean help

# Vantis single-node deploy — the short, safe commands. Everything else lives
# in the README. The deploy defaults to "prod" profile; switch to "dev" in
# the .env file (set SPRING_PROFILES_ACTIVE= or APP_ENV=development).

COMPOSE = docker compose
ENV_FILE = .env

help: ## Show this help
	@awk 'BEGIN {FS = ":.*?## "} /^[a-zA-Z_-]+:.*?## / {printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)

up: ## Build images and start the full stack in detached mode
	@test -f $(ENV_FILE) || { echo "No .env found. Run: cp .env.example .env && set JWT_SECRET, ENCRYPTION_KEY, POSTGRES_PASSWORD"; exit 1; }
	$(COMPOSE) --env-file $(ENV_FILE) up -d --build
	@echo "Waiting for backend readiness..."
	@for i in 1 2 3 4 5 6 7 8 9 10 11 12; do \
		if curl -fsS http://localhost:8080/health/ready >/dev/null 2>&1; then \
			echo "Backend is ready."; break; \
		fi; \
		sleep 5; \
	done

down: ## Stop and remove containers (keeps the postgres volume)
	$(COMPOSE) down

down-v: ## Stop and remove containers AND the postgres volume (DESTROYS DATA)
	$(COMPOSE) down -v

logs: ## Tail logs from every service
	$(COMPOSE) logs -f --tail=200

ps: ## List running Vantis containers
	$(COMPOSE) ps

psql: ## Open a psql shell to the postgres container
	$(COMPOSE) exec postgres psql -U $${POSTGRES_USER:-flowops} -d $${POSTGRES_DB:-flowops}

shell-backend: ## Open a shell in the running backend container
	$(COMPOSE) exec backend sh

shell-ai: ## Open a shell in the running AI service container
	$(COMPOSE) exec ai-service sh

shell-frontend: ## Open a shell in the running frontend container
	$(COMPOSE) exec frontend sh

test: test-backend test-frontend test-ai ## Run the full test suite

test-backend: ## Run the Spring Boot test suite (Java 21, mvn)
	cd backend && mvn -B test

test-frontend: ## Typecheck and lint the Next.js app
	cd frontend && npm run typecheck && npm run lint

test-ai: ## Run the pytest suite
	cd ai-service && . .venv/bin/activate && pytest -q

clean: ## Remove build artifacts (frontend .next, backend target, python caches)
	cd backend && mvn -q clean
	rm -rf frontend/.next frontend/node_modules/.cache
	find ai-service -type d -name __pycache__ -prune -exec rm -rf {} +
