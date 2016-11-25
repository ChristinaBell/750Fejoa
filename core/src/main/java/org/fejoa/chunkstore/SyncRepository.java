/*
 * Copyright 2016.
 * Distributed under the terms of the GPLv3 License.
 *
 * Authors:
 *      Clemens Zeidler <czei002@aucklanduni.ac.nz>
 */
package org.fejoa.chunkstore;

import org.fejoa.library.database.*;
import org.fejoa.chunkstore.sync.*;
import org.fejoa.library.crypto.CryptoException;
import org.fejoa.library.support.StreamHelper;

import java.io.*;
import java.lang.ref.WeakReference;
import java.nio.file.NoSuchFileException;
import java.util.*;

import static org.fejoa.library.database.IIOSyncDatabase.Mode.READ;
import static org.fejoa.library.database.IIOSyncDatabase.Mode.TRUNCATE;
import static org.fejoa.library.database.IIOSyncDatabase.Mode.WRITE;


class SyncRepository implements ISyncDatabase {
    final private File dir;
    final private String branch;
    final private ChunkStoreBranchLog log;
    private CommitBox headCommit;
    final private ICommitCallback commitCallback;
    final private IRepoChunkAccessors accessors;
    private LogRepoTransaction transaction;
    private TreeAccessor treeAccessor;
    final private CommitCache commitCache;
    final private ChunkSplitter chunkSplitter = new RabinSplitter();

    public SyncRepository(File dir, String branch, IRepoChunkAccessors chunkAccessors, ICommitCallback commitCallback)
            throws IOException, CryptoException {
        this.dir = dir;
        this.branch = branch;
        this.accessors = chunkAccessors;
        this.transaction = new LogRepoTransaction(accessors.startTransaction());
        this.log = getLog(dir, branch);
        this.commitCallback = commitCallback;

        BoxPointer headCommitPointer = null;
        if (log.getLatest() != null)
            headCommitPointer = commitCallback.commitPointerFromLog(log.getLatest().getMessage());
        FlatDirectoryBox root;
        if (headCommitPointer == null) {
            root = FlatDirectoryBox.create();
        } else {
            headCommit = CommitBox.read(transaction.getCommitAccessor(), headCommitPointer);
            root = FlatDirectoryBox.read(transaction.getTreeAccessor(), headCommit.getTree());
        }
        this.treeAccessor = new TreeAccessor(root, transaction, useCompression());
        commitCache = new CommitCache(this);
    }

    public SyncRepository(SyncRepository parent, CommitBox headCommit) throws IOException, CryptoException {
        this(parent.dir, parent.branch, parent.accessors, parent.commitCallback);

        setHeadCommit(headCommit);
    }

    static public ChunkSplitter defaultNodeSplitter(int targetChunkSize) {
        float kFactor = (32f) / (32 * 3 + 8);
        return new RabinSplitter((int)(kFactor * targetChunkSize),
                (int)(kFactor * RabinSplitter.CHUNK_1KB), (int)(kFactor * RabinSplitter.CHUNK_128KB * 5));
    }

    public CommitBox getHeadCommit() {
        return headCommit;
    }

    private void setHeadCommit(CommitBox headCommit) throws IOException, CryptoException {
        this.headCommit = headCommit;
        FlatDirectoryBox root = FlatDirectoryBox.read(transaction.getTreeAccessor(), headCommit.getTree());
        this.treeAccessor = new TreeAccessor(root, transaction, useCompression());
    }

    public String getBranch() {
        return branch;
    }

    static public ChunkStoreBranchLog getLog(File baseDir, String branch) throws IOException {
        return new ChunkStoreBranchLog(new File(getBranchDir(baseDir), branch));
    }

    public IRepoChunkAccessors.ITransaction getCurrentTransaction() {
        return transaction;
    }

    public IRepoChunkAccessors getAccessors() {
        return accessors;
    }

    @Override
    public HashValue getTip() {
        synchronized (this) {
            if (getHeadCommit() == null)
                return Config.newDataHash();
            return getHeadCommit().dataHash();
        }
    }

    private Collection<HashValue> getParents() {
        if (headCommit == null)
            return Collections.emptyList();
        List<HashValue> parents = new ArrayList<>();
        for (BoxPointer parent : headCommit.getParents())
            parents.add(parent.getDataHash());
        return parents;
    }

    public CommitCache getCommitCache() {
        return commitCache;
    }

