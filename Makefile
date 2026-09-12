PYTHON ?= python3
SEED := tools/seed/generate_seed.py

# Where Macrobenchmark leaves what it pulled off the device, and where a Baseline Profile has
# to land for AGP to package it — `src/main/baselineProfiles` is the source directory AGP reads
# for an application module.
BENCHMARK_OUTPUT := benchmark/build/outputs/connected_android_test_additional_output
BASELINE_PROFILE_DIR := app/src/main/baselineProfiles

# The device half of the accessibility pass (#40) — see docs/ACCESSIBILITY.md. Accessibility
# Scanner and TalkBack are accessibility *services*, which adb can switch on through the secure
# settings the way the Settings app does; both have to be installed first, and neither is by
# default on a clean device — `a11y-scan` opens the Play Store page when the Scanner is missing.
ADB ?= adb
APP_ID := com.opengym.app.debug
SCANNER_PACKAGE := com.google.android.apps.accessibility.auditor
SCANNER_SERVICE := $(SCANNER_PACKAGE)/$(SCANNER_PACKAGE).ScannerService
TALKBACK_SERVICE := com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService
# The system font scale `a11y-font-scale` sets: the 2.0 the pass walks every screen at.
SCALE ?= 2.0

.DEFAULT_GOAL := help
.PHONY: help seed seed-update seed-check seed-clean test journeys screenshots screenshots-verify lint benchmark baseline-profile \
	a11y-scan a11y-talkback a11y-font-scale a11y-reset

help: ## List the available targets
	@grep -hE '^[a-z0-9-]+:.*?## ' $(MAKEFILE_LIST) \
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

# ─── The accessibility pass, on a phone ─────────────────────────────────────────────────────

a11y-scan: ## Install the debug app, switch Accessibility Scanner on and open the app (needs a device on adb)
	./gradlew :app:installDebug
	@if ! $(ADB) shell pm list packages $(SCANNER_PACKAGE) | grep -q $(SCANNER_PACKAGE); then \
		echo "Accessibility Scanner is not installed - opening its Play Store page on the device."; \
		$(ADB) shell am start -a android.intent.action.VIEW -d "market://details?id=$(SCANNER_PACKAGE)" >/dev/null; \
		exit 1; \
	fi
	@$(call enable-service,$(SCANNER_SERVICE))
	$(ADB) shell monkey -p $(APP_ID) -c android.intent.category.LAUNCHER 1 >/dev/null
	@echo "Scanner is on: tap its floating button on each of the twenty screens in docs/ACCESSIBILITY.md."

a11y-talkback: ## Switch TalkBack on for the linear-navigation walk (a11y-reset turns it off again)
	@$(call enable-service,$(TALKBACK_SERVICE))
	@echo "TalkBack is on. Swipe right to move, double-tap to activate; 'make a11y-reset' turns it off."

a11y-font-scale: ## Set the system font scale (SCALE=2.0 by default) for the large-text walk
	$(ADB) shell settings put system font_scale $(SCALE)

a11y-reset: ## Switch every accessibility service off and put the font scale back to 1.0
	$(ADB) shell settings put secure enabled_accessibility_services '""'
	$(ADB) shell settings put secure accessibility_enabled 0
	$(ADB) shell settings put system font_scale 1.0

# Adds one service to the colon-separated list Android keeps, rather than replacing the list:
# switching the Scanner on must not switch TalkBack off, or the two halves of the pass cannot
# be run together. `accessibility_enabled` is the master switch the list is read behind.
define enable-service
	current=$$($(ADB) shell settings get secure enabled_accessibility_services | tr -d '\r'); \
	case "$$current" in \
		*$(1)*) ;; \
		null|"") $(ADB) shell settings put secure enabled_accessibility_services "$(1)" ;; \
		*) $(ADB) shell settings put secure enabled_accessibility_services "$$current:$(1)" ;; \
	esac; \
	$(ADB) shell settings put secure accessibility_enabled 1
endef
