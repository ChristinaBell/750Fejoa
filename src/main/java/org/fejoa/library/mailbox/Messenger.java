/*
 * Copyright 2014.
 * Distributed under the terms of the GPLv3 License.
 *
 * Authors:
 *      Clemens Zeidler <czei002@aucklanduni.ac.nz>
 */
package org.fejoa.library.mailbox;

import org.fejoa.library.crypto.CryptoException;

import java.io.IOException;
import java.util.List;


public class Messenger {
    final private Mailbox mailbox;

    public Messenger(Mailbox mailbox) {
        this.mailbox = mailbox;
    }

    public MessageChannel createNewThreadMessage(String subject, List<String> receivers, String body) throws CryptoException, IOException {

        MessageBranchInfo branchInfo = new MessageBranchInfo();
        branchInfo.setSubject(subject);
        for (String receiver : receivers)
            branchInfo.addParticipant(receiver, "");

        Message message = new Message();
        message.setBody(body);

        MessageChannel messageChannel = mailbox.createNewMessageChannel();
        MessageBranch messageBranch = messageChannel.getBranch();
        messageBranch.setMessageBranchInfo(branchInfo);
        messageBranch.addMessage(message);
        messageBranch.commit();

        mailbox.addMessageChannel(messageChannel);
        mailbox.commit();

        return messageChannel;
    }

    public void addMessageToThread(MessageChannel channel, String body) throws IOException, CryptoException {
        Message message = new Message();
        message.setBody(body);

        MessageBranch messageBranch = channel.getBranch();
        messageBranch.addMessage(message);
        messageBranch.commit();

        mailbox.commit();
    }
}
