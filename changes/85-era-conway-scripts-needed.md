### Added (era types, #85)

- The scripts a Conway transaction needs and provides are found on the era types (`EraUTxO`'s `getScriptsNeeded` and `getScriptsProvided`), and the era-typed missing-scripts check reads them. The scripts needed equal the existing path's on every recorded Conway refusal and transaction, in order and with each script purpose's index, and the scripts provided on every recorded refusal; the node still judges with the existing rule, so nothing changes in behaviour.
