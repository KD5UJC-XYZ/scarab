# Security policy

## Disclaimer

Cardano Scala Lab is **unofficial research software**. It is not audited, not affiliated with or
endorsed by Input Output Global, Intersect, the Cardano Foundation or Emurgo, and it is **not for
mainnet funds or production stake pools**. It can forge blocks (`--forge`) and loads KES, VRF and
operational-certificate files; do not point it at keys that protect real value. It has not been
reviewed for side channels, denial of service or consensus safety against adversaries (see the
"not checked" list in [docs/compatibility.md](docs/compatibility.md)).

## Supported versions

Only the `main` branch (and the latest tagged research preview) receives fixes.

## Scope

In scope, and welcome as reports:

- consensus or ledger divergence from the reference Haskell node (accepting what it rejects or the
  reverse), because it can split the node from the network: report with era, slot or block hash,
  transaction id if any, expected and actual result, and the source of truth;
- crashes, hangs or unbounded resource use triggered by network input, files or CBOR;
- mistakes in key handling (key material written, logged or exposed);
- vulnerabilities in the vendored or adapted cryptographic code (`core/`).

Out of scope: vulnerabilities in third-party dependencies that are already public (report them
upstream), and issues that require running the node as a mainnet block producer, which is
unsupported.

## Reporting

Please report privately through GitHub: on the repository, **Security -> Report a vulnerability**
(a private security advisory). Do not open a public issue for an exploitable problem. You will get
an acknowledgement as soon as a maintainer reads it; this is a research project run by a small
team, so there is no response-time guarantee. A divergence that does not endanger anyone (for
example a rejected valid transaction found on a testnet) can be reported with the public "bug"
issue template.

## Keys and secrets

- Never commit signing keys (`*.skey`, `*.key`, KES, VRF, cold or payment keys) or node sockets;
  `.gitignore` covers common patterns but is not a safeguard.
- The devnet generates throwaway keys inside its run directory (`${CSL_DATA_DIR:-/tmp/cardano-scala-lab}`);
  the scenarios mount key files read-only with mode 0400. Fixtures contain public chain data or
  throwaway sandbox keys only.
- If you committed a secret by mistake, treat it as compromised, tell a maintainer privately and
  rotate it before anything else.
