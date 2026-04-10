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
# Override INTELLIJ_ROOT if your intellij-community checkout is somewhere
# unexpected (default: parent of this Makefile, i.e. intellij-community/).

INTELLIJ_ROOT ?= $(realpath $(dir $(lastword $(MAKEFILE_LIST))))/..
BAZEL          := cd $(INTELLIJ_ROOT) && bash bazel.cmd

# Open the user's home directory as the project root — lets the Project View
# follow any terminal CWD under ~.
CONCH_WORKSPACE := $(HOME)

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
