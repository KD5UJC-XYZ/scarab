### Fixed (Leios votes, #1355)

- A Leios vote whose announcing block has left the vote state's window of 128 blocks is no longer
  counted again as that block's first and relayed: the vote state remembers the last 4096 blocks
  to leave the window, and such a vote is `Retired`, refused before its signature is checked. On the
  public Leios testnet on 2026-11-23, votes for 222 blocks cycled between the reference and Scarab
  without end and kept every endorser-block announcement from a restarted node. An Improvement on
  the reference, which keeps no such record.
