/*
 * Copyright 2015.
 * Distributed under the terms of the GPLv3 License.
 *
 * Authors:
 *      Clemens Zeidler <czei002@aucklanduni.ac.nz>
 */
package org.fejoa.library.command;

import org.fejoa.library.*;
import org.json.JSONObject;


public class AccessCommandHandler extends EnvelopeCommandHandler {
    public interface IListener extends IncomingCommandManager.IListener {
        void onAccessGranted(String contactId, AccessTokenContact accessTokenContact);
    }

    final private ContactStore contactStore;
    private IListener listener;

    public AccessCommandHandler(UserData userData) {
        super(userData, AccessCommand.COMMAND_NAME);
        this.contactStore = userData.getContactStore();
    }

    public void setListener(IListener listener) {
        this.listener = listener;
    }

    @Override
    public IListener getListener() {
        return listener;
    }

    @Override
    protected boolean handle(JSONObject command, IncomingCommandManager.HandlerResponse response) throws Exception {
        if (!command.getString(Constants.COMMAND_NAME_KEY).equals(AccessCommand.COMMAND_NAME))
            return false;
        String senderId = command.getString(Constants.SENDER_ID_KEY);

        String branch = command.getString(Constants.BRANCH_KEY);
        SymmetricKeyData keyData = null;
        if (command.has(AccessCommand.BRANCH_KEY_KEY)) {
            keyData = new SymmetricKeyData();
            keyData.fromJson(command.getJSONObject(AccessCommand.BRANCH_KEY_KEY));
        }
        String accessToken = command.getString(AccessCommand.TOKEN_KEY);

        AccessTokenContact accessTokenContact = new AccessTokenContact(context, accessToken);
        ContactPublic sender = contactStore.getContactList().get(senderId);
        sender.getContactBranchList().add(new ContactBranch(branch, keyData, accessTokenContact));

        contactStore.commit();

        if (listener != null)
            listener.onAccessGranted(senderId, accessTokenContact);

        response.setHandled();
        return true;
    }
}