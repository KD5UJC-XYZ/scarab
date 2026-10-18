// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.blst;

/** Byte-array JNI entry points added to the portable blst-java library (see native/README.md). */
public final class RawDst {
    private RawDst() {}

    static {
        // Initialise blst-java's resource loader before resolving our additional entry points.
        new supranational.blst.P1();
    }

    public static native byte[] hashG1(byte[] message, byte[] dst);
    public static native byte[] hashG2(byte[] message, byte[] dst);
}
