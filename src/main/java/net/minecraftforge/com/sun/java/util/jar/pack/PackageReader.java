/*
 * Copyright (c) 2001, 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package net.minecraftforge.com.sun.java.util.jar.pack;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.PrintStream;
import java.io.FilterInputStream;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;

/**
 * Reader for a package file.
 *
 * @see PackageWriter
 * @author John Rose
 */
class PackageReader extends BandStructure {
    Package pkg;
    byte[] bytes;
    LimitedBuffer in;
    Package.Version packageVersion;

    PackageReader(Package pkg, InputStream in) throws IOException {
        this.pkg = pkg;
        this.in = new LimitedBuffer(in);
    }

    /** A buffered input stream which is careful not to
     *  read its underlying stream ahead of a given mark,
     *  called the 'readLimit'.  This property declares
     *  the maximum number of characters that future reads
     *  can consume from the underlying stream.
     */
    static
    class LimitedBuffer extends BufferedInputStream {
        long served;     // total number of charburgers served
        int  servedPos;  // ...as of this value of super.pos
        long limit;      // current declared limit
        long buffered;
        public boolean atLimit() {
            boolean z = (getBytesServed() == limit);
            assert(!z || limit == buffered);
            return z;
        }
        public long getBytesServed() {
            return served + (pos - servedPos);
        }
        public void setReadLimit(long newLimit) {
            if (newLimit == -1)
                limit = -1;
            else
                limit = getBytesServed() + newLimit;
        }
        public long getReadLimit() {
            if (limit == -1)
                return limit;
            else
                return limit - getBytesServed();
        }
        public int read() throws IOException {
            if (pos < count) {
                // fast path
                return buf[pos++] & 0xFF;
            }
            served += (pos - servedPos);
            int ch = super.read();
            servedPos = pos;
            if (ch >= 0)  served += 1;
            assert(served <= limit || limit == -1);
            return ch;
        }
        public int read(byte b[], int off, int len) throws IOException {
            served += (pos - servedPos);
            int nr = super.read(b, off, len);
            servedPos = pos;
            if (nr >= 0)  served += nr;
            //assert(served <= limit || limit == -1);
            return nr;
        }
        public long skip(long n) throws IOException {
            throw new RuntimeException("no skipping");
        }
        LimitedBuffer(InputStream originalIn) {
            super(null, 1<<14);
            servedPos = pos;
            super.in = new FilterInputStream(originalIn) {
                public int read() throws IOException {
                    if (buffered == limit)
                        return -1;
                    ++buffered;
                    return super.read();
                }
                public int read(byte b[], int off, int len) throws IOException {
                    if (buffered == limit)
                        return -1;
                    if (limit != -1) {
                        long remaining = limit - buffered;
                        if (len > remaining)
                            len = (int)remaining;
                    }
                    int nr = super.read(b, off, len);
                    if (nr >= 0)  buffered += nr;
                    return nr;
                }
            };
        }
    }

    void read() throws IOException {
        boolean ok = false;
        try {
            //  pack200_archive:
            //        file_header
            //        *band_headers :BYTE1
            //        cp_bands
            //        attr_definition_bands
            //        ic_bands
            //        class_bands
            //        bc_bands
            //        file_bands
            readFileHeader();
            readBandHeaders();
            readConstantPool();  // cp_bands
            readAttrDefs();
            readInnerClasses();
            Package.Class[] classes = readClasses();
            readByteCodes();
            readFiles();     // file_bands
            assert(archiveSize1 == 0 || in.atLimit());
            assert(archiveSize1 == 0 ||
                   in.getBytesServed() == archiveSize0+archiveSize1);
            all_bands.doneDisbursing();

            // As a post-pass, build constant pools and inner classes.
            for (int i = 0; i < classes.length; i++) {
                reconstructClass(classes[i]);
            }

            ok = true;
        } catch (Exception ee) {
            Utils.log.warning("Error on input: "+ee, ee);
            if (verbose > 0)
                Utils.log.info("Stream offsets:"+
                                 " served="+in.getBytesServed()+
                                 " buffered="+in.buffered+
                                 " limit="+in.limit);
            //if (verbose > 0)  ee.printStackTrace();
            if (ee instanceof IOException)  throw (IOException)ee;
            if (ee instanceof RuntimeException)  throw (RuntimeException)ee;
            throw new Error("error unpacking", ee);
        }
    }

    // Temporary count values, until band decoding gets rolling.
    int[] tagCount = new int[Constants.CONSTANT_Limit];
    int numFiles;
    int numAttrDefs;
    int numInnerClasses;
    int numClasses;

    void readFileHeader() throws IOException {
        //  file_header:
        //        archive_magic archive_header
        readArchiveMagic();
        readArchiveHeader();
    }

    // Local routine used to parse fixed-format scalars
    // in the file_header:
    private int getMagicInt32() throws IOException {
        int res = 0;
        for (int i = 0; i < 4; i++) {
            res <<= 8;
            res |= (archive_magic.getByte() & 0xFF);
        }
        return res;
    }

    static final int MAGIC_BYTES = 4;

    void readArchiveMagic() throws IOException {
        // Read a minimum of bytes in the first gulp.
        in.setReadLimit(MAGIC_BYTES + AH_LENGTH_MIN);

        //  archive_magic:
        //        #archive_magic_word :BYTE1[4]
        archive_magic.expectLength(MAGIC_BYTES);
        archive_magic.readFrom(in);

        // read and check magic numbers:
        int magic = getMagicInt32();
        if (pkg.magic != magic) {
            throw new IOException("Unexpected package magic number: got "
                    + magic + "; expected " + pkg.magic);
        }
        archive_magic.doneDisbursing();
    }

     // Fixed 6211177, converted to throw IOException
    void checkArchiveVersion() throws IOException {
        Package.Version versionFound = null;
        for (Package.Version v : new Package.Version[] {
                Constants.JAVA8_PACKAGE_VERSION,
                Constants.JAVA7_PACKAGE_VERSION,
                Constants.JAVA6_PACKAGE_VERSION,
                Constants.JAVA5_PACKAGE_VERSION
            }) {
            if (packageVersion.equals(v)) {
                versionFound = v;
                break;
            }
        }
        if (versionFound == null) {
            String expVer =   Constants.JAVA8_PACKAGE_VERSION.toString()
                            + "OR"
                            + Constants.JAVA7_PACKAGE_VERSION.toString()
                            + " OR "
                            + Constants.JAVA6_PACKAGE_VERSION.toString()
                            + " OR "
                            + Constants.JAVA5_PACKAGE_VERSION.toString();
            throw new IOException("Unexpected package minor version: got "
                    +  packageVersion.toString() + "; expected " + expVer);
        }
    }

    void readArchiveHeader() throws IOException {
        //  archive_header:
        //        #archive_minver :UNSIGNED5[1]
        //        #archive_majver :UNSIGNED5[1]
        //        #archive_options :UNSIGNED5[1]
        //        (archive_file_counts) ** (#have_file_headers)
        //        (archive_special_counts) ** (#have_special_formats)
        //        cp_counts
        //        class_counts
        //
        //  archive_file_counts:
        //        #archive_size_hi :UNSIGNED5[1]
        //        #archive_size_lo :UNSIGNED5[1]
        //        #archive_next_count :UNSIGNED5[1]
        //        #archive_modtime :UNSIGNED5[1]
        //        #file_count :UNSIGNED5[1]
        //
        //  class_counts:
        //        #ic_count :UNSIGNED5[1]
        //        #default_class_minver :UNSIGNED5[1]
        //        #default_class_majver :UNSIGNED5[1]
        //        #class_count :UNSIGNED5[1]
        //
        //  archive_special_counts:
        //        #band_headers_size :UNSIGNED5[1]
        //        #attr_definition_count :UNSIGNED5[1]
        //
        archive_header_0.expectLength(AH_LENGTH_0);
        archive_header_0.readFrom(in);

        int minver = archive_header_0.getInt();
        int majver = archive_header_0.getInt();
        packageVersion = Package.Version.of(majver, minver);
        checkArchiveVersion();
        this.initHighestClassVersion(Constants.JAVA7_MAX_CLASS_VERSION);

        archiveOptions = archive_header_0.getInt();
        archive_header_0.doneDisbursing();

        // detect archive optional fields in archive header
        boolean haveSpecial = testBit(archiveOptions, Constants.AO_HAVE_SPECIAL_FORMATS);
        boolean haveFiles   = testBit(archiveOptions, Constants.AO_HAVE_FILE_HEADERS);
        boolean haveNumbers = testBit(archiveOptions, Constants.AO_HAVE_CP_NUMBERS);
        boolean haveCPExtra = testBit(archiveOptions, Constants.AO_HAVE_CP_EXTRAS);
        initAttrIndexLimit();

        // now we are ready to use the data:
        archive_header_S.expectLength(haveFiles? AH_LENGTH_S: 0);
        archive_header_S.readFrom(in);
        if (haveFiles) {
            long sizeHi = archive_header_S.getInt();
            long sizeLo = archive_header_S.getInt();
            archiveSize1 = (sizeHi << 32) + ((sizeLo << 32) >>> 32);
            // Set the limit, now, up to the file_bits.
            in.setReadLimit(archiveSize1);  // for debug only
        } else {
            archiveSize1 = 0;
            in.setReadLimit(-1);  // remove limitation
        }
        archive_header_S.doneDisbursing();
        archiveSize0 = in.getBytesServed();

        int remainingHeaders = AH_LENGTH_MIN - AH_LENGTH_0 - AH_LENGTH_S;
        if (haveFiles)    remainingHeaders += AH_FILE_HEADER_LEN;
        if (haveSpecial)  remainingHeaders += AH_SPECIAL_FORMAT_LEN;
        if (haveNumbers)  remainingHeaders += AH_CP_NUMBER_LEN;
        if (haveCPExtra)  remainingHeaders += AH_CP_EXTRA_LEN;
        archive_header_1.expectLength(remainingHeaders);
        archive_header_1.readFrom(in);

        if (haveFiles) {
            archiveNextCount = archive_header_1.getInt();
            pkg.default_modtime = archive_header_1.getInt();
            numFiles = archive_header_1.getInt();
        } else {
            archiveNextCount = 0;
            numFiles = 0;
        }

        if (haveSpecial) {
            band_headers.expectLength(archive_header_1.getInt());
            numAttrDefs = archive_header_1.getInt();
        } else {
            band_headers.expectLength(0);
            numAttrDefs = 0;
        }

        readConstantPoolCounts(haveNumbers, haveCPExtra);

        numInnerClasses = archive_header_1.getInt();

        minver = (short) archive_header_1.getInt();
        majver = (short) archive_header_1.getInt();
        pkg.defaultClassVersion = Package.Version.of(majver, minver);
        numClasses = archive_header_1.getInt();

        archive_header_1.doneDisbursing();

        // set some derived archive bits
        if (testBit(archiveOptions, Constants.AO_DEFLATE_HINT)) {
            pkg.default_options |= Constants.FO_DEFLATE_HINT;
        }
    }

    void readBandHeaders() throws IOException {
        band_headers.readFrom(in);
        bandHeaderBytePos = 1;  // Leave room to pushback the initial XB byte.
        bandHeaderBytes = new byte[bandHeaderBytePos + band_headers.length()];
        for (int i = bandHeaderBytePos; i < bandHeaderBytes.length; i++) {
            bandHeaderBytes[i] = (byte) band_headers.getByte();
        }
        band_headers.doneDisbursing();
    }

