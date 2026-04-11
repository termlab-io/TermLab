# Conch Terminal Workstation — Build & Run
#
# This Makefile lives at the root of conch_workbench. After running setup.sh,
# the workbench is symlinked into intellij-community/conch, so running `make`
# from the workbench dir delegates Bazel commands up to the intellij root.
#
# Usage:
#   make conch       — build and run Conch
#   make conch-build — build only (no run)
#   make conch-clean — clean build artifacts
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

.PHONY: conch conch-build conch-clean check-intellij

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
