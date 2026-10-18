// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.legacy

/** Invalid lengths of the frozen snapshot envelope; the byte layout is unchanged. */
enum SnapshotFramingError:
  case SidecarLength(declared: Int, fileBytes: Int, minimum: Int)
