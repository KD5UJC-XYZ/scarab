### Changed (operational-certificate errors, #82)

- Certificate validation reports specific input, counter, start-period, witness, verifier-contract
  and defect cases. Header validation and forging retain typed causes; key mismatches and rejected
  signatures have separate cases. Reloads report malformed certificates or verifier failures as
  `CertificateFailure` instead of `BadCertificateSignature`, keeping the active credentials and
  erasing a refused candidate key. Signed bytes, acceptance rules and stored formats are unchanged.
