package com.raftimpl.raft;

import com.baidu.brpc.client.RpcCallback;
import com.raftimpl.raft.proto.RaftProto;
import com.raftimpl.raft.storage.SegmentedLog;
import com.raftimpl.raft.util.ConfigurationUtils;
import com.google.protobuf.ByteString;
import com.raftimpl.raft.storage.Snapshot;
import com.google.protobuf.InvalidProtocolBufferException;
import com.googlecode.protobuf.format.JsonFormat;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;




import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;




public class RaftNode {

    // node state
   public enum NodeState{
       STATE_FOLLOWER,
        STATE_PRE_CANDIDATE,
        STATE_CANDIDATE,
        STATE_LEADER,
    }
   private static final Logger LOG = LoggerFactory.getLogger(RaftProto.class);
   private static final JsonFormat jsonFormat = new JsonFormat();

   private RaftOptions raftOptions;

   private RaftProto.Configuration configuration;
   private ConcurrentMap<Integer, Peer> peerMap = new ConcurrentHashMap<>();

   private RaftProto.Server localServer;

   private StateMachine stateMachine;

    private SegmentedLog raftLog;
    private Snapshot snapshot;

    // 初始化状态是Follower
    private NodeState state = NodeState.STATE_FOLLOWER;

    // 在当前获得选票的候选人id

    private long currentTerm;

    private int votedFor;

    private int leaderId;

    private long commitIndex;// 最后被应用到状态机的日志条目索引值（初始化为0， 持续递增）

    private volatile long lastAppliedIndex; //以确保在多线程环境中对其修改对所有线程可见。
    private Lock lock = new ReentrantLock();
    private Condition commitIndexCondition = lock.newCondition();

    private Condition catchUpCondition = lock.newCondition();

    private ExecutorService executorService;

    private ScheduledExecutorService scheduledExecutorService;

    private ScheduledFuture electionScheduledFuture;

    private ScheduledFuture heartbeatScheduledFuture;


    public RaftNode(RaftOptions raftOptions,
                    List<RaftProto.Server> servers,
                    RaftProto.Server localServer,
                    StateMachine stateMachine){
        // 初始化Raft选项
        this.raftOptions = raftOptions;
        RaftProto.Configuration.Builder confBuilder = RaftProto.Configuration.newBuilder();

        // 添加服务器列表到配置中
        for(RaftProto.Server server : servers){
            confBuilder.addServers(server);

        }

        configuration = confBuilder.build();

        // 设置本地服务器和状态机
        this.localServer = localServer;
        this.stateMachine = stateMachine;

        // 加载日志和快照

        raftLog = new SegmentedLog(raftOptions.getDataDir(),raftOptions.getMaxSegmentFileSize());
        snapshot = new Snapshot(raftOptions.getDataDir());
        snapshot.reload();

       currentTerm = raftLog.getMetaData().getCurrentTerm();
       votedFor = raftLog.getMetaData().getVotedFor();
       commitIndex=Math.max(snapshot.getMetaData().getLastIncludedIndex(),raftLog.getMetaData().getCommitIndex());

       // 丢弃旧 日志条目
        if(snapshot.getMetaData().getLastIncludedIndex() > 0 && raftLog.getFirstLogIndex() <=snapshot.getMetaData().getLastIncludedIndex()){
            raftLog.truncatePrefix(snapshot.getMetaData().getLastIncludedIndex()+1);

        }

        //应用到state machine
        RaftProto.Configuration snapshotConfiguration = snapshot.getMetaData().getConfiguration();
        if(snapshotConfiguration.getServersCount() >0){
            configuration = snapshotConfiguration;
        }
        String snapshotDataDir = snapshot.getSnapshotDir()+File.separator +"data";
        stateMachine.readSnapshot(snapshotDataDir);

        for(long index = snapshot.getMetaData().getLastIncludedIndex()+1; index <=commitIndex; index++){
            RaftProto.LogEntry entry = raftLog.getEntry(index);
            if(entry.getType()==RaftProto.EntryType.ENTRY_TYPE_DATA){
                stateMachine.apply(entry.getData().toByteArray());
            }else if (entry.getType()==RaftProto.EntryType.ENTRY_TYPE_CONFIGURATION){
                applyConfiguration(entry);
            }
        }
        lastAppliedIndex = commitIndex;
    }




    /**
     * 初始化Raft节点，包括加载日志和快照，初始化线程池和定时器
     */

