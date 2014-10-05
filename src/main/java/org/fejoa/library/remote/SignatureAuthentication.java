/*
 * Copyright 2014.
 * Distributed under the terms of the GPLv3 License.
 *
 * Authors:
 *      Clemens Zeidler <czei002@aucklanduni.ac.nz>
 */
package org.fejoa.library.remote;

import org.eclipse.jgit.util.Base64;
import org.fejoa.library.ContactPrivate;
import org.fejoa.library.support.InStanzaHandler;
import org.fejoa.library.support.IqInStanzaHandler;
import org.fejoa.library.support.ProtocolInStream;
import org.fejoa.library.support.ProtocolOutStream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Element;
import org.xml.sax.Attributes;
import rx.Observable;
import rx.Observer;
import rx.Subscription;
import rx.subscriptions.Subscriptions;
import rx.util.functions.Func1;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;


public class SignatureAuthentication implements IAuthenticationRequest {
    final String AUTH_STANZA = "auth";
    final String AUTH_SIGNED_STANZA = "auth_signed";

    final ConnectionInfo connectionInfo;

    private List<String> roles = new ArrayList<>();

    public SignatureAuthentication(ConnectionInfo info) {
        this.connectionInfo = info;
    }

    public Observable<Boolean> auth(final IRemoteRequest remoteRequest) {
        final JSONLoginRequest loginRequest = new JSONLoginRequest();
        final JSONSignIn signIn = new JSONSignIn(loginRequest);
        loginRequest.setFollowUpJob(signIn);

        return Observable.create(new Observable.OnSubscribeFunc<Boolean>() {
            @Override
            public Subscription onSubscribe(Observer<? super Boolean> observer) {
                try {
                    byte[] request = loginRequest.getRequest();
                    byte[] reply = remoteRequest.send(request);
                    if (reply == null)
                        throw new IOException("network error");
                    if (loginRequest.handleResponse(reply).status == RemoteConnectionJob.Result.ERROR)
                        throw new IOException("bad response");
                    request = signIn.getRequest();
                    reply = remoteRequest.send(request);
                    if (reply == null)
                        throw new IOException("network error");
                    boolean done = signIn.handleResponse(reply).status == RemoteConnectionJob.Result.DONE;
                    observer.onNext(done);
                    observer.onCompleted();
                } catch (Exception e) {
                    e.printStackTrace();
                    observer.onError(e);
                    return Subscriptions.empty();
                }
                return Subscriptions.empty();

            }
        });
    }

    class JSONLoginRequest extends JsonRemoteConnectionJob {
        public String signToken = "";
        public int transactionId;

        @Override
        public byte[] getRequest() throws Exception {
            return jsonRPC.call(AUTH_STANZA,
                    new JsonRPC.Argument("type", "signature"),
                    new JsonRPC.Argument("loginUser", connectionInfo.myself.getUid()),
                    new JsonRPC.Argument("serverUser", connectionInfo.serverUser))
                    .getBytes();
        }

        @Override
        public Result handleResponse(byte[] reply) throws Exception {
            JSONObject result = jsonRPC.getReturnValue(new String(reply));
            if (getStatus(result) != 0)
                return new Result(Result.ERROR, getMessage(result));

            transactionId = result.getInt("transactionId");
            signToken = result.getString("signToken");

            return new Result(Result.DONE, getMessage(result));
        }
    }

    class JSONSignIn extends JsonRemoteConnectionJob {
        final private JSONLoginRequest loginRequest;

        public JSONSignIn(JSONLoginRequest loginRequest) {
            this.loginRequest = loginRequest;
        }

        @Override
        public byte[] getRequest() throws Exception {
            ContactPrivate myself = connectionInfo.myself;
            byte signature[] = myself.sign(myself.getMainKeyId(), loginRequest.signToken.getBytes());

            return jsonRPC.call(AUTH_SIGNED_STANZA,
                    new JsonRPC.Argument("transactionId", loginRequest.transactionId),
                    new JsonRPC.Argument("signature", Base64.encodeBytes(signature)),
                    new JsonRPC.Argument("loginUser", connectionInfo.myself.getUid()),
                    new JsonRPC.Argument("serverUser", connectionInfo.serverUser))
                    .getBytes();
        }

        @Override
        public Result handleResponse(byte[] reply) throws Exception {
            JSONObject result = jsonRPC.getReturnValue(new String(reply));
            if (getStatus(result) != 0)
                return new Result(Result.ERROR, getMessage(result));

            if (!result.has("roles"))
                return new Result(Result.ERROR, "no roles");
            JSONArray jsonArray = result.getJSONArray("roles");
            roles.clear();
            for (int i = 0; i < jsonArray.length(); i++)
                roles.add(jsonArray.getString(i));
            if (roles.size() == 0)
                return new RemoteConnectionJob.Result(Result.ERROR, "no roles");

            return new Result(Result.DONE);
        }
    }
}