PYTHON ?= python3
SEED := tools/seed/generate_seed.py
TRANSLATIONS := tools/translations/check.py
ISSUE_REFS := tools/issue-refs/check.py

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
APP_ID := app.titan.gym.debug
SCANNER_PACKAGE := com.google.android.apps.accessibility.auditor
SCANNER_SERVICE := $(SCANNER_PACKAGE)/$(SCANNER_PACKAGE).ScannerService
# TalkBack ships under two names: Google's, from the Play Store's Accessibility Suite, and the
# rebrand One UI preloads on Samsung phones. `a11y-talkback` switches on whichever is installed,
# Google's first when both are.
TALKBACK_SERVICES := com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService \
	com.samsung.android.accessibility.talkback/com.samsung.android.marvin.talkback.TalkBackService
# The system font scale `a11y-font-scale` sets: the 2.0 the pass walks every screen at.
SCALE ?= 2.0

.DEFAULT_GOAL := help
.PHONY: help seed seed-update seed-rehash seed-check seed-test seed-deps seed-clean translations-check issue-refs-check \
	release-gate-check workflows-check test journeys screenshots screenshots-verify lint \
	demo-data store-screenshots \
	benchmark baseline-profile a11y-scan a11y-talkback a11y-font-scale a11y-reset a11y-record

help: ## List the available targets
	@grep -hE '^[a-z0-9-]+:.*?## ' $(MAKEFILE_LIST) \
		| awk -F':.*?## ' '{printf "  \033[36m%-12s\033[0m %s\n", $$1, $$2}'

seed: ## Regenerate the bundled exercise catalogue from the pinned upstream commit
	$(PYTHON) $(SEED)

seed-update: ## Re-pin source.lock to upstream HEAD, then regenerate
	$(PYTHON) $(SEED) --update

seed-rehash: ## Pin the tarball sha256 of the commit in source.lock (after a Renovate bump), then regenerate
	$(PYTHON) $(SEED) --rehash

seed-check: ## Fail if the committed seed assets are stale (run in CI)
	$(PYTHON) $(SEED) --check

seed-test: ## Run the seed generator's own tests (run in CI)
	$(PYTHON) -m unittest discover -s tools/seed -p 'test_*.py'

# The images are byte-identical only with the encoder they were made with, so this installs
# exactly that Pillow, hash-checked, into whichever Python runs the targets above.
seed-deps: ## Install the hash-pinned Pillow the seed images are encoded with
	$(PYTHON) -m pip install --require-hashes --only-binary :all: --no-deps -r tools/seed/requirements.txt

seed-clean: ## Drop the cached upstream tarballs
	rm -rf tools/seed/.cache

# Reports rather than fails, so a base string added in a PR does not block that PR on its
# translation (CI runs it the same way). `STRICT=1` is for claiming a locale is complete.
translations-check: ## Report every key missing from, or stray in, each module's translations
	$(PYTHON) $(TRANSLATIONS) $(if $(STRICT),--strict) $(if $(LOCALE),--locale $(LOCALE))

# Asks GitHub, so it needs GITHUB_TOKEN or a logged-in `gh`. `PR=231` also counts the issues
# that pull request closes, to check a change for what it made untrue before it merges.
issue-refs-check: ## Fail on prose that still waits on a closed issue ("until #30 lands")
	$(PYTHON) $(ISSUE_REFS) $(if $(PR),--pr $(PR))

# Builds a throwaway git repository under $TMPDIR and runs .github/scripts/release-gate.sh on
# every case in it; CI runs it too. Needs bash and git, nothing else.
release-gate-check: ## Check the release workflow's gate against tags on and off main
	bash .github/scripts/release-gate-test.sh

# Not in CI, which has no workflow linter. `go run` fetches and builds the pinned actionlint;
# it runs shellcheck on every `run:` block too when shellcheck is on the PATH.
ACTIONLINT_VERSION := v1.7.12
workflows-check: ## Lint the GitHub Actions workflows with actionlint (needs Go)
	go run github.com/rhysd/actionlint/cmd/actionlint@$(ACTIONLINT_VERSION)

test: ## Run the JVM unit tests and verify the screenshot references
	./gradlew test verifyRoborazziDebug

journeys: ## Run only the end-to-end journey suite (#29)
	./gradlew :app:testDebugUnitTest --tests "com.titan.app.ui.EndToEndJourneyTest"

screenshots: ## Re-record the screenshot references after an intended visual change
	./gradlew recordRoborazziDebug -Proborazzi.cleanupOldScreenshots=true

screenshots-verify: ## Compare the rendered UI against the committed references
	./gradlew verifyRoborazziDebug

