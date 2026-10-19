### Changed

- Spread reward calculation across block ticks using the reference node’s immutable reward pulser. Imported and restarted pending reward updates now resume from their captured inputs. Reward amounts, exact arithmetic and existing snapshot bytes are preserved; sparse chains still complete outstanding work synchronously at the reference deadline.