    public void init(){
        for(RaftProto.Server server: configuration.getServersList()){
            if(!peerMap.containsKey(server.getServerId())&& server.getServerId() !=localServer.getServerId()){
                Peer peer = new Peer(server);
                peer.setNextIndex(raftLog.getLastLogIndex()+1);
                peerMap.put(server.getServerId(),peer);
            }
        }
        // init thread pool
        executorService = new ThreadPoolExecutor(raftOptions.getRaftConsensusThreadNum(),raftOptions.getRaftConsensusThreadNum(),60,TimeUnit.SECONDS,new LinkedBlockingQueue<Runnable>());
        scheduledExecutorService = Executors.newScheduledThreadPool(2);

        scheduledExecutorService.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                takeSnapshot();
            }
        },raftOptions.getSnapshotPeriodSeconds(), raftOptions.getSnapshotPeriodSeconds(), TimeUnit.SECONDS);
        //start election
        resetElectionTimer();

    }
    // client set command
    public boolean replicate(byte[] data,RaftProto.EntryType entryType){
        lock.lock();
        long newLastLogIndex = 0;
        try{
            if(state != NodeState.STATE_LEADER){
                LOG.debug("I am not the leader");
                return false;
            }
            RaftProto.LogEntry logEntry = RaftProto.LogEntry.newBuilder()
                    .setTerm(currentTerm)
                    .setType(entryType)
                    .setData(ByteString.copyFrom(data)).build();
            List<RaftProto.LogEntry> entries = new ArrayList<>();
            entries.add(logEntry);
            newLastLogIndex = raftLog.append(entries);
            for(RaftProto.Server server: configuration.getServersList()){
                final Peer peer = peerMap.get(server.getServerId());
                executorService.submit(new Runnable() {
                    @Override
                    public void run() {
                        appendEntries(peer);
                    }
                });
            }
            if(raftOptions.isAsyncWrite()){
                //主节点写成功后就返回
                return true;
            }
            long startTime = System.currentTimeMillis();
            while(lastAppliedIndex < newLastLogIndex){
                if (System.currentTimeMillis()-startTime >= raftOptions.getMaxAwaitTimeout()){
                    break;
                }
                commitIndexCondition.await(raftOptions.getMaxAwaitTimeout(),TimeUnit.MILLISECONDS);
            }

        }catch (Exception ex) {
            ex.printStackTrace();
        } finally {
            lock.unlock();
        }
        LOG.debug("lastAppliedIndex={} newLastLogIndex={}",lastAppliedIndex,newLastLogIndex);
        if(lastAppliedIndex <newLastLogIndex){
            return false;
        }





        return true;
    }

    //处理向指定的 Raft 集群中的某个 Peer（节点）发送 AppendEntries 请求
    public boolean appendEntries(Peer peer){
// 创建 AppendEntriesRequest 请求构建器：

        RaftProto.AppendEntriesRequest.Builder requestBuilder = RaftProto.AppendEntriesRequest.newBuilder();
        long prevLogIndex;
        long numEntries;
        //检查是否需要安装快照：
        /*
        当一个跟随者的 nextIndex 小于领导者的日志的 firstLogIndex 时，需要安装快照。
         */
        boolean isNeedInstallSnapshot = false;
        lock.lock();
        try {
            long firstLogIndex = raftLog.getFirstLogIndex();// 获取领导者的第一个日志条目的索引
            if (peer.getNextIndex() < firstLogIndex) {
                isNeedInstallSnapshot = true;
            }
        } finally {
            lock.unlock();
        }

//如果需要安装快照，则进行安装：

        LOG.debug("is need snapshot={}, peer={}", isNeedInstallSnapshot, peer.getServer().getServerId());
        if (isNeedInstallSnapshot) {
            if (!installSnapshot(peer)) {
                return false;
            }
        }
//获取快照的最后包含的日志条目索引和任期：


        long lastSnapshotIndex;
        long lastSnapshotTerm;
        snapshot.getLock().lock();
        try {
            lastSnapshotIndex = snapshot.getMetaData().getLastIncludedIndex();
            lastSnapshotTerm = snapshot.getMetaData().getLastIncludedTerm();
        } finally {
            snapshot.getLock().unlock();
        }


//构建 AppendEntriesRequest 请求：
        lock.lock();
        try {
            long firstLogIndex = raftLog.getFirstLogIndex();
            Validate.isTrue(peer.getNextIndex() >= firstLogIndex);
            prevLogIndex = peer.getNextIndex() - 1;
            long prevLogTerm;
            if (prevLogIndex == 0) {
                prevLogTerm = 0;
            } else if (prevLogIndex == lastSnapshotIndex) {
                prevLogTerm = lastSnapshotTerm;
            } else {
                prevLogTerm = raftLog.getEntryTerm(prevLogIndex);
            }
            requestBuilder.setServerId(localServer.getServerId());
            requestBuilder.setTerm(currentTerm);
            requestBuilder.setPrevLogTerm(prevLogTerm);
            requestBuilder.setPrevLogIndex(prevLogIndex);
            numEntries = packEntries(peer.getNextIndex(), requestBuilder);
            requestBuilder.setCommitIndex(Math.min(commitIndex, prevLogIndex + numEntries));
        } finally {
            lock.unlock();
        }


        //发送 AppendEntries 请求并处理响应
        RaftProto.AppendEntriesRequest request = requestBuilder.build();
        RaftProto.AppendEntriesResponse response = peer.getRaftConsensusServiceAsync().appendEntries(request);
        //处理响应结果


        lock.lock();
        try {
            if (response == null) {
                LOG.warn("appendEntries with peer[{}:{}] failed", peer.getServer().getEndpoint().getHost(), peer.getServer().getEndpoint().getPort());
                if (!ConfigurationUtils.containsServer(configuration, peer.getServer().getServerId())) {
                    peerMap.remove(peer.getServer().getServerId());
                    peer.getRpcClient().stop();
                }
                return false;
            }
            LOG.info("AppendEntries response[{}] from server {} in term {} (my term is {})", response.getResCode(), peer.getServer().getServerId(), response.getTerm(), currentTerm);

            if (response.getTerm() > currentTerm) {
                stepDown(response.getTerm());
            } else {
                if (response.getResCode() == RaftProto.ResCode.RES_CODE_SUCCESS) {
                    peer.setMatchIndex(prevLogIndex + numEntries);
                    peer.setNextIndex(peer.getMatchIndex() + 1);
                    if (ConfigurationUtils.containsServer(configuration, peer.getServer().getServerId())) {
                        advanceCommitIndex();
                    } else {
                        if (raftLog.getLastLogIndex() - peer.getMatchIndex() <= raftOptions.getCatchupMargin()) {
                            LOG.debug("peer catch up the leader");
                            peer.setCatchUp(true);
                            // signal the caller thread
                            catchUpCondition.signalAll();
                        }
                    }
                } else {
                    peer.setNextIndex(response.getLastLogIndex() + 1);
                }
            }
        } finally {
            lock.unlock();
        }


        return true;
    }

    //将当前节点从领导者或候选者状态降级为跟随者状态，并进行相关的状态更新和处理
    public void stepDown(long newTerm){
        //检查当前任期是否大于新任期
        if (currentTerm > newTerm) {
            LOG.error("can't be happened");
            return;
        }
//更新当前任期和其他元数据
        if (currentTerm < newTerm) {
            currentTerm = newTerm;  // 更新当前任期号
            leaderId = 0;  // 重置领导者 ID，因为此时不再是领导者
            votedFor = 0;  // 重置投票记录，因为新任期需要重新投票
            raftLog.updateMetaData(currentTerm, votedFor, null, null);  // 更新日志元数据
        }

        //将节点状态设置为跟随者
        state = NodeState.STATE_FOLLOWER;
        //停止心跳定时任务
        if (heartbeatScheduledFuture != null && !heartbeatScheduledFuture.isDone()) {
            heartbeatScheduledFuture.cancel(true);
        }
        //重置选举计时器
        resetElectionTimer();

    }
