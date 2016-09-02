/*
 * Copyright 2016.
 * Distributed under the terms of the GPLv3 License.
 *
 * Authors:
 *      Clemens Zeidler <czei002@aucklanduni.ac.nz>
 */
package org.fejoa.server;

import org.fejoa.chunkstore.*;
import org.fejoa.chunkstore.sync.RequestHandler;
import org.fejoa.library.BranchAccessRight;
import org.fejoa.library.remote.ChunkStorePushJob;
import org.fejoa.library.remote.JsonRPCHandler;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;


public class ChunkStoreRequestHandler extends JsonRequestHandler {
    public ChunkStoreRequestHandler() {
        super(ChunkStorePushJob.METHOD);
    }

    @Override
    public void handle(Portal.ResponseHandler responseHandler, JsonRPCHandler jsonRPCHandler, InputStream data,
                       Session session) throws Exception {
        JSONObject params = jsonRPCHandler.getParams();
        String user = params.getString("serverUser");
        final String branch = params.getString("branch");
        AccessControl accessControl = new AccessControl(session, user);
        ChunkStore chunkStore = accessControl.getChunkStore(branch, BranchAccessRight.PUSH);
        final ChunkStoreBranchLog branchLog = accessControl.getChunkStoreBranchLog(branch, BranchAccessRight.PUSH);

        ChunkStore.Transaction transaction = chunkStore.openTransaction();
        final RequestHandler handler = new RequestHandler(transaction,
                new RequestHandler.IBranchLogGetter() {
                    @Override
                    public ChunkStoreBranchLog get(String b) throws IOException {
                        if (!branch.equals(b))
                            throw new IOException("Branch miss match.");
                        return branchLog;
                    }
                });

        ServerPipe pipe = new ServerPipe(jsonRPCHandler.makeResult(Portal.Errors.OK, "data pipe ok"),
                responseHandler, data);
        handler.handle(pipe);
    }
}
