package com.raftimpl.raft;

import com.raftimpl.raft.RaftNode;

public interface StateMachine {

    /*
    对状态机种数据进行snapshot, 每个节点本地定时调用
    snapshotDir 旧snapshot 目录
    tempSnapshotDataDir 新snapshot 数据目录
    raftNode raft 节点
    咯calL啊身体AppliedIndex 已应用到复制状态机的最大日志条目索引
     */

    void writeSnapshot(String snapshotDir, String tempSnapshotDataDir, RaftNode raftNode, long localLastAppliedIndex);

    /*
    读取snapshot到状态机，节点启动时调用
    snapshotDir snapshot数据目录
     */

    void readSnapshot(String snapshotDir);

    /*
    将数据应用到数据机
    dataBytes 数据二进制
     */

    void  apply(byte[] dataBytes);

    /*
    从状态机读取数据
    dataBytes key的数据二进制
    Value 的数据二进制
     */

    byte[] get(byte[] dataBytes);

}
