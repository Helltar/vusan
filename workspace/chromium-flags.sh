# The one flag Chromium needs in here that Debian's own /etc/chromium.d files cannot decide for
# us, put where the launcher actually reads it: /usr/bin/chromium clears CHROMIUM_FLAGS itself,
# so the environment cannot carry it.
#
# Chromium's sandbox wants privileges this container does not have, and it is not what isolates
# anything here — the container is, and under WORKSPACE_RUNTIME=runsc so is gVisor. There is no
# GPU either. `--disable-dev-shm-usage` is deliberately absent: Debian's own `dev-shm` file
# already adds it whenever /dev/shm is too small, which in a container it always is.
export CHROMIUM_FLAGS="$CHROMIUM_FLAGS --no-sandbox --disable-gpu"
