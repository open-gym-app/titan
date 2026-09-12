PYTHON ?= python3
SEED := tools/seed/generate_seed.py

# Where Macrobenchmark leaves what it pulled off the device, and where a Baseline Profile has
# to land for AGP to package it — `src/main/baselineProfiles` is the source directory AGP reads
# for an application module.
BENCHMARK_OUTPUT := benchmark/build/outputs/connected_android_test_additional_output
BASELINE_PROFILE_DIR := app/src/main/baselineProfiles

.DEFAULT_GOAL := help
.PHONY: help seed seed-update seed-check seed-clean test journeys screenshots screenshots-verify lint benchmark baseline-profile

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

test: ## Run the JVM unit tests and verify the screenshot references
	./gradlew test verifyRoborazziDebug

journeys: ## Run only the end-to-end journey suite (#29)
	./gradlew :app:testDebugUnitTest --tests "com.opengym.app.ui.EndToEndJourneyTest"

screenshots: ## Re-record the screenshot references after an intended visual change
	./gradlew recordRoborazziDebug -Proborazzi.cleanupOldScreenshots=true

screenshots-verify: ## Compare the rendered UI against the committed references
	./gradlew verifyRoborazziDebug

lint: ## Run detekt and the Android linter
	./gradlew detekt lint

benchmark: ## Run the on-device benchmarks (#42) - needs a physical device on adb
	./gradlew :benchmark:connectedBenchmarkAndroidTest

baseline-profile: ## Regenerate the committed Baseline Profile from a physical device
	./gradlew :benchmark:connectedBenchmarkAndroidTest \
		-Pandroid.testInstrumentationRunnerArguments.class=com.opengym.benchmark.BaselineProfileGenerator
	@mkdir -p $(BASELINE_PROFILE_DIR)
	@found=$$(find $(BENCHMARK_OUTPUT) -name '*-baseline-prof.txt' -print -quit); \
		test -n "$$found" || { echo "No profile in $(BENCHMARK_OUTPUT) - did the run reach a device?"; exit 1; }; \
		cp "$$found" $(BASELINE_PROFILE_DIR)/baseline-prof.txt; \
		echo "Wrote $(BASELINE_PROFILE_DIR)/baseline-prof.txt from $$found - commit it."