    void readConstantPoolCounts(boolean haveNumbers, boolean haveCPExtra) throws IOException {
        // size the constant pools:
        for (int k = 0; k < ConstantPool.TAGS_IN_ORDER.length; k++) {
            //  cp_counts:
            //        #cp_Utf8_count :UNSIGNED5[1]
            //        (cp_number_counts) ** (#have_cp_numbers)
            //        #cp_String_count :UNSIGNED5[1]
            //        #cp_Class_count :UNSIGNED5[1]
            //        #cp_Signature_count :UNSIGNED5[1]
            //        #cp_Descr_count :UNSIGNED5[1]
            //        #cp_Field_count :UNSIGNED5[1]
            //        #cp_Method_count :UNSIGNED5[1]
            //        #cp_Imethod_count :UNSIGNED5[1]
            //        (cp_attr_counts) ** (#have_cp_attr_counts)
            //
            //  cp_number_counts:
            //        #cp_Int_count :UNSIGNED5[1]
            //        #cp_Float_count :UNSIGNED5[1]
            //        #cp_Long_count :UNSIGNED5[1]
            //        #cp_Double_count :UNSIGNED5[1]
            //
            //  cp_extra_counts:
            //        #cp_MethodHandle_count :UNSIGNED5[1]
            //        #cp_MethodType_count :UNSIGNED5[1]
            //        #cp_InvokeDynamic_count :UNSIGNED5[1]
            //        #cp_BootstrapMethod_count :UNSIGNED5[1]
            //
            byte tag = ConstantPool.TAGS_IN_ORDER[k];
            if (!haveNumbers) {
                // These four counts are optional.
                switch (tag) {
                case Constants.CONSTANT_Integer:
                case Constants.CONSTANT_Float:
                case Constants.CONSTANT_Long:
                case Constants.CONSTANT_Double:
                    continue;
                }
            }
            if (!haveCPExtra) {
                // These four counts are optional.
                switch (tag) {
                case Constants.CONSTANT_MethodHandle:
                case Constants.CONSTANT_MethodType:
                case Constants.CONSTANT_InvokeDynamic:
                case Constants.CONSTANT_BootstrapMethod:
                    continue;
                }
            }
            tagCount[tag] = archive_header_1.getInt();
        }
    }

    protected ConstantPool.Index getCPIndex(byte tag) {
        return pkg.cp.getIndexByTag(tag);
    }
    ConstantPool.Index initCPIndex(byte tag, ConstantPool.Entry[] cpMap) {
        if (verbose > 3) {
            for (int i = 0; i < cpMap.length; i++) {
                Utils.log.fine("cp.add "+cpMap[i]);
            }
        }
        ConstantPool.Index index = ConstantPool.makeIndex(ConstantPool.tagName(tag), cpMap);
        if (verbose > 1)  Utils.log.fine("Read "+index);
        pkg.cp.initIndexByTag(tag, index);
        return index;
    }

    void checkLegacy(String bandname) {
        if (packageVersion.lessThan(Constants.JAVA7_PACKAGE_VERSION)) {
            throw new RuntimeException("unexpected band " + bandname);
        }
    }
    void readConstantPool() throws IOException {
        //  cp_bands:
        //        cp_Utf8
        //        *cp_Int :UDELTA5
        //        *cp_Float :UDELTA5
        //        cp_Long
        //        cp_Double
        //        *cp_String :UDELTA5  (cp_Utf8)
        //        *cp_Class :UDELTA5  (cp_Utf8)
        //        cp_Signature
        //        cp_Descr
        //        cp_Field
        //        cp_Method
        //        cp_Imethod

        if (verbose > 0)  Utils.log.info("Reading CP");

        for (int k = 0; k < ConstantPool.TAGS_IN_ORDER.length; k++) {
            byte tag = ConstantPool.TAGS_IN_ORDER[k];
            int  len = tagCount[tag];

            ConstantPool.Entry[] cpMap = new ConstantPool.Entry[len];
            if (verbose > 0)
                Utils.log.info("Reading "+cpMap.length+" "+ConstantPool.tagName(tag)+" entries...");

            switch (tag) {
            case Constants.CONSTANT_Utf8:
                readUtf8Bands(cpMap);
                break;
            case Constants.CONSTANT_Integer:
                cp_Int.expectLength(cpMap.length);
                cp_Int.readFrom(in);
                for (int i = 0; i < cpMap.length; i++) {
                    int x = cp_Int.getInt();  // coding handles signs OK
                    cpMap[i] = ConstantPool.getLiteralEntry(x);
                }
                cp_Int.doneDisbursing();
                break;
            case Constants.CONSTANT_Float:
                cp_Float.expectLength(cpMap.length);
                cp_Float.readFrom(in);
                for (int i = 0; i < cpMap.length; i++) {
                    int x = cp_Float.getInt();
                    float fx = Float.intBitsToFloat(x);
                    cpMap[i] = ConstantPool.getLiteralEntry(fx);
                }
                cp_Float.doneDisbursing();
                break;
            case Constants.CONSTANT_Long:
                //  cp_Long:
                //        *cp_Long_hi :UDELTA5
                //        *cp_Long_lo :DELTA5
                cp_Long_hi.expectLength(cpMap.length);
                cp_Long_hi.readFrom(in);
                cp_Long_lo.expectLength(cpMap.length);
                cp_Long_lo.readFrom(in);
                for (int i = 0; i < cpMap.length; i++) {
                    long hi = cp_Long_hi.getInt();
                    long lo = cp_Long_lo.getInt();
                    long x = (hi << 32) + ((lo << 32) >>> 32);
                    cpMap[i] = ConstantPool.getLiteralEntry(x);
                }
                cp_Long_hi.doneDisbursing();
                cp_Long_lo.doneDisbursing();
                break;
            case Constants.CONSTANT_Double:
                //  cp_Double:
                //        *cp_Double_hi :UDELTA5
                //        *cp_Double_lo :DELTA5
                cp_Double_hi.expectLength(cpMap.length);
                cp_Double_hi.readFrom(in);
                cp_Double_lo.expectLength(cpMap.length);
                cp_Double_lo.readFrom(in);
                for (int i = 0; i < cpMap.length; i++) {
                    long hi = cp_Double_hi.getInt();
                    long lo = cp_Double_lo.getInt();
                    long x = (hi << 32) + ((lo << 32) >>> 32);
                    double dx = Double.longBitsToDouble(x);
                    cpMap[i] = ConstantPool.getLiteralEntry(dx);
                }
                cp_Double_hi.doneDisbursing();
                cp_Double_lo.doneDisbursing();
                break;
            case Constants.CONSTANT_String:
                cp_String.expectLength(cpMap.length);
                cp_String.readFrom(in);
                cp_String.setIndex(getCPIndex(Constants.CONSTANT_Utf8));
                for (int i = 0; i < cpMap.length; i++) {
                    cpMap[i] = ConstantPool.getLiteralEntry(cp_String.getRef().stringValue());
                }
                cp_String.doneDisbursing();
                break;
            case Constants.CONSTANT_Class:
                cp_Class.expectLength(cpMap.length);
                cp_Class.readFrom(in);
                cp_Class.setIndex(getCPIndex(Constants.CONSTANT_Utf8));
                for (int i = 0; i < cpMap.length; i++) {
                    cpMap[i] = ConstantPool.getClassEntry(cp_Class.getRef().stringValue());
                }
                cp_Class.doneDisbursing();
                break;
            case Constants.CONSTANT_Signature:
                readSignatureBands(cpMap);
                break;
            case Constants.CONSTANT_NameandType:
                //  cp_Descr:
                //        *cp_Descr_type :DELTA5  (cp_Signature)
                //        *cp_Descr_name :UDELTA5  (cp_Utf8)
                cp_Descr_name.expectLength(cpMap.length);
                cp_Descr_name.readFrom(in);
                cp_Descr_name.setIndex(getCPIndex(Constants.CONSTANT_Utf8));
                cp_Descr_type.expectLength(cpMap.length);
                cp_Descr_type.readFrom(in);
                cp_Descr_type.setIndex(getCPIndex(Constants.CONSTANT_Signature));
                for (int i = 0; i < cpMap.length; i++) {
                    ConstantPool.Entry ref  = cp_Descr_name.getRef();
                    ConstantPool.Entry ref2 = cp_Descr_type.getRef();
                    cpMap[i] = ConstantPool.getDescriptorEntry((ConstantPool.Utf8Entry)ref,
                                                        (ConstantPool.SignatureEntry)ref2);
                }
                cp_Descr_name.doneDisbursing();
                cp_Descr_type.doneDisbursing();
                break;
            case Constants.CONSTANT_Fieldref:
                readMemberRefs(tag, cpMap, cp_Field_class, cp_Field_desc);
                break;
            case Constants.CONSTANT_Methodref:
                readMemberRefs(tag, cpMap, cp_Method_class, cp_Method_desc);
                break;
            case Constants.CONSTANT_InterfaceMethodref:
                readMemberRefs(tag, cpMap, cp_Imethod_class, cp_Imethod_desc);
                break;
            case Constants.CONSTANT_MethodHandle:
                if (cpMap.length > 0) {
                    checkLegacy(cp_MethodHandle_refkind.name());
                }
                cp_MethodHandle_refkind.expectLength(cpMap.length);
                cp_MethodHandle_refkind.readFrom(in);
                cp_MethodHandle_member.expectLength(cpMap.length);
                cp_MethodHandle_member.readFrom(in);
                cp_MethodHandle_member.setIndex(getCPIndex(Constants.CONSTANT_AnyMember));
                for (int i = 0; i < cpMap.length; i++) {
                    byte        refKind = (byte)        cp_MethodHandle_refkind.getInt();
                    ConstantPool.MemberEntry memRef  = (ConstantPool.MemberEntry) cp_MethodHandle_member.getRef();
                    cpMap[i] = ConstantPool.getMethodHandleEntry(refKind, memRef);
                }
                cp_MethodHandle_refkind.doneDisbursing();
                cp_MethodHandle_member.doneDisbursing();
                break;
            case Constants.CONSTANT_MethodType:
                if (cpMap.length > 0) {
                    checkLegacy(cp_MethodType.name());
                }
                cp_MethodType.expectLength(cpMap.length);
                cp_MethodType.readFrom(in);
                cp_MethodType.setIndex(getCPIndex(Constants.CONSTANT_Signature));
                for (int i = 0; i < cpMap.length; i++) {
                    ConstantPool.SignatureEntry typeRef  = (ConstantPool.SignatureEntry) cp_MethodType.getRef();
                    cpMap[i] = ConstantPool.getMethodTypeEntry(typeRef);
                }
                cp_MethodType.doneDisbursing();
                break;
            case Constants.CONSTANT_InvokeDynamic:
                if (cpMap.length > 0) {
                    checkLegacy(cp_InvokeDynamic_spec.name());
                }
                cp_InvokeDynamic_spec.expectLength(cpMap.length);
                cp_InvokeDynamic_spec.readFrom(in);
                cp_InvokeDynamic_spec.setIndex(getCPIndex(Constants.CONSTANT_BootstrapMethod));
                cp_InvokeDynamic_desc.expectLength(cpMap.length);
                cp_InvokeDynamic_desc.readFrom(in);
                cp_InvokeDynamic_desc.setIndex(getCPIndex(Constants.CONSTANT_NameandType));
                for (int i = 0; i < cpMap.length; i++) {
                    ConstantPool.BootstrapMethodEntry bss   = (ConstantPool.BootstrapMethodEntry) cp_InvokeDynamic_spec.getRef();
                    ConstantPool.DescriptorEntry descr = (ConstantPool.DescriptorEntry)      cp_InvokeDynamic_desc.getRef();
                    cpMap[i] = ConstantPool.getInvokeDynamicEntry(bss, descr);
                }
                cp_InvokeDynamic_spec.doneDisbursing();
                cp_InvokeDynamic_desc.doneDisbursing();
                break;
            case Constants.CONSTANT_BootstrapMethod:
                if (cpMap.length > 0) {
                    checkLegacy(cp_BootstrapMethod_ref.name());
                }
                cp_BootstrapMethod_ref.expectLength(cpMap.length);
                cp_BootstrapMethod_ref.readFrom(in);
                cp_BootstrapMethod_ref.setIndex(getCPIndex(Constants.CONSTANT_MethodHandle));
                cp_BootstrapMethod_arg_count.expectLength(cpMap.length);
                cp_BootstrapMethod_arg_count.readFrom(in);
                int totalArgCount = cp_BootstrapMethod_arg_count.getIntTotal();
                cp_BootstrapMethod_arg.expectLength(totalArgCount);
                cp_BootstrapMethod_arg.readFrom(in);
                cp_BootstrapMethod_arg.setIndex(getCPIndex(Constants.CONSTANT_LoadableValue));
                for (int i = 0; i < cpMap.length; i++) {
                    ConstantPool.MethodHandleEntry bsm = (ConstantPool.MethodHandleEntry) cp_BootstrapMethod_ref.getRef();
                    int argc = cp_BootstrapMethod_arg_count.getInt();
                    ConstantPool.Entry[] argRefs = new ConstantPool.Entry[argc];
                    for (int j = 0; j < argc; j++) {
                        argRefs[j] = cp_BootstrapMethod_arg.getRef();
                    }
                    cpMap[i] = ConstantPool.getBootstrapMethodEntry(bsm, argRefs);
                }
                cp_BootstrapMethod_ref.doneDisbursing();
                cp_BootstrapMethod_arg_count.doneDisbursing();
                cp_BootstrapMethod_arg.doneDisbursing();
                break;
            default:
                throw new AssertionError("unexpected CP tag in package");
            }

            ConstantPool.Index index = initCPIndex(tag, cpMap);

            if (optDumpBands) {
                try (PrintStream ps = new PrintStream(getDumpStream(index, ".idx"))) {
                    printArrayTo(ps, index.cpMap, 0, index.cpMap.length);
                }
            }
        }

        cp_bands.doneDisbursing();

        if (optDumpBands || verbose > 1) {
            for (byte tag = Constants.CONSTANT_GroupFirst; tag < Constants.CONSTANT_GroupLimit; tag++) {
                ConstantPool.Index index = pkg.cp.getIndexByTag(tag);
                if (index == null || index.isEmpty())  continue;
                ConstantPool.Entry[] cpMap = index.cpMap;
                if (verbose > 1)
                    Utils.log.info("Index group "+ConstantPool.tagName(tag)+" contains "+cpMap.length+" entries.");
                if (optDumpBands) {
                    try (PrintStream ps = new PrintStream(getDumpStream(index.debugName, tag, ".gidx", index))) {
                        printArrayTo(ps, cpMap, 0, cpMap.length, true);
                    }
                }
            }
        }

        setBandIndexes();
    }

