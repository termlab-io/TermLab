# Conch Terminal Workstation — Build & Run
#
# This Makefile lives at the root of conch_workbench. After running setup.sh,
# the workbench is symlinked into intellij-community/conch, so running `make`
# from the workbench dir delegates Bazel commands up to the intellij root.
#
# Usage:
#   make conch                    — build and run Conch
#   make conch-build              — build only (no run)
#   make conch-clean              — clean build artifacts
#   make conch-installers         — build all-platform distributions
#   make conch-installers-mac     — build macOS distributions only (.sit)
#   make conch-installers-linux   — build Linux distributions only (.tar.gz)
#   make conch-installers-windows — build Windows distributions only (.win.zip)
#
# Installer artifacts land in: $(INTELLIJ_ROOT)/out/conch/artifacts/
#
# INTELLIJ_ROOT resolution order:
#   1. Environment variable / `make INTELLIJ_ROOT=...`
#   2. .intellij-root file (written by setup.sh, contains an absolute path)
#   3. Sibling 'intellij-community' directory (zero-config fallback)
#
# .intellij-root is gitignored — it's local config, not source.

WORKBENCH_DIR  := $(realpath $(dir $(lastword $(MAKEFILE_LIST))))
INTELLIJ_ROOT_FILE := $(WORKBENCH_DIR)/.intellij-root

ifeq ($(origin INTELLIJ_ROOT), undefined)
  ifneq ($(wildcard $(INTELLIJ_ROOT_FILE)),)
    INTELLIJ_ROOT := $(strip $(shell cat $(INTELLIJ_ROOT_FILE)))
  else
    INTELLIJ_ROOT := $(WORKBENCH_DIR)/../intellij-community
  endif
endif

BAZEL := cd $(INTELLIJ_ROOT) && bash bazel.cmd

# Default workspace root for Conch. Override with:
#   make conch CONCH_WORKSPACE=/path/to/workspace
CONCH_WORKSPACE ?= $(HOME)

# Perf harness defaults. Override as needed, for example:
#   make conch-perf-benchmark CONCH_PERF_WARMUP_SEC=120 CONCH_PERF_DURATION_SEC=600
CONCH_PERF_OUT ?= $(WORKBENCH_DIR)/perf-results
CONCH_PERF_WORKSPACE ?= $(WORKBENCH_DIR)/.perf-workspace
CONCH_PERF_WARMUP_SEC ?= 90
CONCH_PERF_SAMPLE_SEC ?= 5
CONCH_PERF_DURATION_SEC ?= 300

.PHONY: conch conch-build conch-clean conch-installers conch-installers-mac conch-installers-linux conch-installers-windows check-intellij conch-perf-benchmark conch-perf-budget

check-intellij:
	@test -f "$(INTELLIJ_ROOT)/bazel.cmd" || { \
		echo "ERROR: bazel.cmd not found at $(INTELLIJ_ROOT)."; \
		echo "       Run ./setup.sh first, or set INTELLIJ_ROOT explicitly."; \
		exit 1; \
	}

conch: check-intellij
	$(BAZEL) run //conch:conch_run -- $(CONCH_WORKSPACE)

conch-build: check-intellij
	$(BAZEL) build //conch:conch_run

conch-clean: check-intellij
	$(BAZEL) clean

conch-installers: check-intellij
	$(BAZEL) run //conch/build:conch_installers
	@echo "→ Installer artifacts in $(INTELLIJ_ROOT)/out/conch/artifacts/"

conch-installers-mac: check-intellij
	CONCH_TARGET_OS=mac $(BAZEL) run //conch/build:conch_installers
	@echo "→ Installer artifacts in $(INTELLIJ_ROOT)/out/conch/artifacts/"

conch-installers-linux: check-intellij
	CONCH_TARGET_OS=linux $(BAZEL) run //conch/build:conch_installers
	@echo "→ Installer artifacts in $(INTELLIJ_ROOT)/out/conch/artifacts/"

conch-installers-windows: check-intellij
	CONCH_TARGET_OS=windows $(BAZEL) run //conch/build:conch_installers
	@echo "→ Installer artifacts in $(INTELLIJ_ROOT)/out/conch/artifacts/"

conch-perf-benchmark: check-intellij
	mkdir -p "$(CONCH_PERF_WORKSPACE)"
	bash $(WORKBENCH_DIR)/scripts/perf/benchmark_idle.sh \
		--intellij-root "$(INTELLIJ_ROOT)" \
		--workspace "$(CONCH_PERF_WORKSPACE)" \
		--warmup-sec "$(CONCH_PERF_WARMUP_SEC)" \
		--sample-sec "$(CONCH_PERF_SAMPLE_SEC)" \
		--duration-sec "$(CONCH_PERF_DURATION_SEC)" \
		--out "$(CONCH_PERF_OUT)"

conch-perf-budget:
	bash $(WORKBENCH_DIR)/scripts/perf/check_perf_budget.sh \
		--summary "$(CONCH_PERF_OUT)/latest_summary.env"
