/*
 * Copyright 2016.
 * Distributed under the terms of the GPLv3 License.
 *
 * Authors:
 *      Clemens Zeidler <czei002@aucklanduni.ac.nz>
 */
package org.fejoa.chunkstore.sync;

import org.fejoa.chunkstore.*;
import org.fejoa.library.remote.IRemotePipe;
import org.fejoa.library.support.StreamHelper;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.fejoa.chunkstore.sync.Request.GET_ALL_CHUNKS;
import static org.fejoa.chunkstore.sync.Request.GET_CHUNKS;
import static org.fejoa.chunkstore.sync.Request.OK;


public class PullHandler {
    static public void handleGetChunks(ChunkStore.Transaction chunkStore, IRemotePipe pipe, DataInputStream inputStream)
            throws IOException {
        long nRequestedChunks = inputStream.readLong();
        List<HashValue> requestedChunks = new ArrayList<>();
        for (int i = 0; i < nRequestedChunks; i++) {
            HashValue hashValue = Config.newBoxHash();
            inputStream.readFully(hashValue.getBytes());
            requestedChunks.add(hashValue);
        }

        DataOutputStream outputStream = new DataOutputStream(pipe.getOutputStream());
        Request.writeResponseHeader(outputStream, GET_CHUNKS, OK);

        outputStream.writeLong(requestedChunks.size());
        //TODO: check if we have all chunks before start sending them
        for (HashValue hashValue : requestedChunks) {
            outputStream.write(hashValue.getBytes());
            byte[] chunk = chunkStore.getChunk(hashValue);
            outputStream.writeInt(chunk.length);
            outputStream.write(chunk);
        }
    }

    static public void handleGetAllChunks(ChunkStore.Transaction chunkStore, IRemotePipe pipe)
            throws IOException {
        DataOutputStream outputStream = new DataOutputStream(pipe.getOutputStream());
        Request.writeResponseHeader(outputStream, GET_ALL_CHUNKS, OK);

        outputStream.writeLong(chunkStore.size());
        ChunkStore.ChunkStoreIterator iterator = chunkStore.iterator();
        while (iterator.hasNext()) {
            ChunkStore.Entry entry = iterator.next();
            outputStream.write(entry.key.getBytes());
            outputStream.writeInt(entry.data.length);
            outputStream.write(entry.data);
        }
        iterator.unlock();
    }
}