    void readUtf8Bands(ConstantPool.Entry[] cpMap) throws IOException {
        //  cp_Utf8:
        //        *cp_Utf8_prefix :DELTA5
        //        *cp_Utf8_suffix :UNSIGNED5
        //        *cp_Utf8_chars :CHAR3
        //        *cp_Utf8_big_suffix :DELTA5
        //        (*cp_Utf8_big_chars :DELTA5)
        //          ** length(cp_Utf8_big_suffix)
        int len = cpMap.length;
        if (len == 0)
            return;  // nothing to read

        // Bands have implicit leading zeroes, for the empty string:
        final int SUFFIX_SKIP_1 = 1;
        final int PREFIX_SKIP_2 = 2;

        // First band:  Read lengths of shared prefixes.
        cp_Utf8_prefix.expectLength(Math.max(0, len - PREFIX_SKIP_2));
        cp_Utf8_prefix.readFrom(in);

        // Second band:  Read lengths of unshared suffixes:
        cp_Utf8_suffix.expectLength(Math.max(0, len - SUFFIX_SKIP_1));
        cp_Utf8_suffix.readFrom(in);

        char[][] suffixChars = new char[len][];
        int bigSuffixCount = 0;

        // Third band:  Read the char values in the unshared suffixes:
        cp_Utf8_chars.expectLength(cp_Utf8_suffix.getIntTotal());
        cp_Utf8_chars.readFrom(in);
        for (int i = 0; i < len; i++) {
            int suffix = (i < SUFFIX_SKIP_1)? 0: cp_Utf8_suffix.getInt();
            if (suffix == 0 && i >= SUFFIX_SKIP_1) {
                // chars are packed in cp_Utf8_big_chars
                bigSuffixCount += 1;
                continue;
            }
            suffixChars[i] = new char[suffix];
            for (int j = 0; j < suffix; j++) {
                int ch = cp_Utf8_chars.getInt();
                assert(ch == (char)ch);
                suffixChars[i][j] = (char)ch;
            }
        }
        cp_Utf8_chars.doneDisbursing();

        // Fourth band:  Go back and size the specially packed strings.
        int maxChars = 0;
        cp_Utf8_big_suffix.expectLength(bigSuffixCount);
        cp_Utf8_big_suffix.readFrom(in);
        cp_Utf8_suffix.resetForSecondPass();
        for (int i = 0; i < len; i++) {
            int suffix = (i < SUFFIX_SKIP_1)? 0: cp_Utf8_suffix.getInt();
            int prefix = (i < PREFIX_SKIP_2)? 0: cp_Utf8_prefix.getInt();
            if (suffix == 0 && i >= SUFFIX_SKIP_1) {
                assert(suffixChars[i] == null);
                suffix = cp_Utf8_big_suffix.getInt();
            } else {
                assert(suffixChars[i] != null);
            }
            if (maxChars < prefix + suffix)
                maxChars = prefix + suffix;
        }
        char[] buf = new char[maxChars];

        // Fifth band(s):  Get the specially packed characters.
        cp_Utf8_suffix.resetForSecondPass();
        cp_Utf8_big_suffix.resetForSecondPass();
        for (int i = 0; i < len; i++) {
            if (i < SUFFIX_SKIP_1)  continue;
            int suffix = cp_Utf8_suffix.getInt();
            if (suffix != 0)  continue;  // already input
            suffix = cp_Utf8_big_suffix.getInt();
            suffixChars[i] = new char[suffix];
            if (suffix == 0) {
                // Do not bother to add an empty "(Utf8_big_0)" band.
                continue;
            }
            IntBand packed = cp_Utf8_big_chars.newIntBand("(Utf8_big_"+i+")");
            packed.expectLength(suffix);
            packed.readFrom(in);
            for (int j = 0; j < suffix; j++) {
                int ch = packed.getInt();
                assert(ch == (char)ch);
                suffixChars[i][j] = (char)ch;
            }
            packed.doneDisbursing();
        }
        cp_Utf8_big_chars.doneDisbursing();

        // Finally, sew together all the prefixes and suffixes.
        cp_Utf8_prefix.resetForSecondPass();
        cp_Utf8_suffix.resetForSecondPass();
        cp_Utf8_big_suffix.resetForSecondPass();
        for (int i = 0; i < len; i++) {
            int prefix = (i < PREFIX_SKIP_2)? 0: cp_Utf8_prefix.getInt();
            int suffix = (i < SUFFIX_SKIP_1)? 0: cp_Utf8_suffix.getInt();
            if (suffix == 0 && i >= SUFFIX_SKIP_1)
                suffix = cp_Utf8_big_suffix.getInt();

            // by induction, the buffer is already filled with the prefix
            System.arraycopy(suffixChars[i], 0, buf, prefix, suffix);

            cpMap[i] = ConstantPool.getUtf8Entry(new String(buf, 0, prefix+suffix));
        }

        cp_Utf8_prefix.doneDisbursing();
        cp_Utf8_suffix.doneDisbursing();
        cp_Utf8_big_suffix.doneDisbursing();
    }

    Map<ConstantPool.Utf8Entry, ConstantPool.SignatureEntry> utf8Signatures;

    void readSignatureBands(ConstantPool.Entry[] cpMap) throws IOException {
        //  cp_Signature:
        //        *cp_Signature_form :DELTA5  (cp_Utf8)
        //        *cp_Signature_classes :UDELTA5  (cp_Class)
        cp_Signature_form.expectLength(cpMap.length);
        cp_Signature_form.readFrom(in);
        cp_Signature_form.setIndex(getCPIndex(Constants.CONSTANT_Utf8));
        int[] numSigClasses = new int[cpMap.length];
        for (int i = 0; i < cpMap.length; i++) {
            ConstantPool.Utf8Entry formRef = (ConstantPool.Utf8Entry) cp_Signature_form.getRef();
            numSigClasses[i] = ConstantPool.countClassParts(formRef);
        }
        cp_Signature_form.resetForSecondPass();
        cp_Signature_classes.expectLength(getIntTotal(numSigClasses));
        cp_Signature_classes.readFrom(in);
        cp_Signature_classes.setIndex(getCPIndex(Constants.CONSTANT_Class));
        utf8Signatures = new HashMap<>();
        for (int i = 0; i < cpMap.length; i++) {
            ConstantPool.Utf8Entry formRef = (ConstantPool.Utf8Entry) cp_Signature_form.getRef();
            ConstantPool.ClassEntry[] classRefs = new ConstantPool.ClassEntry[numSigClasses[i]];
            for (int j = 0; j < classRefs.length; j++) {
                classRefs[j] = (ConstantPool.ClassEntry) cp_Signature_classes.getRef();
            }
            ConstantPool.SignatureEntry se = ConstantPool.getSignatureEntry(formRef, classRefs);
            cpMap[i] = se;
            utf8Signatures.put(se.asUtf8Entry(), se);
        }
        cp_Signature_form.doneDisbursing();
        cp_Signature_classes.doneDisbursing();
    }

    void readMemberRefs(byte tag, ConstantPool.Entry[] cpMap, CPRefBand cp_class, CPRefBand cp_desc) throws IOException {
        //  cp_Field:
        //        *cp_Field_class :DELTA5  (cp_Class)
        //        *cp_Field_desc :UDELTA5  (cp_Descr)
        //  cp_Method:
        //        *cp_Method_class :DELTA5  (cp_Class)
        //        *cp_Method_desc :UDELTA5  (cp_Descr)
        //  cp_Imethod:
        //        *cp_Imethod_class :DELTA5  (cp_Class)
        //        *cp_Imethod_desc :UDELTA5  (cp_Descr)
        cp_class.expectLength(cpMap.length);
        cp_class.readFrom(in);
        cp_class.setIndex(getCPIndex(Constants.CONSTANT_Class));
        cp_desc.expectLength(cpMap.length);
        cp_desc.readFrom(in);
        cp_desc.setIndex(getCPIndex(Constants.CONSTANT_NameandType));
        for (int i = 0; i < cpMap.length; i++) {
            ConstantPool.ClassEntry mclass = (ConstantPool.ClassEntry) cp_class.getRef();
            ConstantPool.DescriptorEntry mdescr = (ConstantPool.DescriptorEntry) cp_desc.getRef();
            cpMap[i] = ConstantPool.getMemberEntry(tag, mclass, mdescr);
        }
        cp_class.doneDisbursing();
        cp_desc.doneDisbursing();
    }