//生成并保存当前节点的快照
    public void takeSnapshot(){
        //检查是否已经在进行快照操作
        if (snapshot.getIsInstallSnapshot().get()) {
            LOG.info("already in install snapshot, ignore take snapshot");
            return;
        }
        snapshot.getIsTakeSnapshot().compareAndSet(false, true);
//初始化本地变量并尝试获取锁
 try{
     long localLastAppliedIndex;
     long lastAppliedTerm = 0;
     RaftProto.Configuration.Builder localConfiguration = RaftProto.Configuration.newBuilder();
     lock.lock();
     try {
         if (raftLog.getTotalSize() < raftOptions.getSnapshotMinLogSize()) {
             return;
         }
         if (lastAppliedIndex <= snapshot.getMetaData().getLastIncludedIndex()) {
             return;
         }
         localLastAppliedIndex = lastAppliedIndex;
         if (lastAppliedIndex >= raftLog.getFirstLogIndex()
                 && lastAppliedIndex <= raftLog.getLastLogIndex()) {
             lastAppliedTerm = raftLog.getEntryTerm(lastAppliedIndex);
         }
         localConfiguration.mergeFrom(configuration);
     } finally {
         lock.unlock();
     }

//创建临时快照目录并更新元数据

     boolean success = false;
     snapshot.getLock().lock();
     try {
         LOG.info("start taking snapshot");
         String tmpSnapshotDir = snapshot.getSnapshotDir() + ".tmp";
         snapshot.updateMetaData(tmpSnapshotDir, localLastAppliedIndex,
                 lastAppliedTerm, localConfiguration.build());
         String tmpSnapshotDataDir = tmpSnapshotDir + File.separator + "data";
         stateMachine.writeSnapshot(snapshot.getSnapshotDir(), tmpSnapshotDataDir, this, localLastAppliedIndex);
         // 重命名临时快照目录为正式快照目录
         try {
             File snapshotDirFile = new File(snapshot.getSnapshotDir());
             if (snapshotDirFile.exists()) {
                 FileUtils.deleteDirectory(snapshotDirFile);
             }
             FileUtils.moveDirectory(new File(tmpSnapshotDir),
                     new File(snapshot.getSnapshotDir()));
             LOG.info("end taking snapshot, result=success");
             success = true;
         } catch (IOException ex) {
             LOG.warn("move direct failed when taking snapshot, msg={}", ex.getMessage());
         }
     } finally {
         snapshot.getLock().unlock();
     }

//如果快照成功，重新加载快照并删除旧的日志条目

     if (success) {
         long lastSnapshotIndex = 0;
         snapshot.getLock().lock();
         try {
             snapshot.reload();
             lastSnapshotIndex = snapshot.getMetaData().getLastIncludedIndex();
         } finally {
             snapshot.getLock().unlock();
         }

         lock.lock();
         try {
             if (lastSnapshotIndex > 0 && raftLog.getFirstLogIndex() <= lastSnapshotIndex) {
                 raftLog.truncatePrefix(lastSnapshotIndex + 1);
             }
         } finally {
             lock.unlock();
         }
     }

 }finally{

//重置快照标志位

     snapshot.getIsTakeSnapshot().compareAndSet(true, false);

 }




    }

//应用新的配置。新的配置通常包含了集群中的服务器列表，当集群成员发生变化时（例如增加或删除节点），该方法会被调用以更新当前节点的配置并同步 peerMap
    public void applyConfiguration(RaftProto.LogEntry entry){
        //解析新配置
        try {
            RaftProto.Configuration newConfiguration = RaftProto.Configuration.parseFrom(entry.getData().toByteArray());
            configuration = newConfiguration;

/*
更新 peerMap：

遍历新的配置中的服务器列表，检查是否有新的服务器需要添加到 peerMap 中。
如果服务器ID不存在于 peerMap 中，并且不是本地服务器，则创建新的 Peer 实例，并设置其 nextIndex 为当前日志的最后一个索引加一，然后将其添加到 peerMap 中。
 */
            for (RaftProto.Server server : newConfiguration.getServersList()) {
                if (!peerMap.containsKey(server.getServerId())
                        && server.getServerId() != localServer.getServerId()) {
                    Peer peer = new Peer(server);
                    peer.setNextIndex(raftLog.getLastLogIndex() + 1);
                    peerMap.put(server.getServerId(), peer);
                }
            }

            //记录新的配置：
            //
            //使用日志记录新的配置和当前的领导者ID。
            LOG.info("new conf is {}, leaderId={}", jsonFormat.printToString(newConfiguration), leaderId);

        }catch (InvalidProtocolBufferException ex) {
            ex.printStackTrace();
        }



    }

