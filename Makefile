# TermLab — Build & Run
#
# This Makefile lives at the root of termlab_workbench. After running setup.sh,
# the workbench is symlinked into intellij-community/termlab, so running `make`
# from the workbench dir delegates Bazel commands up to the intellij root.
#
# Usage:
#   make termlab                    — build and run TermLab
#   make termlab-build              — build only (no run)
#   make termlab-clean              — clean build artifacts
#   make termlab-installers         — build all-platform distributions
#   make termlab-installers-mac     — build macOS distributions only (.sit)
#   make termlab-installers-linux   — build Linux distributions only (.tar.gz)
#   make termlab-installers-windows — build Windows distributions only (.win.zip, .exe)
#   make termlab-version            — regenerate TermLabApplicationInfo.xml
#
# Product version: pass VERSION=X.Y.Z on any build/run target to override and
# persist the version in customization/version.properties, e.g.:
#   VERSION=0.1.0 make termlab
#   VERSION=0.2.0 make termlab-installers
# The generated TermLabApplicationInfo.xml is a build artifact (gitignored);
# the template lives at customization/TermLabApplicationInfo.xml.tmpl.
#
# Installer artifacts land in: $(INTELLIJ_ROOT)/out/termlab/artifacts/
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

# Default workspace root for TermLab. Override with:
#   make termlab TERMLAB_WORKSPACE=/path/to/workspace
TERMLAB_WORKSPACE ?= $(HOME)

# Perf harness defaults. Override as needed, for example:
#   make termlab-perf-benchmark TERMLAB_PERF_WARMUP_SEC=120 TERMLAB_PERF_DURATION_SEC=600
TERMLAB_PERF_OUT ?= $(WORKBENCH_DIR)/perf-results
TERMLAB_PERF_WORKSPACE ?= $(WORKBENCH_DIR)/.perf-workspace
TERMLAB_PERF_WARMUP_SEC ?= 90
TERMLAB_PERF_SAMPLE_SEC ?= 5
TERMLAB_PERF_DURATION_SEC ?= 300

.PHONY: termlab termlab-build termlab-clean termlab-installers termlab-installers-mac termlab-installers-linux termlab-installers-windows check-intellij termlab-version termlab-perf-benchmark termlab-perf-budget

check-intellij:
	@test -f "$(INTELLIJ_ROOT)/bazel.cmd" || { \
		echo "ERROR: bazel.cmd not found at $(INTELLIJ_ROOT)."; \
		echo "       Run ./setup.sh first, or set INTELLIJ_ROOT explicitly."; \
		exit 1; \
	}

# Stamp version attributes into customization/resources/idea/TermLabApplicationInfo.xml
# in place. Only <version major/minor/patch suffix> are touched; other
# edits in that file (motto, logos, themes, plugins, ...) are preserved.
# Pass VERSION=X.Y.Z to override (and persist) the product version:
#   make termlab-version VERSION=0.2.0
# Runs automatically before every termlab build/run target below.
termlab-version:
	python3 $(WORKBENCH_DIR)/scripts/generate_version.py

termlab: check-intellij termlab-version
	$(BAZEL) run //termlab:termlab_run -- $(TERMLAB_WORKSPACE)

termlab-build: check-intellij termlab-version
	$(BAZEL) build //termlab:termlab_run

termlab-clean: check-intellij
	$(BAZEL) clean

termlab-installers: check-intellij termlab-version
	$(BAZEL) run //termlab/build:termlab_installers
	@echo "→ Installer artifacts in $(INTELLIJ_ROOT)/out/termlab/artifacts/"

termlab-installers-mac: check-intellij termlab-version
	TERMLAB_TARGET_OS=mac $(BAZEL) run //termlab/build:termlab_installers
	@echo "→ Installer artifacts in $(INTELLIJ_ROOT)/out/termlab/artifacts/"

termlab-installers-linux: check-intellij termlab-version
	TERMLAB_TARGET_OS=linux $(BAZEL) run //termlab/build:termlab_installers
	@echo "→ Installer artifacts in $(INTELLIJ_ROOT)/out/termlab/artifacts/"

termlab-installers-windows: check-intellij termlab-version
	TERMLAB_TARGET_OS=windows $(BAZEL) run //termlab/build:termlab_installers
	@echo "→ Installer artifacts in $(INTELLIJ_ROOT)/out/termlab/artifacts/"

termlab-perf-benchmark: check-intellij
	mkdir -p "$(TERMLAB_PERF_WORKSPACE)"
	bash $(WORKBENCH_DIR)/scripts/perf/benchmark_idle.sh \
		--intellij-root "$(INTELLIJ_ROOT)" \
		--workspace "$(TERMLAB_PERF_WORKSPACE)" \
		--warmup-sec "$(TERMLAB_PERF_WARMUP_SEC)" \
		--sample-sec "$(TERMLAB_PERF_SAMPLE_SEC)" \
		--duration-sec "$(TERMLAB_PERF_DURATION_SEC)" \
		--out "$(TERMLAB_PERF_OUT)"

termlab-perf-budget:
	bash $(WORKBENCH_DIR)/scripts/perf/check_perf_budget.sh \
		--summary "$(TERMLAB_PERF_OUT)/latest_summary.env"