    void readFiles() throws IOException {
        //  file_bands:
        //        *file_name :UNSIGNED5  (cp_Utf8)
        //        *file_size_hi :UNSIGNED5
        //        *file_size_lo :UNSIGNED5
        //        *file_modtime :DELTA5
        //        *file_options :UNSIGNED5
        //        *file_bits :BYTE1
        if (verbose > 0)
            Utils.log.info("  ...building "+numFiles+" files...");
        file_name.expectLength(numFiles);
        file_size_lo.expectLength(numFiles);
        int options = archiveOptions;
        boolean haveSizeHi  = testBit(options, Constants.AO_HAVE_FILE_SIZE_HI);
        boolean haveModtime = testBit(options, Constants.AO_HAVE_FILE_MODTIME);
        boolean haveOptions = testBit(options, Constants.AO_HAVE_FILE_OPTIONS);
        if (haveSizeHi)
            file_size_hi.expectLength(numFiles);
        if (haveModtime)
            file_modtime.expectLength(numFiles);
        if (haveOptions)
            file_options.expectLength(numFiles);

        file_name.readFrom(in);
        file_size_hi.readFrom(in);
        file_size_lo.readFrom(in);
        file_modtime.readFrom(in);
        file_options.readFrom(in);
        file_bits.setInputStreamFrom(in);

        Iterator<Package.Class> nextClass = pkg.getClasses().iterator();

        // Compute file lengths before reading any file bits.
        long totalFileLength = 0;
        long[] fileLengths = new long[numFiles];
        for (int i = 0; i < numFiles; i++) {
            long size = ((long)file_size_lo.getInt() << 32) >>> 32;
            if (haveSizeHi)
                size += (long)file_size_hi.getInt() << 32;
            fileLengths[i] = size;
            totalFileLength += size;
        }
        assert(in.getReadLimit() == -1 || in.getReadLimit() == totalFileLength);

        byte[] buf = new byte[1<<16];
        for (int i = 0; i < numFiles; i++) {
            // %%% Use a big temp file for file bits?
            ConstantPool.Utf8Entry name = (ConstantPool.Utf8Entry) file_name.getRef();
            long size = fileLengths[i];
            Package.File file = pkg.new File(name);
            file.modtime = pkg.default_modtime;
            file.options = pkg.default_options;
            if (haveModtime)
                file.modtime += file_modtime.getInt();
            if (haveOptions)
                file.options |= file_options.getInt();
            if (verbose > 1)
                Utils.log.fine("Reading "+size+" bytes of "+name.stringValue());
            long toRead = size;
            while (toRead > 0) {
                int nr = buf.length;
                if (nr > toRead)  nr = (int) toRead;
                nr = file_bits.getInputStream().read(buf, 0, nr);
                if (nr < 0)  throw new EOFException();
                file.addBytes(buf, 0, nr);
                toRead -= nr;
            }
            pkg.addFile(file);
            if (file.isClassStub()) {
                assert(file.getFileLength() == 0);
                Package.Class cls = nextClass.next();
                cls.initFile(file);
            }
        }

        // Do the rest of the classes.
        while (nextClass.hasNext()) {
            Package.Class cls = nextClass.next();
            cls.initFile(null);  // implicitly initialize to a trivial one
            cls.file.modtime = pkg.default_modtime;
        }

        file_name.doneDisbursing();
        file_size_hi.doneDisbursing();
        file_size_lo.doneDisbursing();
        file_modtime.doneDisbursing();
        file_options.doneDisbursing();
        file_bits.doneDisbursing();
        file_bands.doneDisbursing();

        if (archiveSize1 != 0 && !in.atLimit()) {
            throw new RuntimeException("Predicted archive_size "+
                                       archiveSize1+" != "+
                                       (in.getBytesServed()-archiveSize0));
        }
    }

    void readAttrDefs() throws IOException {
        //  attr_definition_bands:
        //        *attr_definition_headers :BYTE1
        //        *attr_definition_name :UNSIGNED5  (cp_Utf8)
        //        *attr_definition_layout :UNSIGNED5  (cp_Utf8)
        attr_definition_headers.expectLength(numAttrDefs);
        attr_definition_name.expectLength(numAttrDefs);
        attr_definition_layout.expectLength(numAttrDefs);
        attr_definition_headers.readFrom(in);
        attr_definition_name.readFrom(in);
        attr_definition_layout.readFrom(in);
        try (PrintStream dump = !optDumpBands ? null
                 : new PrintStream(getDumpStream(attr_definition_headers, ".def")))
        {
            for (int i = 0; i < numAttrDefs; i++) {
                int       header = attr_definition_headers.getByte();
                ConstantPool.Utf8Entry name   = (ConstantPool.Utf8Entry) attr_definition_name.getRef();
                ConstantPool.Utf8Entry layout = (ConstantPool.Utf8Entry) attr_definition_layout.getRef();
                int       ctype  = (header &  ADH_CONTEXT_MASK);
                int       index  = (header >> ADH_BIT_SHIFT) - ADH_BIT_IS_LSB;
                Attribute.Layout def = new Attribute.Layout(ctype,
                                                            name.stringValue(),
                                                            layout.stringValue());
                // Check layout string for Java 6 extensions.
                String pvLayout = def.layoutForClassVersion(getHighestClassVersion());
                if (!pvLayout.equals(def.layout())) {
                    throw new IOException("Bad attribute layout in archive: "+def.layout());
                }
                this.setAttributeLayoutIndex(def, index);
                if (dump != null)  dump.println(index+" "+def);
            }
        }
        attr_definition_headers.doneDisbursing();
        attr_definition_name.doneDisbursing();
        attr_definition_layout.doneDisbursing();
        // Attribute layouts define bands, one per layout element.
        // Create them now, all at once.
        makeNewAttributeBands();
        attr_definition_bands.doneDisbursing();
    }

    void readInnerClasses() throws IOException {
        //  ic_bands:
        //        *ic_this_class :UDELTA5  (cp_Class)
        //        *ic_flags :UNSIGNED5
        //        *ic_outer_class :DELTA5  (null or cp_Class)
        //        *ic_name :DELTA5  (null or cp_Utf8)
        ic_this_class.expectLength(numInnerClasses);
        ic_this_class.readFrom(in);
        ic_flags.expectLength(numInnerClasses);
        ic_flags.readFrom(in);
        int longICCount = 0;
        for (int i = 0; i < numInnerClasses; i++) {
            int flags = ic_flags.getInt();
            boolean longForm = (flags & Constants.ACC_IC_LONG_FORM) != 0;
            if (longForm) {
                longICCount += 1;
            }
        }
        ic_outer_class.expectLength(longICCount);
        ic_outer_class.readFrom(in);
        ic_name.expectLength(longICCount);
        ic_name.readFrom(in);
        ic_flags.resetForSecondPass();
        List<Package.InnerClass> icList = new ArrayList<>(numInnerClasses);
        for (int i = 0; i < numInnerClasses; i++) {
            int flags = ic_flags.getInt();
            boolean longForm = (flags & Constants.ACC_IC_LONG_FORM) != 0;
            flags &= ~Constants.ACC_IC_LONG_FORM;
            ConstantPool.ClassEntry thisClass = (ConstantPool.ClassEntry) ic_this_class.getRef();
            ConstantPool.ClassEntry outerClass;
            ConstantPool.Utf8Entry thisName;
            if (longForm) {
                outerClass = (ConstantPool.ClassEntry) ic_outer_class.getRef();
                thisName   = (ConstantPool.Utf8Entry)  ic_name.getRef();
            } else {
                String n = thisClass.stringValue();
                String[] parse = Package.parseInnerClassName(n);
                assert(parse != null);
                String pkgOuter = parse[0];
                //String number = parse[1];
                String name     = parse[2];
                if (pkgOuter == null)
                    outerClass = null;
                else
                    outerClass = ConstantPool.getClassEntry(pkgOuter);
                if (name == null)
                    thisName   = null;
                else
                    thisName   = ConstantPool.getUtf8Entry(name);
            }
            Package.InnerClass ic =
                new Package.InnerClass(thisClass, outerClass, thisName, flags);
            assert(longForm || ic.predictable);
            icList.add(ic);
        }
        ic_flags.doneDisbursing();
        ic_this_class.doneDisbursing();
        ic_outer_class.doneDisbursing();
        ic_name.doneDisbursing();
        pkg.setAllInnerClasses(icList);
        ic_bands.doneDisbursing();
    }

    void readLocalInnerClasses(Package.Class cls) throws IOException {
        int nc = class_InnerClasses_N.getInt();
        List<Package.InnerClass> localICs = new ArrayList<>(nc);
        for (int i = 0; i < nc; i++) {
            ConstantPool.ClassEntry thisClass = (ConstantPool.ClassEntry) class_InnerClasses_RC.getRef();
            int        flags     =              class_InnerClasses_F.getInt();
            if (flags == 0) {
                // A zero flag means copy a global IC here.
                Package.InnerClass ic = pkg.getGlobalInnerClass(thisClass);
                assert(ic != null);  // must be a valid global IC reference
                localICs.add(ic);
            } else {
                if (flags == Constants.ACC_IC_LONG_FORM)
                    flags = 0;  // clear the marker bit
                ConstantPool.ClassEntry outer = (ConstantPool.ClassEntry) class_InnerClasses_outer_RCN.getRef();
                ConstantPool.Utf8Entry name   = (ConstantPool.Utf8Entry)  class_InnerClasses_name_RUN.getRef();
                localICs.add(new Package.InnerClass(thisClass, outer, name, flags));
            }
        }
        cls.setInnerClasses(localICs);
        // cls.expandLocalICs may add more tuples to ics also,
        // or may even delete tuples.
        // We cannot do that now, because we do not know the
        // full contents of the local constant pool yet.
    }

    static final int NO_FLAGS_YET = 0;  // placeholder for later flag read-in

    Package.Class[] readClasses() throws IOException {
        //  class_bands:
        //        *class_this :DELTA5  (cp_Class)
        //        *class_super :DELTA5  (cp_Class)
        //        *class_interface_count :DELTA5
        //        *class_interface :DELTA5  (cp_Class)
        //        ...(member bands)...
        //        class_attr_bands
        //        code_bands
        Package.Class[] classes = new Package.Class[numClasses];
        if (verbose > 0)
            Utils.log.info("  ...building "+classes.length+" classes...");

        class_this.expectLength(numClasses);
        class_super.expectLength(numClasses);
        class_interface_count.expectLength(numClasses);

        class_this.readFrom(in);
        class_super.readFrom(in);
        class_interface_count.readFrom(in);
        class_interface.expectLength(class_interface_count.getIntTotal());
        class_interface.readFrom(in);
        for (int i = 0; i < classes.length; i++) {
            ConstantPool.ClassEntry thisClass  = (ConstantPool.ClassEntry) class_this.getRef();
            ConstantPool.ClassEntry superClass = (ConstantPool.ClassEntry) class_super.getRef();
            ConstantPool.ClassEntry[] interfaces = new ConstantPool.ClassEntry[class_interface_count.getInt()];
            for (int j = 0; j < interfaces.length; j++) {
                interfaces[j] = (ConstantPool.ClassEntry) class_interface.getRef();
            }
            // Packer encoded rare case of null superClass as thisClass:
            if (superClass == thisClass)  superClass = null;
            Package.Class cls = pkg.new Class(NO_FLAGS_YET,
                                      thisClass, superClass, interfaces);
            classes[i] = cls;
        }
        class_this.doneDisbursing();
        class_super.doneDisbursing();
        class_interface_count.doneDisbursing();
        class_interface.doneDisbursing();
        readMembers(classes);
        countAndReadAttrs(Constants.ATTR_CONTEXT_CLASS, Arrays.asList(classes));
        pkg.trimToSize();
        readCodeHeaders();
        //code_bands.doneDisbursing(); // still need to read code attrs
        //class_bands.doneDisbursing(); // still need to read code attrs
        return classes;
    }

    private int getOutputIndex(ConstantPool.Entry e) {
        // Output CPs do not contain signatures.
        assert(e.tag != Constants.CONSTANT_Signature);
        int k = pkg.cp.untypedIndexOf(e);
        // In the output ordering, input signatures can serve
        // in place of Utf8s.
        if (k >= 0)
            return k;
        if (e.tag == Constants.CONSTANT_Utf8) {
            ConstantPool.Entry se = utf8Signatures.get(e);
            return pkg.cp.untypedIndexOf(se);
        }
        return -1;
    }

    Comparator<ConstantPool.Entry> entryOutputOrder = new Comparator<ConstantPool.Entry>() {
        public int compare(ConstantPool.Entry e0, ConstantPool.Entry e1) {
            int k0 = getOutputIndex(e0);
            int k1 = getOutputIndex(e1);
            if (k0 >= 0 && k1 >= 0)
                // If both have keys, use the keys.
                return k0 - k1;
            if (k0 == k1)
                // If neither have keys, use their native tags & spellings.
                return e0.compareTo(e1);
            // Otherwise, the guy with the key comes first.
            return (k0 >= 0)? 0-1: 1-0;
        }
    };

