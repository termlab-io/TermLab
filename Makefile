# Conch Terminal Workstation — Build & Run
#
# Usage:
#   make conch       — build and run Conch
#   make conch-build — build only (no run)
#   make conch-clean — clean build artifacts
#   make idea        — build and run IntelliJ IDEA CE (for reference)

BAZEL := bash bazel.cmd
# Open the user's home directory as the project root — allows the
# Project View to follow any terminal CWD under ~
CONCH_WORKSPACE := $(HOME)

.PHONY: conch conch-build conch-clean idea

# Build and run Conch
conch:
	$(BAZEL) run //conch:conch_run -- $(CONCH_WORKSPACE)

# Build Conch without running
conch-build:
	$(BAZEL) build //conch:conch_run

# Clean build artifacts
conch-clean:
	$(BAZEL) clean

# Build and run IntelliJ IDEA CE (for comparison/reference)
idea:
	$(BAZEL) run :main_run --
