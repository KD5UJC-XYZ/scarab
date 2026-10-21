### Fixed

- Capture local-root trust at warm-to-hot selection and carry it into the ChainSync deadline
  policy. Ordinary, big-ledger and inbound activations remain untrustable; fixed timeout profiles
  retain their configured deadlines.