//获取当前节点的最后一个日志条目的任期。如果日志为空，则返回快照的最后一个包含的任期
    public long getLastLogTerm(){
        long lastLogIndex = raftLog.getLastLogIndex();
//检查日志是否为空：
//通过比较最后一个日志条目的索引和第一个日志条目的索引，判断日志是否为空。如果 lastLogIndex 大于等于 raftLog.getFirstLogIndex()，说明日志中有条目。
        if (lastLogIndex >= raftLog.getFirstLogIndex()) {
            return raftLog.getEntryTerm(lastLogIndex);
        } else {
            // log为空，lastLogIndex == lastSnapshotIndex
            return snapshot.getMetaData().getLastIncludedTerm();
        }
    }

    //重置选举计时器，以准备下一次的选举超时
    private void resetElectionTimer(){
        //检查并取消现有的选举计时器
        if (electionScheduledFuture != null && !electionScheduledFuture.isDone()) {
            electionScheduledFuture.cancel(true);
        }
//重新安排选举计时器
        electionScheduledFuture = scheduledExecutorService.schedule(new Runnable() {
            @Override
            public void run() {
                startPreVote();
            }
        }, getElectionTimeoutMs(), TimeUnit.MILLISECONDS);


    }

    //生成一个随机选举超时时间，用于设置选举计时器
    private int getElectionTimeoutMs(){
      //获取 ThreadLocalRandom 实例
        ThreadLocalRandom random = ThreadLocalRandom.current();
//计算随机选举超时时间
        int randomElectionTimeout = raftOptions.getElectionTimeoutMilliseconds()
                + random.nextInt(0, raftOptions.getElectionTimeoutMilliseconds());

        LOG.debug("new election time is after {} ms", randomElectionTimeout);
        return randomElectionTimeout;



    }


    /*
     * 服务端发起pre-vote请求。
     * pre-vote/vote是典型的二阶段实现。
     * 作用是防止某一个节点断网后，不断的增加term发起投票；
     * 当该节点网络恢复后，会导致集群其他节点的term增大，导致集群状态变更。
     */

