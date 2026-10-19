### Fixed (LocalStateQuery state consistency, #158)

- Queries for a retained Conway state read UTxO entries from a resource-scoped snapshot captured at the same chain point; re-acquire and release close the prior view.