# The store listing's generated half (#46) — see tools/demo/README.md. `--rerun` because the
# outputs live outside the build directory, where Gradle cannot see them go stale.
STORE_ASSETS := ./gradlew :app:testDebugUnitTest -Ptitan.storeAssets.record=true --rerun

demo-data: ## Regenerate the demo exports under tools/demo/ the store screenshots are taken from
	$(STORE_ASSETS) --tests "com.titan.app.store.DemoDatasetTest"

store-screenshots: ## Re-capture the Play listing's phone screenshots in all four locales
	$(STORE_ASSETS) --tests "com.titan.app.store.StoreScreenshotTest"

lint: ## Run detekt and the Android linter
	./gradlew detekt lint

benchmark: ## Run the on-device benchmarks (#42) - needs a physical device on adb
	./gradlew :benchmark:connectedBenchmarkAndroidTest

baseline-profile: ## Regenerate the committed Baseline Profile from a physical device
	./gradlew :benchmark:connectedBenchmarkAndroidTest \
		-Pandroid.testInstrumentationRunnerArguments.class=com.titan.benchmark.BaselineProfileGenerator
	@mkdir -p $(BASELINE_PROFILE_DIR)
	@found=$$(find $(BENCHMARK_OUTPUT) -name '*-baseline-prof.txt' -print -quit); \
		test -n "$$found" || { echo "No profile in $(BENCHMARK_OUTPUT) - did the run reach a device?"; exit 1; }; \
		cp "$$found" $(BASELINE_PROFILE_DIR)/baseline-prof.txt; \
		echo "Wrote $(BASELINE_PROFILE_DIR)/baseline-prof.txt from $$found - commit it."

# ─── The accessibility pass, on a phone ─────────────────────────────────────────────────────

a11y-scan: ## Install the debug app, switch Accessibility Scanner on and open the app (needs a device on adb)
	@./gradlew :app:installDebug || { \
		echo "Install refused. A $(APP_ID) signed by another machine's debug key? 'adb uninstall $(APP_ID)' and run again."; \
		exit 1; \
	}
	@if ! $(ADB) shell pm list packages $(SCANNER_PACKAGE) | grep -q $(SCANNER_PACKAGE); then \
		echo "Accessibility Scanner is not installed - opening its Play Store page on the device."; \
		$(ADB) shell am start -a android.intent.action.VIEW -d "market://details?id=$(SCANNER_PACKAGE)" >/dev/null; \
		exit 1; \
	fi
	@$(call enable-service,$(SCANNER_SERVICE))
	$(ADB) shell monkey -p $(APP_ID) -c android.intent.category.LAUNCHER 1 >/dev/null
	@echo "Scanner is on: tap its floating button on each of the twenty screens in docs/ACCESSIBILITY.md."

a11y-talkback: ## Switch TalkBack on for the linear-navigation walk (a11y-reset turns it off again)
	@for service in $(TALKBACK_SERVICES); do \
		if $(ADB) shell pm list packages "$${service%%/*}" | grep -q "$${service%%/*}"; then \
			$(call enable-service,$$service); \
			echo "TalkBack is on ($${service%%/*}). Swipe right to move, double-tap to activate; 'make a11y-reset' turns it off."; \
			exit 0; \
		fi; \
	done; \
	echo "No TalkBack on the device - install the Android Accessibility Suite from the Play Store."; exit 1

a11y-font-scale: ## Set the system font scale (SCALE=2.0 by default) for the large-text walk
	$(ADB) shell settings put system font_scale $(SCALE)

a11y-reset: ## Switch every accessibility service off and put the font scale back to 1.0
	$(ADB) shell settings put secure enabled_accessibility_services '""'
	$(ADB) shell settings put secure accessibility_enabled 0
	$(ADB) shell settings put system font_scale 1.0

# The four rows adb cannot read are passed as variables, each one optional:
#   make a11y-record SCANNER_ERRORS="0 on all twenty" TALKBACK_WALK="..." FONT_SCALE="..." DEVIATIONS="none"
a11y-record: ## Write the device rows of the pass record in docs/ACCESSIBILITY.md from the phone on adb
	$(PYTHON) tools/a11y/record.py --adb $(ADB) \
		$(if $(SCANNER_ERRORS),--scanner-errors "$(SCANNER_ERRORS)") \
		$(if $(TALKBACK_WALK),--talkback-walk "$(TALKBACK_WALK)") \
		$(if $(FONT_SCALE),--font-scale "$(FONT_SCALE)") \
		$(if $(DEVIATIONS),--deviations "$(DEVIATIONS)")

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