    @Override
    public HashValue getHash(String path) throws IOException, CryptoException {
        synchronized (this) {
            FlatDirectoryBox.Entry entry = treeAccessor.get(path);
            if (!entry.isFile())
                throw new IOException("Not a file path.");
            FileBox fileBox = (FileBox)entry.getObject();
            if (fileBox == null)
                fileBox = FileBox.read(transaction.getFileAccessor(path), entry.getDataPointer());
            return fileBox.getDataContainer().hash();
        }
    }

    static private File getBranchDir(File dir) {
        return new File(dir, "branches");
    }

    public ICommitCallback getCommitCallback() {
        return commitCallback;
    }

    private FlatDirectoryBox getDirBox(String path) throws IOException {
        try {
            FlatDirectoryBox.Entry entry = treeAccessor.get(path);
            if (entry == null || entry.isFile())
                return null;
            if (entry.getObject() == null) {
                BoxPointer dataPointer = entry.getDataPointer();
                if (dataPointer == null)
                    throw new IOException("Unexpected null data pointer");
                entry.setObject(FlatDirectoryBox.read(transaction.getTreeAccessor(), dataPointer));
            }

            return (FlatDirectoryBox)entry.getObject();
        } catch (CryptoException e) {
            throw new IOException(e.getMessage());
        }
    }

    @Override
    public List<String> listFiles(String path) throws IOException {
        synchronized (this) {
            FlatDirectoryBox directoryBox = getDirBox(path);
            if (directoryBox == null)
                return Collections.emptyList();
            List<String> entries = new ArrayList<>();
            for (FlatDirectoryBox.Entry fileEntry : directoryBox.getFiles())
                entries.add(fileEntry.getName());
            return entries;
        }
    }

    @Override
    public List<String> listDirectories(String path) throws IOException {
        synchronized (this) {
            FlatDirectoryBox directoryBox = getDirBox(path);
            if (directoryBox == null)
                return Collections.emptyList();
            List<String> entries = new ArrayList<>();
            for (FlatDirectoryBox.Entry dirEntry : directoryBox.getDirs())
                entries.add(dirEntry.getName());
            return entries;
        }
    }

    @Override
    public boolean hasFile(String path) throws IOException, CryptoException {
        synchronized (this) {
            return treeAccessor.hasFile(path);
        }
    }

    @Override
    public byte[] readBytes(String path) throws IOException, CryptoException {
        ISyncRandomDataAccess randomDataAccess = open(path, READ);
        byte[] date = StreamHelper.readAll(randomDataAccess);
        randomDataAccess.close();
        return date;
    }

    @Override
    public void putBytes(String path, byte[] bytes) throws IOException, CryptoException {
        ISyncRandomDataAccess randomDataAccess = open(path, TRUNCATE);
        randomDataAccess.write(bytes);
        randomDataAccess.close();
    }

    private BoxPointer flush() throws IOException, CryptoException {
        List<String> paths = new ArrayList<>(openHandles.keySet());
        for (String path : paths) {
            for (ChunkContainerRandomDataAccess randomDataAccess : getOpenHandles(path)) {
                if (!randomDataAccess.getMode().has(WRITE))
                    continue;
                randomDataAccess.flush();
                treeAccessor.put(path, FileBox.create(randomDataAccess.getChunkContainer()));
            }
        }
        return treeAccessor.build();
    }

    final private Map<String, List<WeakReference<ChunkContainerRandomDataAccess>>> openHandles = new HashMap<>();

    private void registerHandle(String path, ChunkContainerRandomDataAccess handle) {
        List<WeakReference<ChunkContainerRandomDataAccess>> list = openHandles.get(path);
        if (list == null) {
            list = new ArrayList<>();
            openHandles.put(path, list);
        }
        list.add(new WeakReference<>(handle));
    }

    private void unregisterHandel(String path, ChunkContainerRandomDataAccess randomDataAccess) {
        List<WeakReference<ChunkContainerRandomDataAccess>> list = openHandles.get(path);
        if (list == null)
            return;
        Iterator<WeakReference<ChunkContainerRandomDataAccess>> it = list.iterator();
        while (it.hasNext()) {
            WeakReference<ChunkContainerRandomDataAccess> weakReference = it.next();
            if (weakReference.get() == randomDataAccess) {
                it.remove();
                break;
            }
        }
    }

