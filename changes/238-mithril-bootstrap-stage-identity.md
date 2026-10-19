### Fixed (certified bootstrap staging, #238)

- Initialize the fresh private database stage's network identity before creating bootstrap work
  files, so strict database preflight allows the authenticated immutable and ledger imports without
  weakening missing-identity refusal for existing databases.
