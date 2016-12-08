/*
 * Copyright 2016.
 * Distributed under the terms of the GPLv3 License.
 *
 * Authors:
 *      Clemens Zeidler <czei002@aucklanduni.ac.nz>
 */
package org.fejoa.gui.javafx;

import java8.util.function.BiConsumer;
import java8.util.function.Consumer;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.geometry.Orientation;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Callback;
import org.fejoa.filestorage.*;
import org.fejoa.gui.IStatusManager;
import org.fejoa.gui.StatusManagerMessenger;
import org.fejoa.library.*;
import org.fejoa.library.command.AccessCommandHandler;
import org.fejoa.library.database.DBObjectList;
import org.fejoa.library.database.DatabaseDiff;
import org.fejoa.library.database.StorageDir;
import org.fejoa.library.support.Task;

import java.io.File;
import java.util.Collection;
import java.util.concurrent.Executor;


class TreeObject {
    private String label = "Loading...";
    final public Object data;

    public TreeObject(Object data) {
        this.data = data;
    }

    @Override
    public String toString() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}

class FileStorageTreeView extends TreeView<TreeObject> {
    final private TreeItem<TreeObject> rootNode;
    private FileStorageManager fileStorageManager;

    public FileStorageTreeView() {
        rootNode = new TreeItem<>(null);
        setRoot(rootNode);
        setShowRoot(false);

        setCellFactory(new Callback<TreeView<TreeObject>, TreeCell<TreeObject>>() {
            @Override
            public TreeCell<TreeObject> call(TreeView<TreeObject> treeObjectTreeView) {
                return new TreeCell<TreeObject>() {
                    @Override
                    protected void updateItem(TreeObject item, boolean empty) {
                        super.updateItem(item, empty);
                        textProperty().unbind();
                        if (empty) {
                            setGraphic(null);
                            setText(null);
                            return;
                        }
                        setText(item.getLabel());
                        if (item.data instanceof ContactStorageList) {
                            setGraphic(new ImageView(Resources.getIcon(Resources.ICON_CONTACT_32)));
                        } else if (item.data instanceof ContactStorageList.Store) {
                            setGraphic(new ImageView(Resources.getIcon(Resources.ICON_REMOTE_32)));
                        } else if (item.data instanceof ContactStorageList.CheckOutEntry) {
                            setGraphic(new ImageView(Resources.getIcon(Resources.ICON_FOLDER_32)));
                        }
                    }
                };
            }
        });
    }

    public void setTo(FileStorageManager fileStorageManager) {
        this.fileStorageManager = fileStorageManager;
        rootNode.getChildren().clear();
        try {
            ContactStorageList.ContactStorage contactStorage = fileStorageManager.getOwnFileStorage();
            TreeItem<TreeObject> ownerTreeItem = new TreeItem<>(new TreeObject(contactStorage));
            ownerTreeItem.setExpanded(true);
            ownerTreeItem.getValue().setLabel("Own Storage");
            rootNode.getChildren().add(ownerTreeItem);
            createStorageTree(ownerTreeItem, contactStorage);
        } catch (Exception e) {

        }

        // contacts
        final TreeItem<TreeObject> contactRootItem = new TreeItem<>(new TreeObject(null));
        contactRootItem.setExpanded(true);
        contactRootItem.getValue().setLabel("Contact Storage");
        rootNode.getChildren().add(contactRootItem);

        final ContactStorageList contactStorageList = fileStorageManager.getContactFileStorageList();
        contactStorageList.getDirContent().get().thenAcceptAsync(new Consumer<Collection<String>>() {
            @Override
            public void accept(Collection<String> strings) {
                for (String contactStorageId : strings) {
                    ContactStorageList.ContactStorage contactStorage = contactStorageList.get(contactStorageId);
                    IContactPublic contact = contactStorage.getContactPublic();
                    Remote remote = contact.getRemotes().getDefault();
                    String remoteString = remote.toAddress();

                    TreeItem<TreeObject> treeItem = new TreeItem<>(new TreeObject(contactStorage));
                    treeItem.setExpanded(true);
                    treeItem.getValue().setLabel(remoteString + " (" + contact.getId() + ")");
                    contactRootItem.getChildren().add(treeItem);
                    createStorageTree(treeItem, contactStorage);
                }
            }
        }, javaFXExecutor);
    }

    final private static Executor javaFXExecutor = new JavaFXScheduler();

    private void createStorageTree(final TreeItem<TreeObject> treeItem,
                                                   final ContactStorageList.ContactStorage contactStorage) {
        final DBObjectList<ContactStorageList.Store> storesList = contactStorage.getStores();
        storesList.getDirContent().get().whenCompleteAsync(new BiConsumer<Collection<String>, Throwable>() {
            @Override
            public void accept(final Collection<String> stores, Throwable throwable) {
                if (stores == null) {
                    TreeItem<TreeObject> parent = treeItem.getParent();
                    if (parent != null)
                        parent.getChildren().remove(treeItem);
                    return;
                }
                for (String store : stores)
                    createStoreItem(treeItem, contactStorage, store);
            }
        }, javaFXExecutor);
    }

