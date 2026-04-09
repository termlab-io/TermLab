# Conch Terminal Workstation — Build & Run
#
# Usage:
#   make conch       — build and run Conch
#   make conch-build — build only (no run)
#   make conch-clean — clean build artifacts
#   make idea        — build and run IntelliJ IDEA CE (for reference)

BAZEL := bash bazel.cmd
CONCH_WORKSPACE := $(HOME)/.config/conch

.PHONY: conch conch-build conch-clean idea

# Build and run Conch — opens ~/.config/conch as the project to skip welcome screen
conch: $(CONCH_WORKSPACE)
	$(BAZEL) run //conch:conch_run -- $(CONCH_WORKSPACE)

# Ensure the workspace directory exists
$(CONCH_WORKSPACE):
	mkdir -p $(CONCH_WORKSPACE)

# Build Conch without running
conch-build:
	$(BAZEL) build //conch:conch_run

# Clean build artifacts
conch-clean:
	$(BAZEL) clean

# Build and run IntelliJ IDEA CE (for comparison/reference)
idea:
	$(BAZEL) run :main_run --