    void reconstructClass(Package.Class cls) {
        if (verbose > 1)  Utils.log.fine("reconstruct "+cls);

        // check for local .ClassFile.version
        Attribute retroVersion = cls.getAttribute(attrClassFileVersion);
        if (retroVersion != null) {
            cls.removeAttribute(retroVersion);
            cls.version = parseClassFileVersionAttr(retroVersion);
        } else {
            cls.version = pkg.defaultClassVersion;
        }

        // Replace null SourceFile by "obvious" string.
        cls.expandSourceFile();

        // record the local cp:
        cls.setCPMap(reconstructLocalCPMap(cls));
    }

    ConstantPool.Entry[] reconstructLocalCPMap(Package.Class cls) {
        Set<ConstantPool.Entry> ldcRefs = ldcRefMap.get(cls);
        Set<ConstantPool.Entry> cpRefs = new HashSet<>();

        // look for constant pool entries:
        cls.visitRefs(Constants.VRM_CLASSIC, cpRefs);

        ArrayList<ConstantPool.BootstrapMethodEntry> bsms = new ArrayList<>();
        // flesh out the local constant pool
        ConstantPool.completeReferencesIn(cpRefs, true, bsms);

        // add the bsm and references as required
        if (!bsms.isEmpty()) {
            cls.addAttribute(Package.attrBootstrapMethodsEmpty.canonicalInstance());
            cpRefs.add(Package.getRefString("BootstrapMethods"));
            Collections.sort(bsms);
            cls.setBootstrapMethods(bsms);
        }

        // Now that we know all our local class references,
        // compute the InnerClasses attribute.
        // An InnerClasses attribute usually gets added here,
        // although it might already have been present.
        int changed = cls.expandLocalICs();

        if (changed != 0) {
            if (changed > 0) {
                // Just visit the expanded InnerClasses attr.
                cls.visitInnerClassRefs(Constants.VRM_CLASSIC, cpRefs);
            } else {
                // Have to recompute from scratch, because of deletions.
                cpRefs.clear();
                cls.visitRefs(Constants.VRM_CLASSIC, cpRefs);
            }

            // flesh out the local constant pool, again
            ConstantPool.completeReferencesIn(cpRefs, true, bsms);
        }

        // construct a local constant pool
        int numDoubles = 0;
        for (ConstantPool.Entry e : cpRefs) {
            if (e.isDoubleWord())  numDoubles++;
        }
        ConstantPool.Entry[] cpMap = new ConstantPool.Entry[1+numDoubles+cpRefs.size()];
        int fillp = 1;

        // Add all ldc operands first.
        if (ldcRefs != null) {
            assert(cpRefs.containsAll(ldcRefs));
            for (ConstantPool.Entry e : ldcRefs) {
                cpMap[fillp++] = e;
            }
            assert(fillp == 1+ldcRefs.size());
            cpRefs.removeAll(ldcRefs);
            ldcRefs = null;  // done with it
        }

        // Next add all the two-byte references.
        Set<ConstantPool.Entry> wideRefs = cpRefs;
        cpRefs = null;  // do not use!
        int narrowLimit = fillp;
        for (ConstantPool.Entry e : wideRefs) {
            cpMap[fillp++] = e;
        }
        assert(fillp == narrowLimit+wideRefs.size());
        Arrays.sort(cpMap, 1, narrowLimit, entryOutputOrder);
        Arrays.sort(cpMap, narrowLimit, fillp, entryOutputOrder);

        if (verbose > 3) {
            Utils.log.fine("CP of "+this+" {");
            for (int i = 0; i < fillp; i++) {
                ConstantPool.Entry e = cpMap[i];
                Utils.log.fine("  "+((e==null)?-1:getOutputIndex(e))
                                   +" : "+e);
            }
            Utils.log.fine("}");
        }

        // Now repack backwards, introducing null elements.
        int revp = cpMap.length;
        for (int i = fillp; --i >= 1; ) {
            ConstantPool.Entry e = cpMap[i];
            if (e.isDoubleWord())
                cpMap[--revp] = null;
            cpMap[--revp] = e;
        }
        assert(revp == 1);  // do not process the initial null

        return cpMap;
    }

    void readMembers(Package.Class[] classes) throws IOException {
        //  class_bands:
        //        ...
        //        *class_field_count :DELTA5
        //        *class_method_count :DELTA5
        //
        //        *field_descr :DELTA5  (cp_Descr)
        //        field_attr_bands
        //
        //        *method_descr :MDELTA5  (cp_Descr)
        //        method_attr_bands
        //        ...
        assert(classes.length == numClasses);
        class_field_count.expectLength(numClasses);
        class_method_count.expectLength(numClasses);
        class_field_count.readFrom(in);
        class_method_count.readFrom(in);

        // Make a pre-pass over field and method counts to size the descrs:
        int totalNF = class_field_count.getIntTotal();
        int totalNM = class_method_count.getIntTotal();
        field_descr.expectLength(totalNF);
        method_descr.expectLength(totalNM);
        if (verbose > 1)  Utils.log.fine("expecting #fields="+totalNF+
                " and #methods="+totalNM+" in #classes="+numClasses);

        List<Package.Class.Field> fields = new ArrayList<>(totalNF);
        field_descr.readFrom(in);
        for (int i = 0; i < classes.length; i++) {
            Package.Class c = classes[i];
            int nf = class_field_count.getInt();
            for (int j = 0; j < nf; j++) {
                Package.Class.Field f = c.new Field(NO_FLAGS_YET, (ConstantPool.DescriptorEntry)
                                            field_descr.getRef());
                fields.add(f);
            }
        }
        class_field_count.doneDisbursing();
        field_descr.doneDisbursing();
        countAndReadAttrs(Constants.ATTR_CONTEXT_FIELD, fields);
        fields = null;  // release to GC

        List<Package.Class.Method> methods = new ArrayList<>(totalNM);
        method_descr.readFrom(in);
        for (int i = 0; i < classes.length; i++) {
            Package.Class c = classes[i];
            int nm = class_method_count.getInt();
            for (int j = 0; j < nm; j++) {
                Package.Class.Method m = c.new Method(NO_FLAGS_YET, (ConstantPool.DescriptorEntry)
                                              method_descr.getRef());
                methods.add(m);
            }
        }
        class_method_count.doneDisbursing();
        method_descr.doneDisbursing();
        countAndReadAttrs(Constants.ATTR_CONTEXT_METHOD, methods);

        // Up to this point, Code attributes look like empty attributes.
        // Now we start to special-case them.  The empty canonical Code
        // attributes stay in the method attribute lists, however.
        allCodes = buildCodeAttrs(methods);
    }

    Code[] allCodes;
    List<Code> codesWithFlags;
    Map<Package.Class, Set<ConstantPool.Entry>> ldcRefMap = new HashMap<>();

    Code[] buildCodeAttrs(List<Package.Class.Method> methods) {
        List<Code> codes = new ArrayList<>(methods.size());
        for (Package.Class.Method m : methods) {
            if (m.getAttribute(attrCodeEmpty) != null) {
                m.code = new Code(m);
                codes.add(m.code);
            }
        }
        Code[] a = new Code[codes.size()];
        codes.toArray(a);
        return a;
    }

    void readCodeHeaders() throws IOException {
        //  code_bands:
        //        *code_headers :BYTE1
        //
        //        *code_max_stack :UNSIGNED5
        //        *code_max_na_locals :UNSIGNED5
        //        *code_handler_count :UNSIGNED5
        //        ...
        //        code_attr_bands
        boolean attrsOK = testBit(archiveOptions, Constants.AO_HAVE_ALL_CODE_FLAGS);
        code_headers.expectLength(allCodes.length);
        code_headers.readFrom(in);
        List<Code> longCodes = new ArrayList<>(allCodes.length / 10);
        for (int i = 0; i < allCodes.length; i++) {
            Code c = allCodes[i];
            int sc = code_headers.getByte();
            assert(sc == (sc & 0xFF));
            if (verbose > 2)
                Utils.log.fine("codeHeader "+c+" = "+sc);
            if (sc == LONG_CODE_HEADER) {
                // We will read ms/ml/nh/flags from bands shortly.
                longCodes.add(c);
                continue;
            }
            // Short code header is the usual case:
            c.setMaxStack(     shortCodeHeader_max_stack(sc) );
            c.setMaxNALocals(  shortCodeHeader_max_na_locals(sc) );
            c.setHandlerCount( shortCodeHeader_handler_count(sc) );
            assert(shortCodeHeader(c) == sc);
        }
        code_headers.doneDisbursing();
        code_max_stack.expectLength(longCodes.size());
        code_max_na_locals.expectLength(longCodes.size());
        code_handler_count.expectLength(longCodes.size());

        // Do the long headers now.
        code_max_stack.readFrom(in);
        code_max_na_locals.readFrom(in);
        code_handler_count.readFrom(in);
        for (Code c : longCodes) {
            c.setMaxStack(     code_max_stack.getInt() );
            c.setMaxNALocals(  code_max_na_locals.getInt() );
            c.setHandlerCount( code_handler_count.getInt() );
        }
        code_max_stack.doneDisbursing();
        code_max_na_locals.doneDisbursing();
        code_handler_count.doneDisbursing();

        readCodeHandlers();

        if (attrsOK) {
            // Code attributes are common (debug info not stripped).
            codesWithFlags = Arrays.asList(allCodes);
        } else {
            // Code attributes are very sparse (debug info is stripped).
            codesWithFlags = longCodes;
        }
        countAttrs(Constants.ATTR_CONTEXT_CODE, codesWithFlags);
        // do readAttrs later, after BCs are scanned
    }

    void readCodeHandlers() throws IOException {
        //  code_bands:
        //        ...
        //        *code_handler_start_P :BCI5
        //        *code_handler_end_PO :BRANCH5
        //        *code_handler_catch_PO :BRANCH5
        //        *code_handler_class_RCN :UNSIGNED5  (null or cp_Class)
        //        ...
        int nh = 0;
        for (int i = 0; i < allCodes.length; i++) {
            Code c = allCodes[i];
            nh += c.getHandlerCount();
        }

        ValueBand[] code_handler_bands = {
            code_handler_start_P,
            code_handler_end_PO,
            code_handler_catch_PO,
            code_handler_class_RCN
        };

        for (int i = 0; i < code_handler_bands.length; i++) {
            code_handler_bands[i].expectLength(nh);
            code_handler_bands[i].readFrom(in);
        }

        for (int i = 0; i < allCodes.length; i++) {
            Code c = allCodes[i];
            for (int j = 0, jmax = c.getHandlerCount(); j < jmax; j++) {
                c.handler_class[j] = code_handler_class_RCN.getRef();
                // For now, just record the raw BCI codes.
                // We must wait until we have instruction boundaries.
                c.handler_start[j] = code_handler_start_P.getInt();
                c.handler_end[j]   = code_handler_end_PO.getInt();
                c.handler_catch[j] = code_handler_catch_PO.getInt();
            }
        }
        for (int i = 0; i < code_handler_bands.length; i++) {
            code_handler_bands[i].doneDisbursing();
        }
    }

    void fixupCodeHandlers() {
        // Actually decode (renumber) the BCIs now.
        for (int i = 0; i < allCodes.length; i++) {
            Code c = allCodes[i];
            for (int j = 0, jmax = c.getHandlerCount(); j < jmax; j++) {
                int sum = c.handler_start[j];
                c.handler_start[j] = c.decodeBCI(sum);
                sum += c.handler_end[j];
                c.handler_end[j]   = c.decodeBCI(sum);
                sum += c.handler_catch[j];
                c.handler_catch[j] = c.decodeBCI(sum);
            }
        }
    }

