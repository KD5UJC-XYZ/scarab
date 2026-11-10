### Fixed (KES forging periods, #474)

- Keep KES evolution targets at full width, so a configured certificate window longer than the
  signing lifetime cannot wrap the target into an earlier period. Exhaustion stops forging with
  the exact typed cause, including during credential reload; configured certificate expiry keeps
  its existing recoverable outcome. Forging events use the successfully evolved key's period.
