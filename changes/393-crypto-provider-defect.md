### Fixed (crypto verification, #393)

- A crypto provider that fails or breaks its contract (Ed25519, VRF, KES, certificate signature) is now a fatal defect of the session (exit 7, no peer blame) and no longer a rejected signature, proof or header. Invalid signatures, proofs and certificates are still rejected as before.
