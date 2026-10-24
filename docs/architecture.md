# Architecture

**Status: Current.** Module boundaries and runtime responsibilities; historical measurements
and removed implementations are recorded separately in [performance.md](performance.md).

How the node is put together. For flags and scenarios see [running.md](running.md); for what is
verified against what see [compatibility.md](compatibility.md); for facts learned from real data
see [findings.md](findings.md).

## Pages

Each component has a page of its own; the section names below are the original headings.

| Page | Sections |
|---|---|
| [Modules, layers, algebras and the error model](architecture/modules.md) | Modules; Layers; Algebras; Error model; Data flow |
| [Configuration and topology files](architecture/configuration.md) | Configuration and topology files (`--config`, `--topology`) |
| [Era history, ledger rules and Plutus evaluation](architecture/ledger.md) | Era history and Byron; Ledger: Shelley to Babbage; Conway; Plutus evaluation |
| [Persistence](architecture/persistence.md) | Persistence (`follow --db <dir>`) |
| [Relay](architecture/relay.md) | Relay (`follow --listen`, `--local-socket`) |
| [Connections and outbound peer selection](architecture/connections.md) | Connections: the manager and the inbound governor (G2); Peer selection: the outbound peers (G1.1) |
| [Header-first validation and the header clock](architecture/header-validation.md) | Header-first validation (G6); Header clock: headers from the future (AUD-01, #153) |
| [Node-to-client](architecture/node-to-client.md) | Node-to-client (`--local-socket`) |
| [Block production](architecture/block-production.md) | Block production (`follow --forge`) |
| [Scale and scheduling](architecture/scale-and-scheduling.md) | Scale: the UTxO set on disk and a pipelined sync; Scheduling: CPU-bound work off the timer path (D8) |