//  initiates a pre-vote request to check if the node can start an election without incrementing the term.
    private void startPreVote(){
        lock.lock();  // 加锁，确保线程安全
        try {
            if (!ConfigurationUtils.containsServer(configuration, localServer.getServerId())) {  // 如果本地节点不在配置中
                resetElectionTimer();  // 重置选举定时器
                return;
            }
            LOG.info("Running pre-vote in term {}", currentTerm);  // 记录当前任期内发起pre-vote
            state = NodeState.STATE_PRE_CANDIDATE;  // 设置节点状态为预候选人
        } finally {
            lock.unlock();  // 释放锁
        }

        for (RaftProto.Server server : configuration.getServersList()) {  // 遍历配置中的所有服务器
            if (server.getServerId() == localServer.getServerId()) {  // 跳过本地服务器
                continue;
            }
            final Peer peer = peerMap.get(server.getServerId());  // 获取对应的peer
            executorService.submit(new Runnable() {  // 提交异步任务
                @Override
                public void run() {
                    preVote(peer);  // 对peer发起pre-vote请求
                }
            });
        }
        resetElectionTimer();  // 重置选举定时器
    }
    /**
     * 服务端发起正式vote，对candidate有效
     */

    private void startVote(){
        lock.lock();  // 加锁，确保线程安全
        try {
            if (!ConfigurationUtils.containsServer(configuration, localServer.getServerId())) {  // 如果本地节点不在配置中
                resetElectionTimer();  // 重置选举定时器
                return;
            }
            currentTerm++;  // 增加当前任期号
            LOG.info("Running for election in term {}", currentTerm);  // 记录当前任期内发起正式投票
            state = NodeState.STATE_CANDIDATE;  // 设置节点状态为候选人
            leaderId = 0;  // 重置leaderId
            votedFor = localServer.getServerId();  // 设置投票给本地服务器
        } finally {
            lock.unlock();
        }

        for (RaftProto.Server server : configuration.getServersList()) {  // 遍历配置中的所有服务器
            if (server.getServerId() == localServer.getServerId()) {  // 跳过本地服务器
                continue;
            }
            final Peer peer = peerMap.get(server.getServerId());  // 获取对应的peer
            executorService.submit(new Runnable() {  // 提交异步任务
                @Override
                public void run() {
                    requestVote(peer);  // 对peer发起正式投票请求
                }
            });
        }


    }
    /**
     * 服务端发起pre-vote请求
     *
     */
    private void preVote(Peer peer) {
        LOG.info("begin pre vote request");  // 记录开始pre-vote请求
        RaftProto.VoteRequest.Builder requestBuilder = RaftProto.VoteRequest.newBuilder();  // 创建VoteRequest构建器
        lock.lock();  // 加锁，确保线程安全
        try {
            peer.setVoteGranted(null);  // 设置peer的投票结果为空
            requestBuilder.setServerId(localServer.getServerId())  // 设置请求中的服务器ID
                    .setTerm(currentTerm)  // 设置请求中的当前任期号
                    .setLastLogIndex(raftLog.getLastLogIndex())  // 设置请求中的最后日志索引
                    .setLastLogTerm(getLastLogTerm());  // 设置请求中的最后日志任期号
        } finally {
            lock.unlock();
        }

        RaftProto.VoteRequest request = requestBuilder.build();  // 构建VoteRequest
        peer.getRaftConsensusServiceAsync().preVote(  // 异步发送pre-vote请求
                request, new PreVoteResponseCallback(peer, request));
    }

    // 服务端发起正式的vote 请求
    private void requestVote(Peer peer){
        LOG.info("begin vote request");  // 记录开始正式投票请求
        RaftProto.VoteRequest.Builder requestBuilder = RaftProto.VoteRequest.newBuilder();  // 创建VoteRequest构建器
        lock.lock();  // 加锁，确保线程安全
        try {
            peer.setVoteGranted(null);  // 设置peer的投票结果为空
            requestBuilder.setServerId(localServer.getServerId())  // 设置请求中的服务器ID
                    .setTerm(currentTerm)  // 设置请求中的当前任期号
                    .setLastLogIndex(raftLog.getLastLogIndex())  // 设置请求中的最后日志索引
                    .setLastLogTerm(getLastLogTerm());  // 设置请求中的最后日志任期号
        } finally {
            lock.unlock();  // 释放锁
        }

        RaftProto.VoteRequest request = requestBuilder.build();  // 构建VoteRequest
        peer.getRaftConsensusServiceAsync().requestVote(  // 异步发送正式投票请求
                request, new VoteResponseCallback(peer, request));
    }

    private class PreVoteResponseCallback implements RpcCallback<RaftProto.VoteResponse>{


        private Peer peer;
        private RaftProto.VoteRequest request;

        public PreVoteResponseCallback(Peer peer, RaftProto.VoteRequest request) {
            this.peer = peer;
            this.request = request;
        }

        @Override
        public void success(RaftProto.VoteResponse response) {
            lock.lock();
            try{
                peer.setVoteGranted(response.getGranted());
                if(currentTerm !=request.getTerm() || state !=NodeState.STATE_CANDIDATE){
                    LOG.info("ignore preVote RPC result");
                    return;
                }
                if(response.getTerm() >currentTerm){
                    LOG.info("Received pre vote response from server{}"+"in term{} (this server's term was)",
                            peer.getServer().getServerId(),response.getTerm(),currentTerm);
                    stepDown(response.getTerm());
                }else{
                    if(response.getGranted()){
                        LOG.info("get pre vote granted from server{} for term{}",peer.getServer().getServerId(),currentTerm);
                        int voteGrantedNum =1;
                        for(RaftProto.Server server: configuration.getServersList()){
                            if(server.getServerId() == localServer.getServerId()){
                                continue;
                            }
                            Peer peer1 = peerMap.get(server.getServerId());
                            if(peer1.getVoteGranted() !=null && peer1.getVoteGranted()==true){
                                voteGrantedNum +=1;
                            }

                        }
                        LOG.info("preVoteGrantedNum={}",voteGrantedNum);
                        if(voteGrantedNum >configuration.getServersCount()/2){
                            LOG.info("get majority pre vote, serverId={} when pre vote, start vote.",localServer.getServerId());
                            startVote();
                        }
                    }else{
                        LOG.info("pre vote denied by server {} with term {}, my term is {}",peer.getServer().getServerId(),response.getTerm(),currentTerm);
                    }
                }
            }finally{
                lock.unlock();
            }

        }

        @Override
        public void fail(Throwable e) {
            LOG.warn("pre vote with peer[{}:{}] failed",peer.getServer().getEndpoint().getHost(),
                    peer.getServer().getEndpoint().getPort());
            peer.setVoteGranted(new Boolean(false));

        }
    }


    public class VoteResponseCallback implements RpcCallback<RaftProto.VoteResponse>{
        private Peer peer;
        private RaftProto.VoteRequest request;

        public VoteResponseCallback(Peer peer, RaftProto.VoteRequest request) {
            this.peer = peer;
            this.request = request;
        }

        @Override
        public void success(RaftProto.VoteResponse response) {
            lock.lock();
            try{
                peer.setVoteGranted(response.getGranted());
                if(currentTerm !=request.getTerm() || state !=NodeState.STATE_CANDIDATE){
                    LOG.info("ignore requestVote RPC result");

                    return;
                }
                if(response.getTerm() > currentTerm){
                    LOG.info("Received RequestVote Response from server{}"+"in term{}(this server's term was {})",peer.getServer().getServerId(),
                            response.getTerm(),currentTerm);
                    stepDown(response.getTerm());

                }else{
                    if(response.getGranted()){
                        LOG.info("Got vote from server{} for term{}",peer.getServer().getServerId(),currentTerm);
                        int voteGrantedNum=0;
                        if (votedFor == localServer.getServerId()) {
                            voteGrantedNum += 1;
                        }
                        for (RaftProto.Server server : configuration.getServersList()) {
                            if (server.getServerId() == localServer.getServerId()) {
                                continue;
                            }
                            Peer peer1 = peerMap.get(server.getServerId());
                            if (peer1.getVoteGranted() != null && peer1.getVoteGranted() == true) {
                                voteGrantedNum += 1;
                            }
                            LOG.info("voteGrantedNum={}", voteGrantedNum);
                            if (voteGrantedNum > configuration.getServersCount() / 2) {
                                LOG.info("Got majority vote, serverId={} become leader", localServer.getServerId());
                                becomeLeader();
                            }
                        }

                    }else {
                        LOG.info("Vote denied by server {} with term {}, my term is {}",
                                peer.getServer().getServerId(), response.getTerm(), currentTerm);
                    }


                }

            }finally{
                lock.unlock();
            }

        }

        @Override
        public void fail(Throwable e) {
            LOG.warn("requestVot with peer[{}:{}] failed ",peer.getServer().getEndpoint().getHost(),peer.getServer().getEndpoint().getPort());
            peer.setVoteGranted((new Boolean(false)));

        }
    }



