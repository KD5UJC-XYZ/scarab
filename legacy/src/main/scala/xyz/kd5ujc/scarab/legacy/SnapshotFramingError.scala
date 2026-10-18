// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.legacy

import xyz.kd5ujc.scarab.codec.DomainError

/** Invalid lengths of the frozen snapshot envelope; the byte layout is unchanged. */
enum SnapshotFramingError extends DomainError:
  case SidecarLength(declared: Int, fileBytes: Int, minimum: Int)
