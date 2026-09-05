# the launcher clears CHROMIUM_FLAGS before reading this file. isolation is provided by the
# workspace container; chromium cannot create its own sandbox with these capabilities.
export CHROMIUM_FLAGS="$CHROMIUM_FLAGS --no-sandbox --disable-gpu"
