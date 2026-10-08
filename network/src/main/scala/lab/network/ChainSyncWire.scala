// SPDX-License-Identifier: Apache-2.0
package lab.network

import lab.cbor.Bytes

/** ChainSync policy boundary over shared framing. Its 65535-byte policy is unchanged. */
private[network] object ChainSyncWire:
  private def checked(limits: ChainSync.Limits): ProtocolWire.Limits =
    if !limits.valid then ChainSync.bad("invalid local limits")
    ProtocolWire.Limits(
      limits.maxMessageBytes,
      limits.maxStringBytes,
      limits.maxHashBytes,
      limits.maxDepth,
      limits.maxItems,
      limits.maxCandidates
    )
  final class Reader(input: Bytes, limits: ChainSync.Limits)
      extends ProtocolWire.Reader(input, checked(limits))
  final class Writer(limits: ChainSync.Limits) extends ProtocolWire.Writer(checked(limits))