// in lock
    private void becomeLeader(){
        state = NodeState.STATE_LEADER;
        leaderId = localServer.getServerId();
        // stop vote timer
        if (electionScheduledFuture != null && !electionScheduledFuture.isDone()) {
            electionScheduledFuture.cancel(true);
        }
        // start heartbeat timer
        startNewHeartbeat();
    }


    // heartbeat timer, append entries
    // in lock
    private void resetHeartbeatTimer(){
        if (heartbeatScheduledFuture != null && !heartbeatScheduledFuture.isDone()) {
            heartbeatScheduledFuture.cancel(true);
        }
        heartbeatScheduledFuture = scheduledExecutorService.schedule(new Runnable() {
            @Override
            public void run() {
                startNewHeartbeat();
            }
        }, raftOptions.getHeartbeatPeriodMilliseconds(), TimeUnit.MILLISECONDS);
    }
    // in lock, 开始心跳，对leader有效
    private void startNewHeartbeat(){
        LOG.debug("start new heartbeat, peers={}", peerMap.keySet());
        for (final Peer peer : peerMap.values()) {
            executorService.submit(new Runnable() {
                @Override
                public void run() {
                    appendEntries(peer);
                }
            });
        }
        resetHeartbeatTimer();
    }
    // in lock, for leader
    //更新 Raft 协议中的提交索引 (commitIndex) 并将日志条目应用到状态机中
    private void advanceCommitIndex(){
        //获取集群中每个节点的 matchIndex

        int peerNum = configuration.getServersList().size();
        long[] matchIndexes = new long[peerNum];
        int i = 0;
        for (RaftProto.Server server : configuration.getServersList()) {
            if (server.getServerId() != localServer.getServerId()) {
                Peer peer = peerMap.get(server.getServerId());
                matchIndexes[i++] = peer.getMatchIndex();
            }
        }
        matchIndexes[i] = raftLog.getLastLogIndex();
        // 排序 matchIndexes 并计算新的 commitIndex
        Arrays.sort(matchIndexes);
        long newCommitIndex = matchIndexes[peerNum / 2];
        LOG.debug("newCommitIndex={}, oldCommitIndex={}", newCommitIndex, commitIndex);
//检查新 commitIndex 的任期是否等于当前任期
        if (raftLog.getEntryTerm(newCommitIndex) != currentTerm) {
            LOG.debug("newCommitIndexTerm={}, currentTerm={}", raftLog.getEntryTerm(newCommitIndex), currentTerm);
            return;
        }
//. 更新 commitIndex 并应用日志条目到状态机
        if (commitIndex >= newCommitIndex) {
            return;
        }
        long oldCommitIndex = commitIndex;
        commitIndex = newCommitIndex;
        raftLog.updateMetaData(currentTerm, null, raftLog.getFirstLogIndex(), commitIndex);
//应用日志条目到状态机
        for (long index = oldCommitIndex + 1; index <= newCommitIndex; index++) {
            RaftProto.LogEntry entry = raftLog.getEntry(index);
            if (entry.getType() == RaftProto.EntryType.ENTRY_TYPE_DATA) {
                stateMachine.apply(entry.getData().toByteArray());
            } else if (entry.getType() == RaftProto.EntryType.ENTRY_TYPE_CONFIGURATION) {
                applyConfiguration(entry);
            }
        }
        lastAppliedIndex = commitIndex;
        LOG.debug("commitIndex={} lastAppliedIndex={}", commitIndex, lastAppliedIndex);
        commitIndexCondition.signalAll();


    }