    private List<ChunkContainerRandomDataAccess> getOpenHandles(String path) {
        List<WeakReference<ChunkContainerRandomDataAccess>> list = openHandles.get(path);
        if (list == null)
            return Collections.emptyList();

        List<WeakReference<ChunkContainerRandomDataAccess>> toRemove = new ArrayList<>();
        List<ChunkContainerRandomDataAccess> refs = new ArrayList<>();
        for (WeakReference<ChunkContainerRandomDataAccess> entry : list) {
            ChunkContainerRandomDataAccess randomDataAccess = entry.get();
            if (randomDataAccess == null) {
                toRemove.add(entry);
                continue;
            }
            refs.add(randomDataAccess);
        }
        for (WeakReference<ChunkContainerRandomDataAccess> entry : toRemove)
            list.remove(entry);
        if (list.size() == 0)
            openHandles.remove(path);
        return refs;
    }

    private ChunkContainer findOpenChunkContainer(String path) {
        List<ChunkContainerRandomDataAccess> refs = getOpenHandles(path);
        if (refs.size() == 0)
            return null;
        return refs.get(0).getChunkContainer();
    }

    private ChunkContainerRandomDataAccess.IIOCallback createIOCallback(final String path) {
        return new ChunkContainerRandomDataAccess.IIOCallback() {
            private void flushOngoingWrites(ChunkContainerRandomDataAccess veto) throws IOException {
                List<ChunkContainerRandomDataAccess> openHandles = getOpenHandles(path);
                for (ChunkContainerRandomDataAccess randomDataAccess : openHandles) {
                    if (randomDataAccess == veto)
                        continue;
                    if (!randomDataAccess.getMode().has(WRITE))
                        continue;
                    randomDataAccess.flush();
                }
            }

            @Override
            public void requestRead(ChunkContainerRandomDataAccess caller) throws IOException {
                flushOngoingWrites(caller);
            }

            @Override
            public void requestWrite(ChunkContainerRandomDataAccess caller) throws IOException {
                flushOngoingWrites(caller);
            }

            @Override
            public void onClosed(ChunkContainerRandomDataAccess caller) throws IOException, CryptoException {
                FileBox file = FileBox.create(caller.getChunkContainer());
                treeAccessor.put(path, file);
                unregisterHandel(path, caller);
            }
        };
    }

    private ChunkContainerRandomDataAccess createNewHandle(String path, Mode openFlags) {
        ChunkContainer chunkContainer = new ChunkContainer(transaction.getFileAccessor(path),
                defaultNodeSplitter(RabinSplitter.CHUNK_8KB));
        chunkContainer.setZLibCompression(useCompression());
        ChunkContainerRandomDataAccess randomDataAccess = new ChunkContainerRandomDataAccess(chunkContainer,
                openFlags, createIOCallback(path));
        registerHandle(path, randomDataAccess);
        return randomDataAccess;
    }

    // TODO: remove when ChunkContainer supports truncate
    private void swapChunkContainer(String path, ChunkContainer chunkContainer) {
        for (ChunkContainerRandomDataAccess randomDataAccess : getOpenHandles(path))
            randomDataAccess.setChunkContainer(chunkContainer);
    }

    @Override
    public ISyncRandomDataAccess open(String path, Mode openFlags) throws IOException, CryptoException {
        synchronized (this) {
            // hacky way to do truncate, when ChunkContainer supports truncate this should be done on the chunk
            // container
            if (openFlags.has(TRUNCATE)) {
                ChunkContainerRandomDataAccess randomDataAccess = createNewHandle(path, openFlags);
                swapChunkContainer(path, randomDataAccess.getChunkContainer());
                return randomDataAccess;
            }

            ChunkContainer chunkContainer = findOpenChunkContainer(path);
            if (chunkContainer != null) {
                ChunkContainerRandomDataAccess randomDataAccess = new ChunkContainerRandomDataAccess(chunkContainer,
                        openFlags, createIOCallback(path));
                registerHandle(path, randomDataAccess);
                return randomDataAccess;
            }

            try {
                FileBox fileBox = treeAccessor.getFileBox(path);
                chunkContainer = fileBox.getDataContainer();
                ChunkContainerRandomDataAccess randomDataAccess = new ChunkContainerRandomDataAccess(chunkContainer,
                        openFlags, createIOCallback(path));
                registerHandle(path, randomDataAccess);
                return randomDataAccess;
            } catch (NoSuchFileException e) {
                if (!openFlags.has(WRITE))
                    throw e;
                return createNewHandle(path, openFlags);
            }
        }
    }

