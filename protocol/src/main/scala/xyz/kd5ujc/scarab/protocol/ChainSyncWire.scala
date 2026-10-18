// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.Bytes

/** ChainSync policy boundary over shared framing. Its 65535-byte policy is unchanged. */
private[protocol] object ChainSyncWire:
  private def checked(limits: ChainSync.Limits)(using ProtocolWire.Scope): ProtocolWire.Limits =
    if !limits.valid then ChainSync.bad(ProtocolError.InvalidLocalLimits)
    ProtocolWire.Limits(
      limits.maxMessageBytes,
      limits.maxStringBytes,
      limits.maxHashBytes,
      limits.maxDepth,
      limits.maxItems,
      limits.maxCandidates
    )
  final class Reader(input: Bytes, limits: ChainSync.Limits)(using ProtocolWire.Scope)
      extends ProtocolWire.Reader(input, checked(limits))
  final class Writer(limits: ChainSync.Limits)(using ProtocolWire.Scope)
      extends ProtocolWire.Writer(checked(limits))
