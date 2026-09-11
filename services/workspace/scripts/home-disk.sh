#!/bin/bash
set -euo pipefail
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
umask 077

# this trusted helper receives only a backing volume and loop devices, never a user's mounted home.
# the backing image is never visible inside a workspace, so commands cannot resize or corrupt it directly.
exec 9>/storage/lock
flock -x 9
image=/storage/home.ext4

case "${1:-}" in
  prepare)
    size="${2:?missing size}"
    reserve="${3:?missing reserve}"
    [[ "$size" =~ ^[1-9][0-9]*$ && "$reserve" =~ ^[1-9][0-9]*$ ]] || exit 1
    if [[ ! -e "$image" ]]; then
      trap 'rm -f /storage/home.new' EXIT
      rm -f /storage/home.new
      read -r available block_size < <(stat -f -c '%a %S' /storage)
      (( available * block_size >= size * 1024 * 1024 + reserve * 1024 * 1024 )) || {
        echo 'not enough space to reserve a workspace disk and the host reserve' >&2
        exit 1
      }
      # reserve real blocks before formatting; discard would silently turn the image sparse again.
      touch /storage/home.new
      if [[ "$(stat -f -c %T /storage)" == btrfs ]]; then
        chattr +C /storage/home.new
      fi
      fallocate -l "${size}M" /storage/home.new
      mkfs.ext4 -q -F -m 0 -E nodiscard,lazy_itable_init=0,lazy_journal_init=0,root_owner=1000:1000 /storage/home.new
      debugfs -w -R 'rmdir lost+found' /storage/home.new >&2
      chmod 600 /storage/home.new
      mv /storage/home.new "$image"
    fi
    [[ -f "$image" && ! -L "$image" ]] || exit 1
    [[ "$(stat -c %s "$image")" == "$(( size * 1024 * 1024 ))" ]] || {
      echo 'existing workspace disk size differs from WORKSPACE_MAX_HOME_MB; resize it offline first' >&2
      exit 1
    }
    # --nooverlap makes recovery reuse this image's attachment after an interrupted startup.
    losetup --find --show --nooverlap "$image"
    ;;
  release)
    if [[ -f "$image" && ! -L "$image" ]]; then
      while read -r device; do
        [[ "$device" =~ ^/dev/loop[0-9]+$ ]] || exit 1
        losetup --detach "$device"
      done < <(losetup --associated "$image" --noheadings --output NAME)
    fi
    ;;
  *) exit 1 ;;
esac
