package com.raftimpl.raft;

import lombok.Getter;
import lombok.Setter;

// raft 配置选项

public class RaftOptions {
    // A follower would become a candidate if it doesn't receive any message
    // from the leader in electionTimeoutMs milliseconds
    private int electionTimeoutMilliseconds = 5000;

    // 领导者以这样的频率发送心跳信息， 尽管没有数据
    private int heartbeatPeriodMilliseconds = 500;
    // snapshot定时器执行间隔
    private int snapshotPeriodSeconds = 3600;
    // log entry大小达到snapshotMinLogSize，才做snapshot
    private int snapshotMinLogSize = 100*1024*1024;

    private int maxSnapshotBytesPerRequest = 500*1024;// 500K

    private int maxtLogEntriesPerRequest = 5000;

    // 单个segment文件大小，默认100m
    private int maxSegmentFileSize = 100*1000*1000;
    // follower与leader差距在catchupMargin，才可以参与选举和提供服务
    private long catchupMargin = 500;

    //replicate 最大等待超时时间， 单位ms

    private long maxAwaitTimeout = 1000;

    //与其他节点进行同步，选主等操作的线程池大小

    private int raftConsensusThreadNum = 20;
    // 是否异步写数据；true表示主节点保存后就返回，然后异步同步给从节点；
    // false表示主节点同步给大多数从节点后才返回。
    private boolean asyncWrite = false;

    //raft 的log 和 snapshot 父目录绝对路径

    private String dataDir = System.getProperty("com.raftimp.raft.data.dir");

    public int getElectionTimeoutMilliseconds() {
        return electionTimeoutMilliseconds;
    }

    public int getHeartbeatPeriodMilliseconds() {
        return heartbeatPeriodMilliseconds;
    }

    public int getSnapshotPeriodSeconds() {
        return snapshotPeriodSeconds;
    }

    public int getSnapshotMinLogSize() {
        return snapshotMinLogSize;
    }

    public int getMaxSnapshotBytesPerRequest() {
        return maxSnapshotBytesPerRequest;
    }

    public int getMaxtLogEntriesPerRequest() {
        return maxtLogEntriesPerRequest;
    }

    public int getMaxSegmentFileSize() {
        return maxSegmentFileSize;
    }

    public long getCatchupMargin() {
        return catchupMargin;
    }

    public long getMaxAwaitTimeout() {
        return maxAwaitTimeout;
    }

    public int getRaftConsensusThreadNum() {
        return raftConsensusThreadNum;
    }

    public boolean isAsyncWrite() {
        return asyncWrite;
    }

    public String getDataDir() {
        return dataDir;
    }
}
