### Changed (node, #599)

- A peer session's interface no longer carries what only the removed in-session block application
  used. The selection worker reads the bodies it applies from the block pool through an interface
  of its own. A session no longer stages, validates or reports bodies itself. The branches for a
  block pool without the selection worker are removed. The node's behaviour is unchanged: every
  block was already adopted by the worker (#578).
