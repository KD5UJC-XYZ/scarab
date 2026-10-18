### Changed (semantic error contracts, #189)

- Retain typed script-evaluation failures until transaction-rejection encoding, use closed Byron failure sites and forge skip reasons, and preserve typed connection endings through server and connection state.
- Replace model-generator domain exceptions and message-based classifications with functional typed failures, bounded diagnostics and explicit sampler-cycle refusals. Public wire and persisted formats, generated codecs, metric keys and log fields remain unchanged.
