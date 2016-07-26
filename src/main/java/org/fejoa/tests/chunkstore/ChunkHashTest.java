/*
 * Copyright 2016.
 * Distributed under the terms of the GPLv3 License.
 *
 * Authors:
 *      Clemens Zeidler <czei002@aucklanduni.ac.nz>
 */
package org.fejoa.tests.chunkstore;

import junit.framework.TestCase;
import org.fejoa.chunkstore.ChunkHash;
import org.fejoa.chunkstore.FixedBlockSplitter;
import org.fejoa.library.crypto.CryptoHelper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;


public class ChunkHashTest extends TestCase {
    public void testSimple() throws NoSuchAlgorithmException {
        MessageDigest messageDigest = CryptoHelper.sha256Hash();
        ChunkHash chunkHash = new ChunkHash(new FixedBlockSplitter(2), new FixedBlockSplitter(64));

        byte[] block1 = "11".getBytes();
        messageDigest.update(block1);
        byte[] hashBlock1 = messageDigest.digest();
        chunkHash.update(block1);
        assertTrue(Arrays.equals(hashBlock1, chunkHash.digest()));

        // second layer
        byte[] block2 = "22".getBytes();
        messageDigest.reset();
        messageDigest.update(block2);
        byte[] hashBlock2 = messageDigest.digest();
        messageDigest.reset();
        messageDigest.update(hashBlock1);
        messageDigest.update(hashBlock2);
        byte[] combinedHashLayer2_0 = messageDigest.digest();

        chunkHash.reset();
        chunkHash.update(block1);
        chunkHash.update(block2);
        assertTrue(Arrays.equals(combinedHashLayer2_0, chunkHash.digest()));

        // third layer
        byte[] block3 = "33".getBytes();
        messageDigest.reset();
        messageDigest.update(block3);
        byte[] hashBlock3 = messageDigest.digest();
        messageDigest.reset();
        messageDigest.update(hashBlock3);
        byte[] combinedHashLayer2_1 = messageDigest.digest();
        messageDigest.reset();
        messageDigest.update(combinedHashLayer2_0);
        messageDigest.update(combinedHashLayer2_1);
        byte[] combinedHashLayer3_0 = messageDigest.digest();

        chunkHash.reset();
        chunkHash.update(block1);
        chunkHash.update(block2);
        chunkHash.update(block3);
        assertTrue(Arrays.equals(combinedHashLayer3_0, chunkHash.digest()));

        // fill third layer
        byte[] block4 = "44".getBytes();
        messageDigest.reset();
        messageDigest.update(block4);
        byte[] hashBlock4 = messageDigest.digest();
        messageDigest.reset();
        messageDigest.update(hashBlock3);
        messageDigest.update(hashBlock4);
        byte[] combinedHashLayer2_2 = messageDigest.digest();
        messageDigest.reset();
        messageDigest.update(combinedHashLayer2_0);
        messageDigest.update(combinedHashLayer2_2);
        byte[] combinedHashLayer3_1 = messageDigest.digest();

        chunkHash.reset();
        chunkHash.update(block1);
        chunkHash.update(block2);
        chunkHash.update(block3);
        chunkHash.update(block4);
        assertTrue(Arrays.equals(combinedHashLayer3_1, chunkHash.digest()));
    }
}