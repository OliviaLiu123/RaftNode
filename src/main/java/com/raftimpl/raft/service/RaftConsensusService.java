package com.raftimpl.raft.service;

import com.raftimpl.raft.proto.RaftProto;


// raft 节点之间相互通信的接口
public interface RaftConsensusService {
  // 在正式的投票之前进行预投票。预投票用于检查是否有可能赢得选票，这样可以避免在网络分区或其他异常情况下无谓地发起投票。
    RaftProto.VoteResponse preVote(RaftProto.VoteRequest request);

    //发起投票请求。一个候选人节点在尝试成为领导者时，会向其他节点请求投票。
    RaftProto.VoteResponse requestVote(RaftProto.VoteRequest request);
//领导者节点使用该方法向跟随者节点追加日志条目。也用作心跳机制，确保跟随者节点保持活动状态并且没有分区。
    RaftProto.AppendEntriesResponse appendEntries(RaftProto.AppendEntriesRequest request);
//在跟随者节点日志条目落后太多时，领导者节点使用该方法安装快照。快照包含了部分或全部的状态机状态，确保跟随者节点能快速追上最新状态。
    RaftProto.InstallSnapshotResponse installSnapshot(RaftProto.InstallSnapshotRequest request);
//获取当前领导者的提交索引值。提交索引表示日志中最新已经被应用到状态机的条目索引。
    RaftProto.GetLeaderCommitIndexResponse getLeaderCommitIndex(RaftProto.GetLeaderCommitIndexRequest request);



}
