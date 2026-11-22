### Added (era types, #85)

- Conway's BBODY on the era types, as cardano-ledger f649f975 writes it: the header's protocol version where the build checks it, the block's reference scripts, the body's size and hash, LEDGERS, the block's execution units and the block counted for its issuer, with Conway's block and Praos header in the era's types. On the 53 recorded Conway blocks of the era-walking captures and on crafted refusals of each of its checks it gives the flat BBODY's verdict, failures and state; the node still applies Conway blocks with the flat BBODY, so nothing changes in behaviour.