    // Generic routines for reading attributes of
    // classes, fields, methods, and codes.
    // The holders is a global list, already collected,
    // of attribute "customers".
    void countAndReadAttrs(int ctype, Collection<? extends Attribute.Holder> holders)
            throws IOException {
        //  class_attr_bands:
        //        *class_flags :UNSIGNED5
        //        *class_attr_count :UNSIGNED5
        //        *class_attr_indexes :UNSIGNED5
        //        *class_attr_calls :UNSIGNED5
        //        *class_Signature_RS :UNSIGNED5 (cp_Signature)
        //        class_metadata_bands
        //        *class_SourceFile_RU :UNSIGNED5 (cp_Utf8)
        //        *class_EnclosingMethod_RM :UNSIGNED5 (cp_Method)
        //        ic_local_bands
        //        *class_ClassFile_version_minor_H :UNSIGNED5
        //        *class_ClassFile_version_major_H :UNSIGNED5
        //        class_type_metadata_bands
        //
        //  field_attr_bands:
        //        *field_flags :UNSIGNED5
        //        *field_attr_count :UNSIGNED5
        //        *field_attr_indexes :UNSIGNED5
        //        *field_attr_calls :UNSIGNED5
        //        *field_Signature_RS :UNSIGNED5 (cp_Signature)
        //        field_metadata_bands
        //        *field_ConstantValue_KQ :UNSIGNED5 (cp_Int, etc.; see note)
        //        field_type_metadata_bands
        //
        //  method_attr_bands:
        //        *method_flags :UNSIGNED5
        //        *method_attr_count :UNSIGNED5
        //        *method_attr_indexes :UNSIGNED5
        //        *method_attr_calls :UNSIGNED5
        //        *method_Signature_RS :UNSIGNED5 (cp_Signature)
        //        method_metadata_bands
        //        *method_Exceptions_N :UNSIGNED5
        //        *method_Exceptions_RC :UNSIGNED5  (cp_Class)
        //        *method_MethodParameters_NB: BYTE1
        //        *method_MethodParameters_RUN: UNSIGNED5 (cp_Utf8)
        //        *method_MethodParameters_FH:  UNSIGNED5 (flag)
        //        method_type_metadata_bands
        //
        //  code_attr_bands:
        //        *code_flags :UNSIGNED5
        //        *code_attr_count :UNSIGNED5
        //        *code_attr_indexes :UNSIGNED5
        //        *code_attr_calls :UNSIGNED5
        //        *code_LineNumberTable_N :UNSIGNED5
        //        *code_LineNumberTable_bci_P :BCI5
        //        *code_LineNumberTable_line :UNSIGNED5
        //        *code_LocalVariableTable_N :UNSIGNED5
        //        *code_LocalVariableTable_bci_P :BCI5
        //        *code_LocalVariableTable_span_O :BRANCH5
        //        *code_LocalVariableTable_name_RU :UNSIGNED5 (cp_Utf8)
        //        *code_LocalVariableTable_type_RS :UNSIGNED5 (cp_Signature)
        //        *code_LocalVariableTable_slot :UNSIGNED5
        //        code_type_metadata_bands

        countAttrs(ctype, holders);
        readAttrs(ctype, holders);
    }

    // Read flags and count the attributes that are to be placed
    // on the given holders.
    void countAttrs(int ctype, Collection<? extends Attribute.Holder> holders)
            throws IOException {
        // Here, xxx stands for one of class, field, method, code.
        MultiBand xxx_attr_bands = attrBands[ctype];
        long flagMask = attrFlagMask[ctype];
        if (verbose > 1) {
            Utils.log.fine("scanning flags and attrs for "+
                    Attribute.contextName(ctype)+"["+holders.size()+"]");
        }

        // Fetch the attribute layout definitions which govern the bands
        // we are about to read.
        List<Attribute.Layout> defList = attrDefs.get(ctype);
        Attribute.Layout[] defs = new Attribute.Layout[defList.size()];
        defList.toArray(defs);
        IntBand xxx_flags_hi = getAttrBand(xxx_attr_bands, AB_FLAGS_HI);
        IntBand xxx_flags_lo = getAttrBand(xxx_attr_bands, AB_FLAGS_LO);
        IntBand xxx_attr_count = getAttrBand(xxx_attr_bands, AB_ATTR_COUNT);
        IntBand xxx_attr_indexes = getAttrBand(xxx_attr_bands, AB_ATTR_INDEXES);
        IntBand xxx_attr_calls = getAttrBand(xxx_attr_bands, AB_ATTR_CALLS);

        // Count up the number of holders which have overflow attrs.
        int overflowMask = attrOverflowMask[ctype];
        int overflowHolderCount = 0;
        boolean haveLongFlags = haveFlagsHi(ctype);
        xxx_flags_hi.expectLength(haveLongFlags? holders.size(): 0);
        xxx_flags_hi.readFrom(in);
        xxx_flags_lo.expectLength(holders.size());
        xxx_flags_lo.readFrom(in);
        assert((flagMask & overflowMask) == overflowMask);
        for (Attribute.Holder h : holders) {
            int flags = xxx_flags_lo.getInt();
            h.flags = flags;
            if ((flags & overflowMask) != 0)
                overflowHolderCount += 1;
        }

        // For each holder with overflow attrs, read a count.
        xxx_attr_count.expectLength(overflowHolderCount);
        xxx_attr_count.readFrom(in);
        xxx_attr_indexes.expectLength(xxx_attr_count.getIntTotal());
        xxx_attr_indexes.readFrom(in);

        // Now it's time to check flag bits that indicate attributes.
        // We accumulate (a) a list of attribute types for each holder
        // (class/field/method/code), and also we accumulate (b) a total
        // count for each attribute type.
        int[] totalCounts = new int[defs.length];
        for (Attribute.Holder h : holders) {
            assert(h.attributes == null);
            // System.out.println("flags="+h.flags+" using fm="+flagMask);
            long attrBits = ((h.flags & flagMask) << 32) >>> 32;
            // Clean up the flags now.
            h.flags -= (int)attrBits;   // strip attr bits
            assert(h.flags == (char)h.flags);  // 16 bits only now
            assert((ctype != Constants.ATTR_CONTEXT_CODE) || h.flags == 0);
            if (haveLongFlags)
                attrBits += (long)xxx_flags_hi.getInt() << 32;
            if (attrBits == 0)  continue;  // no attrs on this guy

            int noa = 0;  // number of overflow attrs
            long overflowBit = (attrBits & overflowMask);
            assert(overflowBit >= 0);
            attrBits -= overflowBit;
            if (overflowBit != 0) {
                noa = xxx_attr_count.getInt();
            }

            int nfa = 0;  // number of flag attrs
            long bits = attrBits;
            for (int ai = 0; bits != 0; ai++) {
                if ((bits & (1L<<ai)) == 0)  continue;
                bits -= (1L<<ai);
                nfa += 1;
            }
            List<Attribute> ha = new ArrayList<>(nfa + noa);
            h.attributes = ha;
            bits = attrBits;  // iterate again
            for (int ai = 0; bits != 0; ai++) {
                if ((bits & (1L<<ai)) == 0)  continue;
                bits -= (1L<<ai);
                totalCounts[ai] += 1;
                // This definition index is live in this holder.
                if (defs[ai] == null)  badAttrIndex(ai, ctype);
                Attribute canonical = defs[ai].canonicalInstance();
                ha.add(canonical);
                nfa -= 1;
            }
            assert(nfa == 0);
            for (; noa > 0; noa--) {
                int ai = xxx_attr_indexes.getInt();
                totalCounts[ai] += 1;
                // This definition index is live in this holder.
                if (defs[ai] == null)  badAttrIndex(ai, ctype);
                Attribute canonical = defs[ai].canonicalInstance();
                ha.add(canonical);
            }
        }

        xxx_flags_hi.doneDisbursing();
        xxx_flags_lo.doneDisbursing();
        xxx_attr_count.doneDisbursing();
        xxx_attr_indexes.doneDisbursing();

        // Now each holder has a list of canonical attribute instances.
        // For layouts with no elements, we are done.  However, for
        // layouts with bands, we must replace each canonical (empty)
        // instance with a value-bearing one, initialized from the
        // appropriate bands.

        // Make a small pass to detect and read backward call counts.
        int callCounts = 0;
        for (boolean predef = true; ; predef = false) {
            for (int ai = 0; ai < defs.length; ai++) {
                Attribute.Layout def = defs[ai];
                if (def == null)  continue;  // unused index
                if (predef != isPredefinedAttr(ctype, ai))
                    continue;  // wrong pass
                int totalCount = totalCounts[ai];
                if (totalCount == 0)
                    continue;  // irrelevant
                Attribute.Layout.Element[] cbles = def.getCallables();
                for (int j = 0; j < cbles.length; j++) {
                    assert(cbles[j].kind == Attribute.EK_CBLE);
                    if (cbles[j].flagTest(Attribute.EF_BACK))
                        callCounts += 1;
                }
            }
            if (!predef)  break;
        }
        xxx_attr_calls.expectLength(callCounts);
        xxx_attr_calls.readFrom(in);

        // Finally, size all the attribute bands.
        for (boolean predef = true; ; predef = false) {
            for (int ai = 0; ai < defs.length; ai++) {
                Attribute.Layout def = defs[ai];
                if (def == null)  continue;  // unused index
                if (predef != isPredefinedAttr(ctype, ai))
                    continue;  // wrong pass
                int totalCount = totalCounts[ai];
                Band[] ab = attrBandTable.get(def);
                if (def == attrInnerClassesEmpty) {
                    // Special case.
                    // Size the bands as if using the following layout:
                    //    [RCH TI[ (0)[] ()[RCNH RUNH] ]].
                    class_InnerClasses_N.expectLength(totalCount);
                    class_InnerClasses_N.readFrom(in);
                    int tupleCount = class_InnerClasses_N.getIntTotal();
                    class_InnerClasses_RC.expectLength(tupleCount);
                    class_InnerClasses_RC.readFrom(in);
                    class_InnerClasses_F.expectLength(tupleCount);
                    class_InnerClasses_F.readFrom(in);
                    // Drop remaining columns wherever flags are zero:
                    tupleCount -= class_InnerClasses_F.getIntCount(0);
                    class_InnerClasses_outer_RCN.expectLength(tupleCount);
                    class_InnerClasses_outer_RCN.readFrom(in);
                    class_InnerClasses_name_RUN.expectLength(tupleCount);
                    class_InnerClasses_name_RUN.readFrom(in);
                } else if (!optDebugBands && totalCount == 0) {
                    // Expect no elements at all.  Skip quickly. however if we
                    // are debugging bands, read all bands regardless
                    for (int j = 0; j < ab.length; j++) {
                        ab[j].doneWithUnusedBand();
                    }
                } else {
                    // Read these bands in sequence.
                    boolean hasCallables = def.hasCallables();
                    if (!hasCallables) {
                        readAttrBands(def.elems, totalCount, new int[0], ab);
                    } else {
                        Attribute.Layout.Element[] cbles = def.getCallables();
                        // At first, record initial calls.
                        // Later, forward calls may also accumulate here:
                        int[] forwardCounts = new int[cbles.length];
                        forwardCounts[0] = totalCount;
                        for (int j = 0; j < cbles.length; j++) {
                            assert(cbles[j].kind == Attribute.EK_CBLE);
                            int entryCount = forwardCounts[j];
                            forwardCounts[j] = -1;  // No more, please!
                            if (totalCount > 0 && cbles[j].flagTest(Attribute.EF_BACK))
                                entryCount += xxx_attr_calls.getInt();
                            readAttrBands(cbles[j].body, entryCount, forwardCounts, ab);
                        }
                    }
                    // mark them read,  to satisfy asserts
                    if (optDebugBands && totalCount == 0) {
                        for (int j = 0; j < ab.length; j++) {
                            ab[j].doneDisbursing();
                        }
                    }
                }
            }
            if (!predef)  break;
        }
        xxx_attr_calls.doneDisbursing();
    }

    void badAttrIndex(int ai, int ctype) throws IOException {
        throw new IOException("Unknown attribute index "+ai+" for "+
                                   Constants.ATTR_CONTEXT_NAME[ctype]+" attribute");
    }

