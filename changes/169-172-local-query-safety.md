### Fixed (nested local queries and hard-fork constructors, #169, #172)
- LocalStateQuery decodes, plans, renders and answers nested `GetCBOR` queries without growing the
  worker's call stack. Answers emit their wrapping headers in one pass instead of repeatedly copying
  the inner bytes. A 256 KiB worker exercises 20,000 layers, including an acquired session's answer
  and refusal diagnostic; the existing transport and semantic byte/item limits still apply.
- `QueryAnytime GetEraStart` accepts exactly `[0]`; hard-fork interpreter and current-era queries
  accept exactly `[0]` and `[1]`. Wrong tags and extra or missing constructor fields close the
  connection as on the pinned Haskell node. Query decode failures are a closed error ADT.
