/*
 * Copyright 2015.
 * Distributed under the terms of the GPLv3 License.
 *
 * Authors:
 *      Clemens Zeidler <czei002@aucklanduni.ac.nz>
 */
package org.fejoa.server;


import org.fejoa.library2.remote.JsonRPCHandler;

import java.io.InputStream;


abstract public class JsonRequestHandler {
    static public class Errors {
        final static public int INVALID_JSON_REQUEST = -1;
        final static public int NO_HANDLER_FOR_REQUEST = -2;
        final static public int EXCEPTION = -3;
    }

    private String method;

    public JsonRequestHandler(String method) {
        this.method = method;
    }

    public String getMethod() {
        return method;
    }

    abstract public void handle(Portal.ResponseHandler responseHandler, JsonRPCHandler jsonRPCHandler,
                                InputStream data, Session session) throws Exception;
}