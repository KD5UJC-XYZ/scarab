// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.Bytes

/** ChainSync receive limits and unrestricted encoding over shared CBOR framing. */
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
      bound = if limits.local then ProtocolWire.Bound.Local else ProtocolWire.Bound.Network,
      unboundedBytes = limits.messageByteLimit.isEmpty,
      deferredLengths = true
    )
  private[protocol] def encoding(limits: ChainSync.Limits)(using
      ProtocolWire.Scope
  ): ProtocolWire.Limits =
    if !limits.valid then ChainSync.bad(ProtocolError.InvalidLocalLimits)
    checked(ChainSync.Limits.NodeToClient)

  final class Reader(input: Bytes, limits: ChainSync.Limits)(using ProtocolWire.Scope)
      extends ProtocolWire.Reader(input, checked(limits))
  final class Writer(limits: ChainSync.Limits)(using ProtocolWire.Scope)
      extends ProtocolWire.Writer(encoding(limits))
