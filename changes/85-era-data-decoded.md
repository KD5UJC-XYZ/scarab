### Changed (era types, #85)

- The era types' witness set holds each datum and redeemer's data decoded, with the bytes it arrived as, as the reference's `Data era`: the script contexts read it, so a redeemer or a witness datum no longer has the node's own `Undecodable` failure, and the redeemers are translated in one pass, as the reference does; an output's inline datum is still read from its bytes. The node still builds contexts with the existing code, so nothing changes in behaviour.
