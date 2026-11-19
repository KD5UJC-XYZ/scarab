// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.Bytes

/** ChainSync policy boundary over shared framing. Its 65535-byte policy is unchanged. */
private[protocol] object ChainSyncWire:
  private[protocol] def checked(limits: ChainSync.Limits)(using
      ProtocolWire.Scope
  ): ProtocolWire.Limits =
    if !limits.valid then ChainSync.bad(ProtocolError.InvalidLocalLimits)
    ProtocolWire.Limits(
      limits.maxMessageBytes,
      limits.maxStringBytes,
      limits.maxHashBytes,
      limits.maxDepth,
      limits.maxItems,
      limits.maxCandidates
    )
  def reader(
      input: Bytes,
      limits: ChainSync.Limits,
      from: ProtocolWire.Progress = ProtocolWire.Progress.none
  )(using ProtocolWire.Scope): ProtocolWire.Reader =
    ProtocolWire.Reader(input, checked(limits), from)
  final class Writer(limits: ChainSync.Limits)(using ProtocolWire.Scope)
      extends ProtocolWire.Writer(checked(limits))
