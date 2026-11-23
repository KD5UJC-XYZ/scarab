### Changed (era types, #85)

- Conway's block rules on the era types refuse a block on the failures they recorded when a later transaction meets a defect of the node's own, such as a stored output the era cannot read or a script the decoder cannot judge, instead of leaving the block with no verdict; the defect is kept beside the refusal for the node to log, and a defect with no failure before it still leaves no verdict. The parallel evaluation of a block's scripts comes to the same result. The node still applies Conway blocks with the existing rules, so nothing changes in behaviour.
