<!-- SPDX-License-Identifier: Apache-2.0 -->
# Runtime error contracts (M11-E/#82)

Part of the [compatibility matrix](../compatibility.md); the register entry is `runtime-error-contracts` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented for runtime layers and adapters: closed domain/service cases, associated encoder error families and retained nested causes; generic bridges require explicit `DomainError` intent

## Evidence

`DomainErrorContractSuite`, `DomainDecodeContractSuite`, `DomainProtocolContractSuite`, `DomainBoundaryContractSuite`, `DomainNetworkContractSuite`: 15 positive/negative compiler and cause-identity controls; malformed-input, binary-reader and existing wire/snapshot goldens; integration evidence below

## Explicitly unchecked or unconfirmed

lexical declared-channel guard cannot establish error semantics or resolve every Scala type construct; Scalus callback ABI and compiler/AST follow-ups remain [M11-E](../design/domain-errors.md#review-and-remaining-work); fiber ownership remains #180
