### Fixed (node-to-client era index, #720)

- A node-to-client query of an era index past the last era of the reference's `CardanoEras`, or past
  a `Word8`, is refused and its connection closed, as the reference refuses it, in `QueryIfCurrent`
  and in `QueryAnytime`; it used to be answered with an era mismatch naming an "Unknown" era.
- A query of era index 0 is read by Byron's own query decoder, a bare tag 0 for
  `GetUpdateInterfaceState`, and answered with the era mismatch past Byron; it used to be read as a
  Shelley query. While the ledger is in Byron it is not answered yet (#726).
- A query of Dijkstra (era index 7) reads its arguments at Dijkstra's version 12, as the reference
  does (a chunked byte string, a `GovActionId` as a record of its own), and is answered with the era
  mismatch naming Dijkstra.
