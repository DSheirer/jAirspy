# Patches to libairspy

The build scripts apply every `*.patch` here, in name order, to the upstream
airspyone_host source before building it, for the pinned version and for the
nightly build of `master`. A patch that no longer applies fails the build, so
remove a patch once upstream includes the fix.

| Patch | Problem |
|-------|---------|
| `0001-drain-cancelled-transfers.patch` | `airspy_stop_rx` cancels the in-flight USB transfers but handles libusb events only once, without waiting, so some cancellations are still pending when `airspy_close` frees the transfers. `libusb_close` then reads freed memory. On Windows this crashed about one in ten closes after streaming. The patch drains events twice for up to 50 ms each, as hydrasdr-host does. |

To make or update a patch, change a clean checkout of the pinned commit and run
`git diff > native/patches/<name>.patch`. Patches must keep LF line endings.