    @Override
    public void remove(String path) throws IOException, CryptoException {
        synchronized (this) {
            treeAccessor.remove(path);
        }
    }

    public boolean useCompression() {
        return true;
    }

    private FileBox writeToFileBox(String path, byte[] data) throws IOException {
        ChunkContainer chunkContainer = new ChunkContainer(transaction.getFileAccessor(path),
                defaultNodeSplitter(RabinSplitter.CHUNK_8KB));
        chunkContainer.setZLibCompression(useCompression());

        FileBox file = FileBox.create(chunkContainer);
        ChunkContainerOutputStream containerOutputStream = new ChunkContainerOutputStream(chunkContainer,
                chunkSplitter);
        containerOutputStream.write(data);
        containerOutputStream.flush();
        return file;
    }

    static public BoxPointer put(TypedBlob blob, IChunkAccessor accessor, boolean compress) throws IOException,
            CryptoException {
        ChunkSplitter nodeSplitter = SyncRepository.defaultNodeSplitter(RabinSplitter.CHUNK_8KB);
        ChunkContainer chunkContainer = new ChunkContainer(accessor, nodeSplitter);
        chunkContainer.setZLibCompression(compress);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        blob.write(new DataOutputStream(outputStream));
        chunkContainer.append(new DataChunk(outputStream.toByteArray()));
        chunkContainer.flush(false);

        return chunkContainer.getBoxPointer();
    }

    private void copyMissingCommits(CommitBox commitBox,
                                    IRepoChunkAccessors.ITransaction source,
                                    IRepoChunkAccessors.ITransaction target)
            throws IOException, CryptoException {
        // TODO: test
        // copy to their transaction
        ChunkStore.Transaction targetTransaction = target.getRawAccessor();
        if (targetTransaction.contains(commitBox.getBoxPointer().getBoxHash()))
            return;
        for (BoxPointer parent : commitBox.getParents()) {
            CommitBox parentCommit = CommitBox.read(source.getCommitAccessor(), parent);
            copyMissingCommits(parentCommit, source, target);
        }

        ChunkFetcher chunkFetcher = ChunkFetcher.createLocalFetcher(targetTransaction, source.getRawAccessor());
        chunkFetcher.enqueueGetCommitJob(target, commitBox.getBoxPointer());
        chunkFetcher.fetch();
    }

    public MergeResult merge(IRepoChunkAccessors.ITransaction otherTransaction, CommitBox otherBranch)
            throws IOException, CryptoException {
        // TODO: check if the transaction is valid, i.e. contains object compatible with otherBranch?
        // TODO: verify commits

        // 1) Find common ancestor
        // 2) Pull missing objects into the other transaction
        // 3) Merge head with otherBranch and commit the other transaction
        synchronized (SyncRepository.this) {
            assert otherBranch != null;
            if (treeAccessor.isModified()) {
                if (headCommit != null) {
                    // do deeper check if data has been changed
                    BoxPointer boxPointer = headCommit.getTree();
                    BoxPointer afterBuild = flush();
                    if (!boxPointer.equals(afterBuild))
                        return MergeResult.UNCOMMITTED_CHANGES;
                } else {
                    return MergeResult.UNCOMMITTED_CHANGES;
                }
            }

            if (headCommit == null) {
                // we are empty just use the other branch
                otherTransaction.finishTransaction();
                headCommit = otherBranch;

                transaction.finishTransaction();
                transaction = new LogRepoTransaction(accessors.startTransaction());
                log.add(commitCallback.logHash(headCommit.getBoxPointer()),
                        commitCallback.commitPointerToLog(headCommit.getBoxPointer()), transaction.getObjectsWritten());
                treeAccessor = new TreeAccessor(FlatDirectoryBox.read(transaction.getTreeAccessor(),
                        otherBranch.getTree()), transaction, useCompression());
                return MergeResult.FAST_FORWARD;
            }
            if (headCommit.dataHash().equals(otherBranch.dataHash()))
                return MergeResult.FAST_FORWARD;
            if (commitCache.isParent(headCommit.dataHash(), otherBranch.dataHash()))
                return MergeResult.FAST_FORWARD;

            CommonAncestorsFinder.Chains chains = CommonAncestorsFinder.find(transaction.getCommitAccessor(),
                    headCommit, otherTransaction.getCommitAccessor(), otherBranch);
            copyMissingCommits(headCommit, transaction, otherTransaction);

            CommonAncestorsFinder.SingleCommitChain shortestChain = chains.getShortestChain();
            if (shortestChain == null)
                throw new IOException("Branches don't have common ancestor.");
            if (shortestChain.getOldest().dataHash().equals(headCommit.dataHash())) {
                // no local commits: just use the remote head
                otherTransaction.finishTransaction();
                headCommit = otherBranch;

                transaction.finishTransaction();
                transaction = new LogRepoTransaction(accessors.startTransaction());
                log.add(commitCallback.logHash(headCommit.getBoxPointer()),
                        commitCallback.commitPointerToLog(headCommit.getBoxPointer()), transaction.getObjectsWritten());
                treeAccessor = new TreeAccessor(FlatDirectoryBox.read(transaction.getTreeAccessor(),
                        otherBranch.getTree()), transaction, useCompression());
                return MergeResult.FAST_FORWARD;
            }

            // merge branches
            treeAccessor = ThreeWayMerge.merge(transaction, transaction, headCommit, otherTransaction,
                    otherBranch, shortestChain.getOldest(), ThreeWayMerge.ourSolver(), useCompression());
            return MergeResult.MERGED;
        }
    }

