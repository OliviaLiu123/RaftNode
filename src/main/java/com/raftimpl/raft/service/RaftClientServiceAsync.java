package com.raftimpl.raft.service;


import com.baidu.brpc.client.RpcCallback;
import com.raftimpl.raft.proto.RaftProto;

import java.util.concurrent.Future;
public interface RaftClientServiceAsync {

    Future <RaftProto.GetLeaderResponse> getLeader();
}
