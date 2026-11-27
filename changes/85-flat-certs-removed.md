### Removed (era types, #85)
- **The flat Conway CERTS, CERT, DELEG and GOVCERT are gone.** Since the flat LEDGER went, only tests reached them
  and the glue that put the era certificate rules' failures in their flat form (`ConwayCertsPredFailure.toStored` and
  the `toStored` of each rule below it). `ConwayCertsChecksSuite`, which compared the era CERTS with the flat one,
  holds the era CERTS to the flat one's judgments, recorded before it went. Two differences from the reference went
  with it: under the 11.2 and Leios builds below protocol version 11, the flat CERTS refused a withdrawal that does
  not drain its account in CERTS, where the reference refuses it in LEDGER; and it summed DReps' expiries as integers
  where the reference wraps them in `Word64`. The flat POOL stays, which the eras before Conway run, and with it its
  unbounded retirement limit past epoch 2^64. What the node admits, refuses and sends is unchanged.