    public HashValue commit(ICommitSignature commitSignature) throws IOException, CryptoException {
        return commit("Repo commit", commitSignature);
    }

    private boolean needCommit() {
        return treeAccessor.isModified();
    }

    @Override
    public HashValue commit(String message, ICommitSignature commitSignature) throws IOException, CryptoException {
        synchronized (this) {
            commitInternal(message, commitSignature);
            if (headCommit == null)
                return null;
            return headCommit.dataHash();
        }
    }

    public BoxPointer commitInternal(String message, ICommitSignature commitSignature) throws IOException,
            CryptoException {
        return commitInternal(message, commitSignature, Collections.<BoxPointer>emptyList());
    }

    public BoxPointer commitInternal(String message, ICommitSignature commitSignature,
                                     Collection<BoxPointer> mergeParents) throws IOException,
            CryptoException {
        synchronized (this) {
            if (mergeParents.size() == 0 && !needCommit())
                return null;
            BoxPointer rootTree = flush();
            if (mergeParents.size() == 0 && headCommit != null && headCommit.getTree().equals(rootTree))
                return null;
            CommitBox commitBox = CommitBox.create();
            commitBox.setTree(rootTree);
            if (headCommit != null)
                commitBox.addParent(headCommit.getBoxPointer());
            for (BoxPointer mergeParent : mergeParents)
                commitBox.addParent(mergeParent);
            if (commitSignature != null)
                message = commitSignature.signMessage(message, rootTree.getDataHash(), getParents());
            commitBox.setCommitMessage(message.getBytes());
            BoxPointer commitPointer = put(commitBox, transaction.getCommitAccessor(), useCompression());
            commitBox.setBoxPointer(commitPointer);
            headCommit = commitBox;

            transaction.finishTransaction();
            log.add(commitCallback.logHash(commitPointer), commitCallback.commitPointerToLog(commitPointer),
                    transaction.getObjectsWritten());

            transaction = new LogRepoTransaction(accessors.startTransaction());
            this.treeAccessor.setTransaction(transaction);

            return commitPointer;
        }
    }

    public ChunkStoreBranchLog getBranchLog() throws IOException {
        return log;
    }

    @Override
    public DatabaseDiff getDiff(HashValue baseCommitHash, HashValue endCommitHash) throws IOException, CryptoException {
        synchronized (this) {
            CommitBox baseCommit = commitCache.getCommit(baseCommitHash);
            CommitBox endCommit = commitCache.getCommit(endCommitHash);

            DatabaseDiff databaseDiff = new DatabaseDiff(baseCommitHash, endCommitHash);

            IChunkAccessor treeAccessor = transaction.getTreeAccessor();
            TreeIterator diffIterator = new TreeIterator(treeAccessor, baseCommit, treeAccessor, endCommit);
            while (diffIterator.hasNext()) {
                DiffIterator.Change<FlatDirectoryBox.Entry> change = diffIterator.next();
                switch (change.type) {
                    case MODIFIED:
                        databaseDiff.modified.addPath(change.path);
                        break;

                    case ADDED:
                        databaseDiff.added.addPath(change.path);
                        break;

                    case REMOVED:
                        databaseDiff.removed.addPath(change.path);
                        break;
                }
            }

            return databaseDiff;
        }
    }
}