//in lock
    //给定的 nextIndex 位置开始，从日志中获取一定数量的日志条目，打包到 AppendEntriesRequest 请求中，并返回实际打包的日志条目的数量
    private long packEntries(long nextIndex,RaftProto.AppendEntriesRequest.Builder requestBuilder){
        long lastIndex = Math.min(raftLog.getLastLogIndex(),nextIndex+raftOptions.getMaxtLogEntriesPerRequest()-1);
        for(long index=nextIndex; index<=nextIndex;index++ ){
            RaftProto.LogEntry entry = raftLog.getEntry(index);
            requestBuilder.addEntries(entry);
        }
        return lastIndex-nextIndex+1;
    }


  private boolean installSnapshot(Peer peer) {
      if (snapshot.getIsTakeSnapshot().get()) {
          LOG.info("already in take snapshot, please send install snapshot request later");
          return false;
      }
      if (!snapshot.getIsInstallSnapshot().compareAndSet(false, true)) {
          LOG.info("already in install snapshot");
          return false;
      }

      LOG.info("begin send install snapshot request to server={}", peer.getServer().getServerId());
      boolean isSuccess = true;
      TreeMap<String, Snapshot.SnapshotDataFile> snapshotDataFileMap = snapshot.openSnapshotDataFiles();
      LOG.info("total snapshot files={}", snapshotDataFileMap.keySet());
      try {
          boolean isLastRequest = false;
          String lastFileName = null;
          long lastOffset = 0;
          long lastLength = 0;
          while (!isLastRequest) {
              RaftProto.InstallSnapshotRequest request
                      = buildInstallSnapshotRequest(snapshotDataFileMap, lastFileName, lastOffset, lastLength);
              if (request == null) {
                  LOG.warn("snapshot request == null");
                  isSuccess = false;
                  break;
              }
              if (request.getIsLast()) {
                  isLastRequest = true;
              }
              LOG.info("install snapshot request, fileName={}, offset={}, size={}, isFirst={}, isLast={}",
                      request.getFileName(), request.getOffset(), request.getData().toByteArray().length,
                      request.getIsFirst(), request.getIsLast());
              RaftProto.InstallSnapshotResponse response
                      = peer.getRaftConsensusServiceAsync().installSnapshot(request);
              if (response != null && response.getResCode() == RaftProto.ResCode.RES_CODE_SUCCESS) {
                  lastFileName = request.getFileName();
                  lastOffset = request.getOffset();
                  lastLength = request.getData().size();
              } else {
                  isSuccess = false;
                  break;
              }
          }

          if (isSuccess) {
              long lastIncludedIndexInSnapshot;
              snapshot.getLock().lock();
              try {
                  lastIncludedIndexInSnapshot = snapshot.getMetaData().getLastIncludedIndex();
              } finally {
                  snapshot.getLock().unlock();
              }

              lock.lock();
              try {
                  peer.setNextIndex(lastIncludedIndexInSnapshot + 1);
              } finally {
                  lock.unlock();
              }
          }
      } finally {
          snapshot.closeSnapshotDataFiles(snapshotDataFileMap);
          snapshot.getIsInstallSnapshot().compareAndSet(true, false);
      }
      LOG.info("end send install snapshot request to server={}, success={}",
              peer.getServer().getServerId(), isSuccess);
      return isSuccess;
  }


  private RaftProto.InstallSnapshotRequest buildInstallSnapshotRequest(TreeMap<String,Snapshot.SnapshotDataFile> snapshotDataFileMap,String lastFileName
  ,long lastOffset, long lastLength){
      RaftProto.InstallSnapshotRequest.Builder requestBuilder = RaftProto.InstallSnapshotRequest.newBuilder();

      snapshot.getLock().lock();
      try {
          if (lastFileName == null) {
              lastFileName = snapshotDataFileMap.firstKey();
              lastOffset = 0;
              lastLength = 0;
          }
          Snapshot.SnapshotDataFile lastFile = snapshotDataFileMap.get(lastFileName);
          long lastFileLength = lastFile.randomAccessFile.length();
          String currentFileName = lastFileName;
          long currentOffset = lastOffset + lastLength;
          int currentDataSize = raftOptions.getMaxSnapshotBytesPerRequest();
          Snapshot.SnapshotDataFile currentDataFile = lastFile;
          if (lastOffset + lastLength < lastFileLength) {
              if (lastOffset + lastLength + raftOptions.getMaxSnapshotBytesPerRequest() > lastFileLength) {
                  currentDataSize = (int) (lastFileLength - (lastOffset + lastLength));
              }
          } else {
              Map.Entry<String, Snapshot.SnapshotDataFile> currentEntry
                      = snapshotDataFileMap.higherEntry(lastFileName);
              if (currentEntry == null) {
                  LOG.warn("reach the last file={}", lastFileName);
                  return null;
              }
              currentDataFile = currentEntry.getValue();
              currentFileName = currentEntry.getKey();
              currentOffset = 0;
              int currentFileLength = (int) currentEntry.getValue().randomAccessFile.length();
              if (currentFileLength < raftOptions.getMaxSnapshotBytesPerRequest()) {
                  currentDataSize = currentFileLength;
              }
          }
          byte[] currentData = new byte[currentDataSize];
          currentDataFile.randomAccessFile.seek(currentOffset);
          currentDataFile.randomAccessFile.read(currentData);
          requestBuilder.setData(ByteString.copyFrom(currentData));
          requestBuilder.setFileName(currentFileName);
          requestBuilder.setOffset(currentOffset);
          requestBuilder.setIsFirst(false);
          if (currentFileName.equals(snapshotDataFileMap.lastKey())
                  && currentOffset + currentDataSize >= currentDataFile.randomAccessFile.length()) {
              requestBuilder.setIsLast(true);
          } else {
              requestBuilder.setIsLast(false);
          }
          if (currentFileName.equals(snapshotDataFileMap.firstKey()) && currentOffset == 0) {
              requestBuilder.setIsFirst(true);
              requestBuilder.setSnapshotMetaData(snapshot.getMetaData());
          } else {
              requestBuilder.setIsFirst(false);
          }
      } catch (Exception ex) {
          LOG.warn("meet exception:", ex);
          return null;
      } finally {
          snapshot.getLock().unlock();
      }

      lock.lock();
      try {
          requestBuilder.setTerm(currentTerm);
          requestBuilder.setServerId(localServer.getServerId());
      } finally {
          lock.unlock();
      }

      return requestBuilder.build();
  }

