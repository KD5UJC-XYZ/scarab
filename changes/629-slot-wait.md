### Fixed (forging, #629)

- The forger's slot loop no longer logs `systemClockMovedBack` warnings while the clock runs normally. Its wait for the next slot was rounded down to the millisecond, so most waits ended just before the slot began, read the same slot again and logged the clock as moved back (thousands of warnings per hour with 0.1 s slots). The wait is now rounded down to the microsecond, as the reference's `threadDelay` rounds it. Which slots are checked for leadership, and when, is unchanged; a clock moved back is still logged, and still stops the node beyond 20 s.
