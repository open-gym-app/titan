PYTHON ?= python3
SEED := tools/seed/generate_seed.py

.DEFAULT_GOAL := help
.PHONY: help seed seed-update seed-check seed-clean test lint

help: ## List the available targets
	@grep -hE '^[a-z-]+:.*?## ' $(MAKEFILE_LIST) \
		| awk -F':.*?## ' '{printf "  \033[36m%-12s\033[0m %s\n", $$1, $$2}'

seed: ## Regenerate the bundled exercise catalogue from the pinned upstream commit
	$(PYTHON) $(SEED)

seed-update: ## Re-pin source.lock to upstream HEAD, then regenerate
	$(PYTHON) $(SEED) --update

seed-check: ## Fail if the committed seed assets are stale (run in CI)
	$(PYTHON) $(SEED) --check

seed-clean: ## Drop the cached upstream tarballs
	rm -rf tools/seed/.cache

test: ## Run the JVM unit tests
	./gradlew test testDebugUnitTest

lint: ## Run detekt and the Android linter
	./gradlew detekt lint