    private void createStoreItem(TreeItem<TreeObject> parent,
                                 final ContactStorageList.ContactStorage contactStorage, String storeId) {
        ContactStorageList.Store store = contactStorage.getStores().get(storeId);
        TreeObject storeTreeObject = new TreeObject(store);
        final TreeItem<TreeObject> treeItem = new TreeItem<>(storeTreeObject);
        storeTreeObject.setLabel("Branch: " + storeId);
        treeItem.setExpanded(true);
        parent.getChildren().add(treeItem);

        store.getCheckOutProfiles().thenAcceptAsync(new Consumer<ContactStorageList.CheckOutProfiles>() {
            @Override
            public void accept(ContactStorageList.CheckOutProfiles checkOutProfiles) {
                for (ContactStorageList.CheckOutEntry entry
                        : checkOutProfiles.getCheckOut(fileStorageManager.getProfile()).getCheckOutEntries()) {
                    createCheckoutItem(treeItem, entry);
                }
            }
        }, javaFXExecutor);
    }

    private void createCheckoutItem(final TreeItem<TreeObject> parent, ContactStorageList.CheckOutEntry entry) {
        final TreeItem<TreeObject> treeItem = new TreeItem<>(new TreeObject(entry));
        treeItem.setExpanded(true);
        treeItem.getValue().setLabel("Check out: " + entry.getCheckOutPath());
        parent.getChildren().add(treeItem);
    }

}


class FileStorageInfoView extends VBox {
    final private Label checkOutDirInfo = new Label();
    final private ListView<String> sharedWithList = new ListView<>();

    public FileStorageInfoView() {
        HBox pathLayout = new HBox();
        pathLayout.getChildren().add(new Label("Checkout path:"));
        pathLayout.getChildren().add(checkOutDirInfo);

        getChildren().add(pathLayout);
        getChildren().add(sharedWithList);
    }

    public void setTo(BranchInfo branchInfo, ContactStorageList.CheckOutEntry entry) {
        String checkOutPath = entry.getCheckOutPath();
        checkOutDirInfo.setText(new File(checkOutPath).getAbsolutePath());

        sharedWithList.setVisible(false);
        try {
            Collection<ContactAccess> contactAccessList = branchInfo.getContactAccessList()
                    .getEntries();
            if (contactAccessList.size() > 0)
                sharedWithList.setVisible(true);
            for (ContactAccess contactAccess : contactAccessList) {
                contactAccess.getContact().thenAccept(new Consumer<String>() {
                    @Override
                    public void accept(String s) {
                        sharedWithList.getItems().add(s);
                    }
                });
            }
        } catch (Exception e) {

        }

    }
}

public class FileStorageView extends SplitPane {
    final private Client client;
    final StatusManagerMessenger statusManager;

    final private FileStorageManager fileStorageManager;
    final private StorageDir.IListener listener = new StorageDir.IListener() {
        @Override
        public void onTipChanged(DatabaseDiff diff) {
            update();
        }
    };

    final FileStorageTreeView fileStorageTreeView = new FileStorageTreeView();;
    final StackPane stackPane = new StackPane();

