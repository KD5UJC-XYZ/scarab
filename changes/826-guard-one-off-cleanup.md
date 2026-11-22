### Removed (storage format guard, #826)

- `scripts/check_storage_format.py` no longer holds the one-off exceptions of #1127, #1173, #1244
  and #1313. Each admitted the paths of one storage format change for that pull request only, and
  all four have merged; a follow-up pull request removes an exception once its pull request
  merges (FMT-1). No persisted byte changes.
