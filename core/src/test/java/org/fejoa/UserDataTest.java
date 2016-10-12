/*
 * Copyright 2015.
 * Distributed under the terms of the GPLv3 License.
 *
 * Authors:
 *      Clemens Zeidler <czei002@aucklanduni.ac.nz>
 */
package org.fejoa;

import junit.framework.TestCase;
import org.fejoa.library.UserDataSettings;
import org.fejoa.library.crypto.CryptoException;
import org.fejoa.library.support.StorageLib;
import org.fejoa.library.FejoaContext;
import org.fejoa.library.Remote;
import org.fejoa.library.UserData;
import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;


public class UserDataTest extends TestCase {
    final List<String> cleanUpDirs = new ArrayList<String>();

    @Override
    public void tearDown() throws Exception {
        super.tearDown();

        for (String dir : cleanUpDirs)
            StorageLib.recursiveDeleteFile(new File(dir));
    }

    public void testUserData() throws IOException, CryptoException, JSONException {
        String dir = "userDataTest";
        cleanUpDirs.add(dir);
        for (String dir1 : cleanUpDirs)
            StorageLib.recursiveDeleteFile(new File(dir1));

        String password = "password";
        String user = "user";
        String server = "localhost";

        FejoaContext context = new FejoaContext(dir);
        UserData userData = UserData.create(context, password);
        Remote remoteRemote = new Remote(user, server);
        userData.getRemoteStore().add(remoteRemote);
        userData.getRemoteStore().setDefault(remoteRemote);

        String defaultSignatureKey = userData.getMyself().getSignatureKeys().getDefault().getId();
        String defaultPublicKey = userData.getMyself().getEncryptionKeys().getDefault().getId();
        userData.commit(true);
        UserDataSettings settings = userData.getSettings();

        // open it again
        context = new FejoaContext(dir);
        userData = UserData.open(context, settings, password);

        assertEquals(defaultSignatureKey, userData.getMyself().getSignatureKeys().getDefault().getId());
        assertEquals(defaultPublicKey, userData.getMyself().getEncryptionKeys().getDefault().getId());
    }
}