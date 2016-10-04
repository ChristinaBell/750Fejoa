/*
 * Copyright 2016.
 * Distributed under the terms of the GPLv3 License.
 *
 * Authors:
 *      Clemens Zeidler <czei002@aucklanduni.ac.nz>
 */
package org.fejoa.library;

import org.fejoa.library.crypto.CryptoException;
import org.fejoa.library.database.IOStorageDir;
import org.fejoa.library.database.StorageDir;

import java.io.IOException;


public class ContactStore extends StorageDirObject {
    private StorageDirList<ContactPublic> contactList;

    protected ContactStore(final FejoaContext context, StorageDir dir) {
        super(context, dir);

        contactList = new StorageDirList<>(storageDir,
                new StorageDirList.IEntryIO<ContactPublic>() {
                    @Override
                    public String getId(ContactPublic entry) {
                        return entry.getId();
                    }

                    @Override
                    public ContactPublic read(IOStorageDir dir) throws IOException {
                        return new ContactPublic(context, dir);
                    }

                    @Override
                    public void write(ContactPublic entry, IOStorageDir dir) throws IOException {

                    }
                });
    }

    public ContactPublic addContact(String id) throws IOException, CryptoException {
        ContactPublic contact = new ContactPublic(context, contactList.getStorageDirForId(id));
        contact.setId(id);
        // contact needs an id before added to the contact list
        contactList.add(contact);
        return contact;
    }

    public StorageDirList<ContactPublic> getContactList() {
        return contactList;
    }

    public IContactFinder<IContactPublic> getContactFinder() {
        return new IContactFinder<IContactPublic>() {
            @Override
            public ContactPublic get(String contactId) {
                return contactList.get(contactId);
            }
        };
    }
}
