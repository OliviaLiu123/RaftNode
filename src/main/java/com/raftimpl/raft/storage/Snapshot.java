package com.raftimpl.raft.storage;

import com.raftimpl.raft.proto.RaftProto;
import com.raftimpl.raft.util.RaftFileUtils;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

//Snapshot 类用于管理 Raft 协议中的快照（snapshot）。快照是在 Raft 协议中用来保存状态机的某个时间点的状态，以减少日志的存储和恢复时间
/*
具体来说，Snapshot 类存储和管理以下信息：

1.快照元数据 (metaData)：

1)包含最后包含的日志索引 (lastIncludedIndex) 和最后包含的日志任期 (lastIncludedTerm)。
2)包含当前配置 (configuration)。
这些信息存储在一个文件中，通常名为 metadata。
2. 快照数据文件：

1)存储状态机在某个时间点的实际状态数据。
2)这些数据文件存储在快照目录中的 data 子目录下。
3)个数据文件使用 SnapshotDataFile 类表示，包含文件名和对应的 RandomAccessFile 对象。

 */
public class Snapshot {

    // 内部类，用于表示快照数据文件
    public class SnapshotDataFile {
        public String fileName;
        public RandomAccessFile randomAccessFile; // 随机访问文件对象
    }
    private static final Logger LOG = LoggerFactory.getLogger(Snapshot.class); // 日志记录器
    private String snapshotDir; // 快照目录

    private RaftProto.SnapshotMetaData metaData; //快照元数据

    // 表示是否正在安装snapshot，leader向follower安装，leader和follower同时处于installSnapshot状态

    private AtomicBoolean isInstallSnapshot = new AtomicBoolean(false);

    // 表示节点自己是否在对状态机做snapshot
    private AtomicBoolean isTakeSnapshot = new AtomicBoolean(false);

    private Lock lock = new ReentrantLock();

    public Snapshot(String raftDataDir){
        this.snapshotDir = raftDataDir+File.separator +"snapshot";
        String snapshotDataDir = snapshotDir + File.separator +"data";
        File file = new File(snapshotDataDir);
        if(!file.exists()){
            file.mkdir();
        }
    }
    // 重新加载快照的元数据
    public void reload() {
        metaData = this.readMetaData();
        if (metaData == null) {
            metaData = RaftProto.SnapshotMetaData.newBuilder().build();
        }
    }


    // 读取快照元数据文件
    public RaftProto.SnapshotMetaData readMetaData() {
        String fileName = snapshotDir + File.separator + "metadata";
        File file = new File(fileName);
        try (RandomAccessFile randomAccessFile = new RandomAccessFile(file, "r")) {
            RaftProto.SnapshotMetaData metadata = RaftFileUtils.readProtoFromFile(
                    randomAccessFile, RaftProto.SnapshotMetaData.class);
            return metadata;
        } catch (IOException ex) {
            LOG.warn("meta file not exist, name={}", fileName);
            return null;
        }
    }

    // 打开快照数据文件，并将其存储在 TreeMap 中
    public TreeMap<String, SnapshotDataFile> openSnapshotDataFiles() {
        TreeMap<String, SnapshotDataFile> snapshotDataFileMap = new TreeMap<>();
        String snapshotDataDir = snapshotDir + File.separator + "data";
        try {
            Path snapshotDataPath = FileSystems.getDefault().getPath(snapshotDataDir);
            snapshotDataPath = snapshotDataPath.toRealPath();
            snapshotDataDir = snapshotDataPath.toString();
            List<String> fileNames = RaftFileUtils.getSortedFilesInDirectory(snapshotDataDir, snapshotDataDir);
            for (String fileName : fileNames) {
                RandomAccessFile randomAccessFile = RaftFileUtils.openFile(snapshotDataDir, fileName, "r");
                SnapshotDataFile snapshotFile = new SnapshotDataFile();
                snapshotFile.fileName = fileName;
                snapshotFile.randomAccessFile = randomAccessFile;
                snapshotDataFileMap.put(fileName, snapshotFile);
            }
        } catch (IOException ex) {
            LOG.warn("readSnapshotDataFiles exception:", ex);
            throw new RuntimeException(ex);
        }
        return snapshotDataFileMap;
    }
    // 关闭所有快照数据文件
    public void closeSnapshotDataFiles(TreeMap<String, SnapshotDataFile> snapshotDataFileMap) {
        for (Map.Entry<String, SnapshotDataFile> entry : snapshotDataFileMap.entrySet()) {
            try {
                entry.getValue().randomAccessFile.close();
            } catch (IOException ex) {
                LOG.warn("close snapshot files exception:", ex);
            }
        }
    }
    // 更新快照的元数据
    public void updateMetaData(String dir,
                               Long lastIncludedIndex,
                               Long lastIncludedTerm,
                               RaftProto.Configuration configuration) {
        RaftProto.SnapshotMetaData snapshotMetaData = RaftProto.SnapshotMetaData.newBuilder()
                .setLastIncludedIndex(lastIncludedIndex)
                .setLastIncludedTerm(lastIncludedTerm)
                .setConfiguration(configuration).build();
        String snapshotMetaFile = dir + File.separator + "metadata";
        RandomAccessFile randomAccessFile = null;
        try {
            File dirFile = new File(dir);
            if (!dirFile.exists()) {
                dirFile.mkdirs();
            }

            File file = new File(snapshotMetaFile);
            if (file.exists()) {
                FileUtils.forceDelete(file);
            }
            file.createNewFile();
            randomAccessFile = new RandomAccessFile(file, "rw");
            RaftFileUtils.writeProtoToFile(randomAccessFile, snapshotMetaData);
        } catch (IOException ex) {
            LOG.warn("meta file not exist, name={}", snapshotMetaFile);
        } finally {
            RaftFileUtils.closeFile(randomAccessFile);
        }
    }

    public RaftProto.SnapshotMetaData getMetaData() {
        return metaData;
    }

    public String getSnapshotDir() {
        return snapshotDir;
    }

    public AtomicBoolean getIsInstallSnapshot() {
        return isInstallSnapshot;
    }

    public AtomicBoolean getIsTakeSnapshot() {
        return isTakeSnapshot;
    }

    public Lock getLock() {
        return lock;
    }


}