    void readAttrs(int ctype, Collection<? extends Attribute.Holder> holders)
            throws IOException {
        // Decode band values into attributes.
        Set<Attribute.Layout> sawDefs = new HashSet<>();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        for (final Attribute.Holder h : holders) {
            if (h.attributes == null)  continue;
            for (ListIterator<Attribute> j = h.attributes.listIterator(); j.hasNext(); ) {
                Attribute a = j.next();
                Attribute.Layout def = a.layout();
                if (def.bandCount == 0) {
                    if (def == attrInnerClassesEmpty) {
                        // Special logic to read this attr.
                        readLocalInnerClasses((Package.Class) h);
                        continue;
                    }
                    // Canonical empty attr works fine (e.g., Synthetic).
                    continue;
                }
                sawDefs.add(def);
                boolean isCV = (ctype == Constants.ATTR_CONTEXT_FIELD && def == attrConstantValue);
                if (isCV)  setConstantValueIndex((Package.Class.Field)h);
                if (verbose > 2)
                    Utils.log.fine("read "+a+" in "+h);
                final Band[] ab = attrBandTable.get(def);
                // Read one attribute of type def from ab into a byte array.
                buf.reset();
                Object fixups = a.unparse(new Attribute.ValueStream() {
                    public int getInt(int bandIndex) {
                        return ((IntBand) ab[bandIndex]).getInt();
                    }
                    public ConstantPool.Entry getRef(int bandIndex) {
                        return ((CPRefBand) ab[bandIndex]).getRef();
                    }
                    public int decodeBCI(int bciCode) {
                        Code code = (Code) h;
                        return code.decodeBCI(bciCode);
                    }
                }, buf);
                // Replace the canonical attr with the one just read.
                j.set(a.addContent(buf.toByteArray(), fixups));
                if (isCV)  setConstantValueIndex(null);  // clean up
            }
        }

        // Mark the bands we just used as done disbursing.
        for (Attribute.Layout def : sawDefs) {
            if (def == null)  continue;  // unused index
            Band[] ab = attrBandTable.get(def);
            for (int j = 0; j < ab.length; j++) {
                ab[j].doneDisbursing();
            }
        }

        if (ctype == Constants.ATTR_CONTEXT_CLASS) {
            class_InnerClasses_N.doneDisbursing();
            class_InnerClasses_RC.doneDisbursing();
            class_InnerClasses_F.doneDisbursing();
            class_InnerClasses_outer_RCN.doneDisbursing();
            class_InnerClasses_name_RUN.doneDisbursing();
        }

        MultiBand xxx_attr_bands = attrBands[ctype];
        for (int i = 0; i < xxx_attr_bands.size(); i++) {
            Band b = xxx_attr_bands.get(i);
            if (b instanceof MultiBand)
                b.doneDisbursing();
        }
        xxx_attr_bands.doneDisbursing();
    }

    private
    void readAttrBands(Attribute.Layout.Element[] elems,
                       int count, int[] forwardCounts,
                       Band[] ab)
            throws IOException {
        for (int i = 0; i < elems.length; i++) {
            Attribute.Layout.Element e = elems[i];
            Band eBand = null;
            if (e.hasBand()) {
                eBand = ab[e.bandIndex];
                eBand.expectLength(count);
                eBand.readFrom(in);
            }
            switch (e.kind) {
            case Attribute.EK_REPL:
                // Recursive call.
                int repCount = ((IntBand)eBand).getIntTotal();
                // Note:  getIntTotal makes an extra pass over this band.
                readAttrBands(e.body, repCount, forwardCounts, ab);
                break;
            case Attribute.EK_UN:
                int remainingCount = count;
                for (int j = 0; j < e.body.length; j++) {
                    int caseCount;
                    if (j == e.body.length-1) {
                        caseCount = remainingCount;
                    } else {
                        caseCount = 0;
                        for (int j0 = j;
                             (j == j0)
                             || (j < e.body.length
                                 && e.body[j].flagTest(Attribute.EF_BACK));
                             j++) {
                            caseCount += ((IntBand)eBand).getIntCount(e.body[j].value);
                        }
                        --j;  // back up to last occurrence of this body
                    }
                    remainingCount -= caseCount;
                    readAttrBands(e.body[j].body, caseCount, forwardCounts, ab);
                }
                assert(remainingCount == 0);
                break;
            case Attribute.EK_CALL:
                assert(e.body.length == 1);
                assert(e.body[0].kind == Attribute.EK_CBLE);
                if (!e.flagTest(Attribute.EF_BACK)) {
                    // Backward calls are pre-counted, but forwards are not.
                    // Push the present count forward.
                    assert(forwardCounts[e.value] >= 0);
                    forwardCounts[e.value] += count;
                }
                break;
            case Attribute.EK_CBLE:
                assert(false);
                break;
            }
        }
    }

    void readByteCodes() throws IOException {
        //  bc_bands:
        //        *bc_codes :BYTE1
        //        *bc_case_count :UNSIGNED5
        //        *bc_case_value :DELTA5
        //        *bc_byte :BYTE1
        //        *bc_short :DELTA5
        //        *bc_local :UNSIGNED5
        //        *bc_label :BRANCH5
        //        *bc_intref :DELTA5  (cp_Int)
        //        *bc_floatref :DELTA5  (cp_Float)
        //        *bc_longref :DELTA5  (cp_Long)
        //        *bc_doubleref :DELTA5  (cp_Double)
        //        *bc_stringref :DELTA5  (cp_String)
        //        *bc_classref :UNSIGNED5  (current class or cp_Class)
        //        *bc_fieldref :DELTA5  (cp_Field)
        //        *bc_methodref :UNSIGNED5  (cp_Method)
        //        *bc_imethodref :DELTA5  (cp_Imethod)
        //        *bc_thisfield :UNSIGNED5 (cp_Field, only for current class)
        //        *bc_superfield :UNSIGNED5 (cp_Field, only for current super)
        //        *bc_thismethod :UNSIGNED5 (cp_Method, only for current class)
        //        *bc_supermethod :UNSIGNED5 (cp_Method, only for current super)
        //        *bc_initref :UNSIGNED5 (cp_Field, only for most recent new)
        //        *bc_escref :UNSIGNED5 (cp_All)
        //        *bc_escrefsize :UNSIGNED5
        //        *bc_escsize :UNSIGNED5
        //        *bc_escbyte :BYTE1
        bc_codes.elementCountForDebug = allCodes.length;
        bc_codes.setInputStreamFrom(in);
        readByteCodeOps();  // reads from bc_codes and bc_case_count
        bc_codes.doneDisbursing();

        // All the operand bands have now been sized.  Read them all in turn.
        Band[] operand_bands = {
            bc_case_value,
            bc_byte, bc_short,
            bc_local, bc_label,
            bc_intref, bc_floatref,
            bc_longref, bc_doubleref, bc_stringref,
            bc_loadablevalueref,
            bc_classref, bc_fieldref,
            bc_methodref, bc_imethodref,
            bc_indyref,
            bc_thisfield, bc_superfield,
            bc_thismethod, bc_supermethod,
            bc_initref,
            bc_escref, bc_escrefsize, bc_escsize
        };
        for (int i = 0; i < operand_bands.length; i++) {
            operand_bands[i].readFrom(in);
        }
        bc_escbyte.expectLength(bc_escsize.getIntTotal());
        bc_escbyte.readFrom(in);

        expandByteCodeOps();

        // Done fetching values from operand bands:
        bc_case_count.doneDisbursing();
        for (int i = 0; i < operand_bands.length; i++) {
            operand_bands[i].doneDisbursing();
        }
        bc_escbyte.doneDisbursing();
        bc_bands.doneDisbursing();

        // We must delay the parsing of Code attributes until we
        // have a complete model of bytecodes, for BCI encodings.
        readAttrs(Constants.ATTR_CONTEXT_CODE, codesWithFlags);
        // Ditto for exception handlers in codes.
        fixupCodeHandlers();
        // Now we can finish with class_bands; cf. readClasses().
        code_bands.doneDisbursing();
        class_bands.doneDisbursing();
    }

    private void readByteCodeOps() throws IOException {
        // scratch buffer for collecting code::
        byte[] buf = new byte[1<<12];
        // record of all switch opcodes (these are variable-length)
        List<Integer> allSwitchOps = new ArrayList<>();
        for (int k = 0; k < allCodes.length; k++) {
            Code c = allCodes[k];
        scanOneMethod:
            for (int i = 0; ; i++) {
                int bc = bc_codes.getByte();
                if (i + 10 > buf.length)  buf = realloc(buf);
                buf[i] = (byte)bc;
                boolean isWide = false;
                if (bc == Constants._wide) {
                    bc = bc_codes.getByte();
                    buf[++i] = (byte)bc;
                    isWide = true;
                }
                assert(bc == (0xFF & bc));
                // Adjust expectations of various band sizes.
                switch (bc) {
                case Constants._tableswitch:
                case Constants._lookupswitch:
                    bc_case_count.expectMoreLength(1);
                    allSwitchOps.add(bc);
                    break;
                case Constants._iinc:
                    bc_local.expectMoreLength(1);
                    if (isWide)
                        bc_short.expectMoreLength(1);
                    else
                        bc_byte.expectMoreLength(1);
                    break;
                case Constants._sipush:
                    bc_short.expectMoreLength(1);
                    break;
                case Constants._bipush:
                    bc_byte.expectMoreLength(1);
                    break;
                case Constants._newarray:
                    bc_byte.expectMoreLength(1);
                    break;
                case Constants._multianewarray:
                    assert(getCPRefOpBand(bc) == bc_classref);
                    bc_classref.expectMoreLength(1);
                    bc_byte.expectMoreLength(1);
                    break;
                case Constants._ref_escape:
                    bc_escrefsize.expectMoreLength(1);
                    bc_escref.expectMoreLength(1);
                    break;
                case Constants._byte_escape:
                    bc_escsize.expectMoreLength(1);
                    // bc_escbyte will have to be counted too
                    break;
                default:
                    if (Instruction.isInvokeInitOp(bc)) {
                        bc_initref.expectMoreLength(1);
                        break;
                    }
                    if (Instruction.isSelfLinkerOp(bc)) {
                        CPRefBand bc_which = selfOpRefBand(bc);
                        bc_which.expectMoreLength(1);
                        break;
                    }
                    if (Instruction.isBranchOp(bc)) {
                        bc_label.expectMoreLength(1);
                        break;
                    }
                    if (Instruction.isCPRefOp(bc)) {
                        CPRefBand bc_which = getCPRefOpBand(bc);
                        bc_which.expectMoreLength(1);
                        assert(bc != Constants._multianewarray);  // handled elsewhere
                        break;
                    }
                    if (Instruction.isLocalSlotOp(bc)) {
                        bc_local.expectMoreLength(1);
                        break;
                    }
                    break;
                case Constants._end_marker:
                    {
                        // Transfer from buf to a more permanent place:
                        c.bytes = realloc(buf, i);
                        break scanOneMethod;
                    }
                }
            }
        }

        // To size instruction bands correctly, we need info on switches:
        bc_case_count.readFrom(in);
        for (Integer i : allSwitchOps) {
            int bc = i.intValue();
            int caseCount = bc_case_count.getInt();
            bc_label.expectMoreLength(1+caseCount); // default label + cases
            bc_case_value.expectMoreLength(bc == Constants._tableswitch ? 1 : caseCount);
        }
        bc_case_count.resetForSecondPass();
    }

