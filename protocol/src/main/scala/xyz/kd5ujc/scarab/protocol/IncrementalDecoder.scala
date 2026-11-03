// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.Bytes
import ChainSync.DecodeResult

/** A prefix decoder whose cursor remembers work on an incomplete message. Each input is the
  * complete prefix accumulated so far, extending the preceding input without changing its bytes.
  * Starting again with [[initial]] must give the same verdict; the cursor changes only the work
  * needed to reach it. A caller discards the cursor after a complete message or failure.
  */
trait IncrementalDecoder[A]:
  type Cursor
  def initial: Cursor
  def resume(cursor: Cursor, input: Bytes): (Cursor, DecodeResult[A])
  final def decodePrefix(input: Bytes): DecodeResult[A] = resume(initial, input)._2
