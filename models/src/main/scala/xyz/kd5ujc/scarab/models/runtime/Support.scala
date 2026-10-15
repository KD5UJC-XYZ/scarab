// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.models.runtime

import xyz.kd5ujc.scarab.codec.Bytes as CborBytes

/** The empty byte string (the `raw` of a value that has not been encoded yet). */
val EmptyBytes: Bytes = CborBytes.empty

/** An absent optional member. */
val noValue: Decoded[Option[Nothing]] = Right(None)
