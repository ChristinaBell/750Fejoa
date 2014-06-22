/*
 * Copyright 2014.
 * Distributed under the terms of the GPLv3 License.
 *
 * Authors:
 *      Clemens Zeidler <czei002@aucklanduni.ac.nz>
 */
package org.fejoa.library.remote;

import rx.Observable;
import rx.util.functions.Func1;

import java.util.HashMap;
import java.util.Map;


public class RemoteConnection {
    public IRemoteRequest remoteRequest = null;
    final private Map<String, AuthenticationState> roleMap = new HashMap<>();

    private class AuthenticationState {
        public boolean connected = false;
        public IAuthenticationRequest authenticationRequest = null;

        public AuthenticationState(IAuthenticationRequest request) {
            this.authenticationRequest = request;
        }
    }

    public RemoteConnection(IRemoteRequest remoteRequest) {
        this.remoteRequest = remoteRequest;
    }

    public Observable<byte[]> send(byte data[]) {
        return remoteRequest.send(data);
    }

    public Observable<Boolean> requestRole(String role) {
        if (!roleMap.containsKey(role))
            throw new IllegalArgumentException("unkown role");

        final AuthenticationState state = roleMap.get(role);
        if (state.connected)
            return Observable.just(true);

        return state.authenticationRequest.send(remoteRequest).map(new Func1<Boolean, Boolean>() {
            @Override
            public Boolean call(Boolean connected) {
                state.connected = connected;
                return connected;
            }
        });
    }
}