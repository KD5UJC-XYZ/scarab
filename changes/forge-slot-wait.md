### Fixed
- The forger waits the whole time left to the next slot, kept to the nanosecond (the reference's `threadDelay` keeps
  it to the microsecond). It cut the wait to whole milliseconds, so it often woke just before the slot boundary, read
  the same slot again and logged `systemClockMovedBack` (a Warning) although the clock had not moved; the slot then
  started about a millisecond late. On a 0.1 s devnet that was about once per slot.