//确保当前节点是领导者（Leader），并且等待日志条目被应用到状态机（State Machine
  public boolean waitUntilApplied(){
        final CountDownLatch cdl;
        long readIndex;
        lock.lock();
        try{
            // 记录当前 commitIndex 为 readIndex
            // 创建 CountDownLatch，值为 Peer 节点数的一半（向上取整，加上 Leader 节点本身即可超过半数）
            readIndex = commitIndex;
            int peerNum = configuration.getServersList().size();
            cdl = new CountDownLatch((peerNum+1)>>1);
// 向所有 Follower 节点发送心跳包，如果得到响应就让 CountDownLatch 减一
            LOG.debug("ensure leader, peers={}", peerMap.keySet());
            for (final Peer peer : peerMap.values()) {
                executorService.submit(new Runnable() {
                    @Override
                    public void run() {
                        if (appendEntries(peer)) {  // 如果 appendEntries 成功
                            cdl.countDown();  // 让 CountDownLatch 减一
                        }
                    }
                });
            }

        }finally {
            lock.unlock();
        }

      // 等待 CountDownLatch 减为 0 或超时
      try {
          if (cdl.await(raftOptions.getMaxAwaitTimeout(), TimeUnit.MILLISECONDS)) {  // 等待直到 CountDownLatch 计数为 0 或超时
              lock.lock();
              try {
                  // 如果 CountDownLatch 在超时时间内减为 0，则成功确认当前节点是 Leader 节点，等待 readIndex 之前的日志条目被应用到复制状态机
                  long startTime = System.currentTimeMillis();
                  while (lastAppliedIndex < readIndex
                          && System.currentTimeMillis() - startTime < raftOptions.getMaxAwaitTimeout()) {
                      commitIndexCondition.await(raftOptions.getMaxAwaitTimeout(), TimeUnit.MILLISECONDS);
                  }
                  return lastAppliedIndex >= readIndex;  // 返回日志条目是否已被应用
              } finally {
                  lock.unlock();  // 释放锁
              }
          }
      } catch (InterruptedException ignore) {
      }
      return false;  // 超时或被中断，返回 false
  }

  public boolean waitForLeaderCommitIndex(){
      long readIndex = -1;
      boolean callLeader = false;
      Peer leader = null;

      lock.lock();
      try {
          // 记录commitIndex为readIndex
          // 如果当前节点是Leader节点，则直接获取当前commitIndex，否则通过RPC从Leader节点获取commitIndex
          if (leaderId == localServer.getServerId()) {
              readIndex = commitIndex;
          } else {
              callLeader = true;
              leader = peerMap.get(leaderId);
          }
      } finally {
          lock.unlock();
      }

      if (callLeader && leader != null) {
          RaftProto.GetLeaderCommitIndexRequest request = RaftProto.GetLeaderCommitIndexRequest.newBuilder().build();
          RaftProto.GetLeaderCommitIndexResponse response =
                  leader.getRaftConsensusServiceAsync().getLeaderCommitIndex(request);
          if (response == null) {
              LOG.warn("acquire commit index from leader[{}:{}] failed",
                      leader.getServer().getEndpoint().getHost(),
                      leader.getServer().getEndpoint().getPort());
              return false;
          }
          readIndex = response.getCommitIndex();
      }

      if (readIndex == -1) {
          return false;
      }

      lock.lock();
      try {
          // 等待readIndex之前的日志条目被应用到复制状态机
          long startTime = System.currentTimeMillis();
          while (lastAppliedIndex < readIndex
                  && System.currentTimeMillis() - startTime < raftOptions.getMaxAwaitTimeout()) {
              commitIndexCondition.await(raftOptions.getMaxAwaitTimeout(), TimeUnit.MILLISECONDS);
          }
          return lastAppliedIndex >= readIndex;
      } catch (InterruptedException ignore) {
      } finally {
          lock.unlock();
      }
      return false;
  }














    //getter and setter

    public RaftOptions getRaftOptions() {
        return raftOptions;
    }

    public void setRaftOptions(RaftOptions raftOptions) {
        this.raftOptions = raftOptions;
    }

    public RaftProto.Configuration getConfiguration() {
        return configuration;
    }

    public void setConfiguration(RaftProto.Configuration configuration) {
        this.configuration = configuration;
    }

    public ConcurrentMap<Integer, Peer> getPeerMap() {
        return peerMap;
    }


    public RaftProto.Server getLocalServer() {
        return localServer;
    }



    public StateMachine getStateMachine() {
        return stateMachine;
    }



    public SegmentedLog getRaftLog() {
        return raftLog;
    }



    public Snapshot getSnapshot() {
        return snapshot;
    }


    public NodeState getState() {
        return state;
    }

    public long getCurrentTerm() {
        return currentTerm;
    }



    public int getVotedFor() {
        return votedFor;
    }

    public void setVotedFor(int votedFor) {
        this.votedFor = votedFor;
    }

    public int getLeaderId() {
        return leaderId;
    }

    public void setLeaderId(int leaderId) {
        this.leaderId = leaderId;
    }

    public long getCommitIndex() {
        return commitIndex;
    }

    public void setCommitIndex(long commitIndex) {
        this.commitIndex = commitIndex;
    }

    public long getLastAppliedIndex() {
        return lastAppliedIndex;
    }

    public void setLastAppliedIndex(long lastAppliedIndex) {
        this.lastAppliedIndex = lastAppliedIndex;
    }

    public Lock getLock() {
        return lock;
    }



    public Condition getCommitIndexCondition() {
        return commitIndexCondition;
    }



    public Condition getCatchUpCondition() {
        return catchUpCondition;
    }



    public ExecutorService getExecutorService() {
        return executorService;
    }



    public ScheduledExecutorService getScheduledExecutorService() {
        return scheduledExecutorService;
    }

    public void setScheduledExecutorService(ScheduledExecutorService scheduledExecutorService) {
        this.scheduledExecutorService = scheduledExecutorService;
    }

    public ScheduledFuture getElectionScheduledFuture() {
        return electionScheduledFuture;
    }

    public void setElectionScheduledFuture(ScheduledFuture electionScheduledFuture) {
        this.electionScheduledFuture = electionScheduledFuture;
    }

    public ScheduledFuture getHeartbeatScheduledFuture() {
        return heartbeatScheduledFuture;
    }

    public void setHeartbeatScheduledFuture(ScheduledFuture heartbeatScheduledFuture) {
        this.heartbeatScheduledFuture = heartbeatScheduledFuture;
    }
}
