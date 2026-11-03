### Fixed (a header's VRF output, #712)

- A header whose VRF output is not 64 bytes long is decoded, as the reference decodes it, and is
  refused by the VRF check of header validation, as the reference refuses it: a Praos header as a
  rejected VRF proof, a TPraos header as a rejected nonce or leader certificate. Before, it was
  refused at decoding.
- Chain selection's VRF tie-break orders outputs of different lengths as the reference does,
  shorter first.