    public FileStorageView(final Client client, IStatusManager statusManager) {
        this.client = client;
        this.statusManager = new StatusManagerMessenger(statusManager);

        fileStorageManager = new FileStorageManager(client);
        fileStorageManager.addAccessGrantedHandler(new AccessCommandHandler.IContextHandler() {
            @Override
            public boolean handle(String senderId, BranchInfo branchInfo) throws Exception {
                IContactPublic contactPublic = client.getUserData().getContactStore().getContactFinder().get(senderId);
                if (contactPublic == null)
                    return false;
                fileStorageManager.addContactStorage(contactPublic, branchInfo, "");
                return true;
            }
        });

        // create layout
        HBox createLayout = new HBox();
        final TextField pathTextArea = new TextField();
        Button createStorageButton = new Button("Create Storage");
        createStorageButton.setOnAction(new EventHandler<ActionEvent>() {
            @Override
            public void handle(ActionEvent actionEvent) {
                try {
                    fileStorageManager.createNewStorage(pathTextArea.getText());
                }  catch (Exception e) {
                    e.printStackTrace();
                    FileStorageView.this.statusManager.error(e);
                }
            }
        });
        createLayout.getChildren().add(pathTextArea);
        createLayout.getChildren().add(createStorageButton);

        // sync layout
        HBox syncLayout = new HBox();
        final Button syncButton = new Button("Sync");
        syncButton.setDisable(true);

        final MenuButton shareButton = new MenuButton("Share");
        shareButton.setDisable(true);

        syncLayout.getChildren().add(syncButton);
        syncLayout.getChildren().add(shareButton);

        fileStorageTreeView.getSelectionModel().selectedItemProperty().addListener(
                new ChangeListener<TreeItem<TreeObject>>() {
            @Override
            public void changed(ObservableValue<? extends TreeItem<TreeObject>> observableValue,
                                TreeItem<TreeObject> oldEntry, TreeItem<TreeObject> newEntry) {
                syncButton.setDisable(true);
                shareButton.setDisable(true);
                setTo(null, null);
                if (newEntry != null) {
                    final ContactStorageList.Store fileStorageEntry = getFileStorageEntry(newEntry);
                    if (fileStorageEntry == null)
                        return;
                    boolean isOwnStorage = fileStorageEntry.getContactStorage().getContactPublic().getId().equals(
                            client.getUserData().getMyself().getId());
                    if (newEntry.getValue().data instanceof ContactStorageList.CheckOutEntry) {
                        final ContactStorageList.CheckOutEntry checkOut
                                = (ContactStorageList.CheckOutEntry)newEntry.getValue().data;
                        syncButton.setDisable(false);
                        try {
                            String remoteId = checkOut.getRemoteIds().get(0);
                            BranchInfo branchInfo = fileStorageEntry.getBranchInfo();
                            setTo(branchInfo, checkOut);
                            Collection<BranchInfo.Location> locationList = branchInfo.getLocationEntries();
                            for (final BranchInfo.Location location : locationList) {
                                if (location.getRemoteId().equals(remoteId)) {
                                    syncButton.setOnAction(new EventHandler<ActionEvent>() {
                                        @Override
                                        public void handle(ActionEvent actionEvent) {
                                        sync(checkOut, location);
                                        }
                                    });
                                    break;
                                }
                            }
                        } catch (Exception e) {

                        }
                    }
                    if (isOwnStorage) {
                        shareButton.setDisable(false);
                        updateShareMenu(shareButton, fileStorageEntry);
                    }
                }
            }
        });

        VBox leftLayoutLayout = new VBox();
        leftLayoutLayout.getChildren().add(createLayout);
        leftLayoutLayout.getChildren().add(fileStorageTreeView);
        VBox.setVgrow(fileStorageTreeView, Priority.ALWAYS);

        VBox rightLayout = new VBox();
        rightLayout.getChildren().add(syncLayout);
        rightLayout.getChildren().add(stackPane);
        VBox.setVgrow(stackPane, Priority.ALWAYS);

        setOrientation(Orientation.HORIZONTAL);
        getItems().addAll(leftLayoutLayout, rightLayout);
        setDividerPosition(0, 0.3);

        client.getUserData().getStorageDir().addListener(listener);
        update();
    }

    private void setTo(BranchInfo branchInfo, ContactStorageList.CheckOutEntry entry) {
        if (entry == null) {
            stackPane.getChildren().clear();
            return;
        }
        FileStorageInfoView infoView = new FileStorageInfoView();
        infoView.setTo(branchInfo, entry);
        stackPane.getChildren().add(infoView);
    }

    private ContactStorageList.Store getFileStorageEntry(TreeItem<TreeObject> storageEntry) {
        ContactStorageList.Store store = null;
        do {
            if (storageEntry.getValue().data instanceof ContactStorageList.Store) {
                store = (ContactStorageList.Store)storageEntry.getValue().data;
                break;
            }
            storageEntry = storageEntry.getParent();
        } while (storageEntry.getParent() != null);
        if (store == null)
            return null;
        return store;
    }

    private void updateShareMenu(MenuButton shareButton, final ContactStorageList.Store entry) {
        shareButton.getItems().clear();
        for (final ContactPublic contactPublic : client.getUserData().getContactStore().getContactList().getEntries()) {
            Remote remote = contactPublic.getRemotes().getDefault();
            String remoteString = remote.getUser() + "@" + remote.getServer();
            MenuItem item = new MenuItem(remoteString + ": " + contactPublic.getId());
            shareButton.getItems().add(item);
            item.setOnAction(new EventHandler<ActionEvent>() {
                @Override
                public void handle(ActionEvent actionEvent) {
                    try {
                        String branch = entry.getBranch();
                        fileStorageManager.grantAccess(branch, BranchAccessRight.PULL_PUSH, contactPublic);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });
        }
    }

    private void update() {
        fileStorageTreeView.setTo(fileStorageManager);
    }

    private void sync(ContactStorageList.CheckOutEntry entry, BranchInfo.Location location) {
        try {
            fileStorageManager.sync(entry, location, true,
                    new Task.IObserver<CheckoutDir.Update, CheckoutDir.Result>() {
                @Override
                public void onProgress(CheckoutDir.Update update) {

                }

                @Override
                public void onResult(CheckoutDir.Result result) {

                }

                @Override
                public void onException(Exception exception) {
                    exception.printStackTrace();
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