    private void expandByteCodeOps() throws IOException {
        // scratch buffer for collecting code:
        byte[] buf = new byte[1<<12];
        // scratch buffer for collecting instruction boundaries:
        int[] insnMap = new int[1<<12];
        // list of label carriers, for label decoding post-pass:
        int[] labels = new int[1<<10];
        // scratch buffer for registering CP refs:
        Fixups fixupBuf = new Fixups();

        for (int k = 0; k < allCodes.length; k++) {
            Code code = allCodes[k];
            byte[] codeOps = code.bytes;
            code.bytes = null;  // just for now, while we accumulate bits

            Package.Class curClass = code.thisClass();

            Set<ConstantPool.Entry> ldcRefSet = ldcRefMap.get(curClass);
            if (ldcRefSet == null)
                ldcRefMap.put(curClass, ldcRefSet = new HashSet<>());

            ConstantPool.ClassEntry thisClass  = curClass.thisClass;
            ConstantPool.ClassEntry superClass = curClass.superClass;
            ConstantPool.ClassEntry newClass   = null;  // class of last _new opcode

            int pc = 0;  // fill pointer in buf; actual bytecode PC
            int numInsns = 0;
            int numLabels = 0;
            boolean hasEscs = false;
            fixupBuf.clear();
            for (int i = 0; i < codeOps.length; i++) {
                int bc = Instruction.getByte(codeOps, i);
                int curPC = pc;
                insnMap[numInsns++] = curPC;
                if (pc + 10 > buf.length)  buf = realloc(buf);
                if (numInsns+10 > insnMap.length)  insnMap = realloc(insnMap);
                if (numLabels+10 > labels.length)  labels = realloc(labels);
                boolean isWide = false;
                if (bc == Constants._wide) {
                    buf[pc++] = (byte) bc;
                    bc = Instruction.getByte(codeOps, ++i);
                    isWide = true;
                }
                switch (bc) {
                case Constants._tableswitch: // apc:  (df, lo, hi, (hi-lo+1)*(label))
                case Constants._lookupswitch: // apc:  (df, nc, nc*(case, label))
                    {
                        int caseCount = bc_case_count.getInt();
                        while ((pc + 30 + caseCount*8) > buf.length)
                            buf = realloc(buf);
                        buf[pc++] = (byte) bc;
                        //initialize apc, df, lo, hi bytes to reasonable bits:
                        Arrays.fill(buf, pc, pc+30, (byte)0);
                        Instruction.Switch isw = (Instruction.Switch)
                            Instruction.at(buf, curPC);
                        //isw.setDefaultLabel(getLabel(bc_label, code, curPC));
                        isw.setCaseCount(caseCount);
                        if (bc == Constants._tableswitch) {
                            isw.setCaseValue(0, bc_case_value.getInt());
                        } else {
                            for (int j = 0; j < caseCount; j++) {
                                isw.setCaseValue(j, bc_case_value.getInt());
                            }
                        }
                        // Make our getLabel calls later.
                        labels[numLabels++] = curPC;
                        pc = isw.getNextPC();
                        continue;
                    }
                case Constants._iinc:
                    {
                        buf[pc++] = (byte) bc;
                        int local = bc_local.getInt();
                        int delta;
                        if (isWide) {
                            delta = bc_short.getInt();
                            Instruction.setShort(buf, pc, local); pc += 2;
                            Instruction.setShort(buf, pc, delta); pc += 2;
                        } else {
                            delta = (byte) bc_byte.getByte();
                            buf[pc++] = (byte)local;
                            buf[pc++] = (byte)delta;
                        }
                        continue;
                    }
                case Constants._sipush:
                    {
                        int val = bc_short.getInt();
                        buf[pc++] = (byte) bc;
                        Instruction.setShort(buf, pc, val); pc += 2;
                        continue;
                    }
                case Constants._bipush:
                case Constants._newarray:
                    {
                        int val = bc_byte.getByte();
                        buf[pc++] = (byte) bc;
                        buf[pc++] = (byte) val;
                        continue;
                    }
                case Constants._ref_escape:
                    {
                        // Note that insnMap has one entry for this.
                        hasEscs = true;
                        int size = bc_escrefsize.getInt();
                        ConstantPool.Entry ref = bc_escref.getRef();
                        if (size == 1)  ldcRefSet.add(ref);
                        int fmt;
                        switch (size) {
                        case 1: fixupBuf.addU1(pc, ref); break;
                        case 2: fixupBuf.addU2(pc, ref); break;
                        default: assert(false); fmt = 0;
                        }
                        buf[pc+0] = buf[pc+1] = 0;
                        pc += size;
                    }
                    continue;
                case Constants._byte_escape:
                    {
                        // Note that insnMap has one entry for all these bytes.
                        hasEscs = true;
                        int size = bc_escsize.getInt();
                        while ((pc + size) > buf.length)
                            buf = realloc(buf);
                        while (size-- > 0) {
                            buf[pc++] = (byte) bc_escbyte.getByte();
                        }
                    }
                    continue;
                default:
                    if (Instruction.isInvokeInitOp(bc)) {
                        int idx = (bc - Constants._invokeinit_op);
                        int origBC = Constants._invokespecial;
                        ConstantPool.ClassEntry classRef;
                        switch (idx) {
                        case Constants._invokeinit_self_option:
                            classRef = thisClass; break;
                        case Constants._invokeinit_super_option:
                            classRef = superClass; break;
                        default:
                            assert(idx == Constants._invokeinit_new_option);
                            classRef = newClass; break;
                        }
                        buf[pc++] = (byte) origBC;
                        int coding = bc_initref.getInt();
                        // Find the nth overloading of <init> in classRef.
                        ConstantPool.MemberEntry ref = pkg.cp.getOverloadingForIndex(Constants.CONSTANT_Methodref, classRef, "<init>", coding);
                        fixupBuf.addU2(pc, ref);
                        buf[pc+0] = buf[pc+1] = 0;
                        pc += 2;
                        assert(Instruction.opLength(origBC) == (pc - curPC));
                        continue;
                    }
                    if (Instruction.isSelfLinkerOp(bc)) {
                        int idx = (bc - Constants._self_linker_op);
                        boolean isSuper = (idx >= Constants._self_linker_super_flag);
                        if (isSuper)  idx -= Constants._self_linker_super_flag;
                        boolean isAload = (idx >= Constants._self_linker_aload_flag);
                        if (isAload)  idx -= Constants._self_linker_aload_flag;
                        int origBC = Constants._first_linker_op + idx;
                        boolean isField = Instruction.isFieldOp(origBC);
                        CPRefBand bc_which;
                        ConstantPool.ClassEntry which_cls  = isSuper ? superClass : thisClass;
                        ConstantPool.Index which_ix;
                        if (isField) {
                            bc_which = isSuper ? bc_superfield  : bc_thisfield;
                            which_ix = pkg.cp.getMemberIndex(Constants.CONSTANT_Fieldref, which_cls);
                        } else {
                            bc_which = isSuper ? bc_supermethod : bc_thismethod;
                            which_ix = pkg.cp.getMemberIndex(Constants.CONSTANT_Methodref, which_cls);
                        }
                        assert(bc_which == selfOpRefBand(bc));
                        ConstantPool.MemberEntry ref = (ConstantPool.MemberEntry) bc_which.getRef(which_ix);
                        if (isAload) {
                            buf[pc++] = (byte) Constants._aload_0;
                            curPC = pc;
                            // Note: insnMap keeps the _aload_0 separate.
                            insnMap[numInsns++] = curPC;
                        }
                        buf[pc++] = (byte) origBC;
                        fixupBuf.addU2(pc, ref);
                        buf[pc+0] = buf[pc+1] = 0;
                        pc += 2;
                        assert(Instruction.opLength(origBC) == (pc - curPC));
                        continue;
                    }
                    if (Instruction.isBranchOp(bc)) {
                        buf[pc++] = (byte) bc;
                        assert(!isWide);  // no wide prefix for branches
                        int nextPC = curPC + Instruction.opLength(bc);
                        // Make our getLabel calls later.
                        labels[numLabels++] = curPC;
                        //Instruction.at(buf, curPC).setBranchLabel(getLabel(bc_label, code, curPC));
                        while (pc < nextPC)  buf[pc++] = 0;
                        continue;
                    }
                    if (Instruction.isCPRefOp(bc)) {
                        CPRefBand bc_which = getCPRefOpBand(bc);
                        ConstantPool.Entry ref = bc_which.getRef();
                        if (ref == null) {
                            if (bc_which == bc_classref) {
                                // Shorthand for class self-references.
                                ref = thisClass;
                            } else {
                                assert(false);
                            }
                        }
                        int origBC = bc;
                        int size = 2;
                        switch (bc) {
                        case Constants._invokestatic_int:
                            origBC = Constants._invokestatic;
                            break;
                        case Constants._invokespecial_int:
                            origBC = Constants._invokespecial;
                            break;
                        case Constants._ildc:
                        case Constants._cldc:
                        case Constants._fldc:
                        case Constants._sldc:
                        case Constants._qldc:
                            origBC = Constants._ldc;
                            size = 1;
                            ldcRefSet.add(ref);
                            break;
                        case Constants._ildc_w:
                        case Constants._cldc_w:
                        case Constants._fldc_w:
                        case Constants._sldc_w:
                        case Constants._qldc_w:
                            origBC = Constants._ldc_w;
                            break;
                        case Constants._lldc2_w:
                        case Constants._dldc2_w:
                            origBC = Constants._ldc2_w;
                            break;
                        case Constants._new:
                            newClass = (ConstantPool.ClassEntry) ref;
                            break;
                        }
                        buf[pc++] = (byte) origBC;
                        int fmt;
                        switch (size) {
                        case 1: fixupBuf.addU1(pc, ref); break;
                        case 2: fixupBuf.addU2(pc, ref); break;
                        default: assert(false); fmt = 0;
                        }
                        buf[pc+0] = buf[pc+1] = 0;
                        pc += size;
                        if (origBC == Constants._multianewarray) {
                            // Copy the trailing byte also.
                            int val = bc_byte.getByte();
                            buf[pc++] = (byte) val;
                        } else if (origBC == Constants._invokeinterface) {
                            int argSize = ((ConstantPool.MemberEntry)ref).descRef.typeRef.computeSize(true);
                            buf[pc++] = (byte)( 1 + argSize );
                            buf[pc++] = 0;
                        } else if (origBC == Constants._invokedynamic) {
                            buf[pc++] = 0;
                            buf[pc++] = 0;
                        }
                        assert(Instruction.opLength(origBC) == (pc - curPC));
                        continue;
                    }
                    if (Instruction.isLocalSlotOp(bc)) {
                        buf[pc++] = (byte) bc;
                        int local = bc_local.getInt();
                        if (isWide) {
                            Instruction.setShort(buf, pc, local);
                            pc += 2;
                            if (bc == Constants._iinc) {
                                int iVal = bc_short.getInt();
                                Instruction.setShort(buf, pc, iVal);
                                pc += 2;
                            }
                        } else {
                            Instruction.setByte(buf, pc, local);
                            pc += 1;
                            if (bc == Constants._iinc) {
                                int iVal = bc_byte.getByte();
                                Instruction.setByte(buf, pc, iVal);
                                pc += 1;
                            }
                        }
                        assert(Instruction.opLength(bc) == (pc - curPC));
                        continue;
                    }
                    // Random bytecode.  Just copy it.
                    if (bc >= Constants._bytecode_limit)
                        Utils.log.warning("unrecognized bytescode "+bc
                                            +" "+Instruction.byteName(bc));
                    assert(bc < Constants._bytecode_limit);
                    buf[pc++] = (byte) bc;
                    assert(Instruction.opLength(bc) == (pc - curPC));
                    continue;
                }
            }
            // now make a permanent copy of the bytecodes
            code.setBytes(realloc(buf, pc));
            code.setInstructionMap(insnMap, numInsns);
            // fix up labels, now that code has its insnMap
            Instruction ibr = null;  // temporary branch instruction
            for (int i = 0; i < numLabels; i++) {
                int curPC = labels[i];
                // (Note:  Passing ibr in allows reuse, a speed hack.)
                ibr = Instruction.at(code.bytes, curPC, ibr);
                if (ibr instanceof Instruction.Switch) {
                    Instruction.Switch isw = (Instruction.Switch) ibr;
                    isw.setDefaultLabel(getLabel(bc_label, code, curPC));
                    int caseCount = isw.getCaseCount();
                    for (int j = 0; j < caseCount; j++) {
                        isw.setCaseLabel(j, getLabel(bc_label, code, curPC));
                    }
                } else {
                    ibr.setBranchLabel(getLabel(bc_label, code, curPC));
                }
            }
            if (fixupBuf.size() > 0) {
                if (verbose > 2)
                    Utils.log.fine("Fixups in code: "+fixupBuf);
                code.addFixups(fixupBuf);
            }
        }
    }
}
