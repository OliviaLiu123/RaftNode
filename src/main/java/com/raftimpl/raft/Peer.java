package com.raftimpl.raft;

import com.baidu.brpc.client.BrpcProxy;
import com.baidu.brpc.client.RpcClient;
import com.baidu.brpc.client.instance.Endpoint;
import com.raftimpl.raft.proto.RaftProto;
import com.raftimpl.raft.service.RaftConsensusServiceAsync;


public class Peer {

    private RaftProto.Server server;
    private RpcClient rpcClient;

    private RaftConsensusServiceAsync raftConsensusServiceAsync;

    //需要发送给follower 的下一个日志条目的索引值，只对leader 有效

    private long nextIndex;

    //已复制日志的最高索引值

    private long matchIndex;
    private volatile Boolean voteGranted;

    private volatile boolean isCatchUp;

    public Peer(RaftProto.Server server){
        this.server = server;
        this.rpcClient = new RpcClient(new Endpoint(
                server.getEndpoint().getHost(),
                server.getEndpoint().getPort()));

        raftConsensusServiceAsync = BrpcProxy.getProxy(rpcClient,RaftConsensusServiceAsync.class);
        isCatchUp = false;

    }

    public RaftProto.Server getServer() {
        return server;
    }



    public RpcClient getRpcClient() {
        return rpcClient;
    }

    public void setRpcClient(RpcClient rpcClient) {
        this.rpcClient = rpcClient;
    }

    public RaftConsensusServiceAsync getRaftConsensusServiceAsync() {
        return raftConsensusServiceAsync;
    }



    public long getNextIndex() {
        return nextIndex;
    }

    public void setNextIndex(long nextIndex) {
        this.nextIndex = nextIndex;
    }

    public long getMatchIndex() {
        return matchIndex;
    }

    public void setMatchIndex(long matchIndex) {
        this.matchIndex = matchIndex;
    }

    public Boolean getVoteGranted() {
        return voteGranted;
    }

    public void setVoteGranted(Boolean voteGranted) {
        this.voteGranted = voteGranted;
    }

    public boolean isCatchUp() {
        return isCatchUp;
    }

    public void setCatchUp(boolean catchUp) {
        isCatchUp = catchUp;
    }
}
