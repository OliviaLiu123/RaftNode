package com.raftimpl.raft.service;
import com.raftimpl.raft.proto.RaftProto;

//raft 集群管理接口

public interface RaftClientService {
    /**
     * 获取raft集群leader节点信息
点
     */
    RaftProto.GetLeaderResponse getLeader(RaftProto.GetLeaderResponse request);
    /**
     * 获取raft集群所有节点信息。
     */
    RaftProto.GetConfigurationResponse getConfiguration(RaftProto.GetConfigurationRequest request);

    /**
     * 向raft集群添加节点。
     */
    RaftProto.AddPeersResponse addPeers(RaftProto.AddPeersRequest request);

    /**
     * 从raft集群删除节点
     */
    RaftProto.RemovePeersResponse removePeers(RaftProto.RemovePeersRequest request);
}




