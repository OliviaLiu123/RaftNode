package com.raftimpl.raft.service;
import com.baidu.brpc.client.RpcCallback;
import com.raftimpl.raft.proto.RaftProto;

import java.util.concurrent.Future;

// 用于生成 client 异步调用所需的 proxy


public interface RaftConsensusServiceAsync  extends RaftConsensusService{

    Future<RaftProto.VoteResponse> preVote(RaftProto.VoteRequest request, RpcCallback<RaftProto.VoteResponse> callback);

    Future<RaftProto.VoteResponse> requestVote(
            RaftProto.VoteRequest request,
            RpcCallback<RaftProto.VoteResponse> callback);

    RaftProto.AppendEntriesResponse appendEntries(
            RaftProto.AppendEntriesRequest request);

    Future<RaftProto.InstallSnapshotResponse> installSnapshot(
            RaftProto.InstallSnapshotRequest request,
            RpcCallback<RaftProto.InstallSnapshotResponse> callback);




}
