# my New project raftNode 

 这个 project是实现Raft 协议中的RaftNode 类。
 包含的属性：
 1. NodeState:Enum 表示节点在Raft 协议中的状态
 2. LOG : logger 日志记录器，用来记录日志信息
 3. jsonFormat : JsonFormat JSON 格式化工具，用于格式化日志的配置和信息
 4. raftOptions :RaftOptions raft协议的选项配置
 5. configuration : RaftProto.Configuration  集群配置，包括所有服务器的信息
 6. peerMap : ConcurrentMap<Integer, Peer> 用于存储每个节点的Peer 对象
 7. localServer: RaftProto.Server 本地服务器信息
 8. stateMachie : StateMachine 状态机，用于应用于日志条目
 9. raftLog : SegmentedLog 分段日志，用于存储Raft 日志
 10. snapshot : Snapshot 快照，用于存储raft状态机的快照
 11. state: NodeState 当前阶段状态，初始化为Follower
 12. currentTerm :long 当前任期号，初始化为0 ，持续递增
 13. votedFor: int 当前获得选票候选人的id
 14. leaderId: int 当前领导者id
 15. commitIndex :long 已知的最大的已经提交的日志条目索引值
 16. lastAppliedIndex: long 最后被应用到状态机的日志条目索引值，初始化为0，持续递增
 17. lock:Lock 重入锁，用于在多线程环境中包含共享资源
 18. commitindexCondition: Condition 条件变量，用于在提交索引变化时通知等待的线程
 19. catchUpCodition: Condition 条件变量 用于在追赶进度时通知等待的线程
 20. executorService :ExecutorService 执行服务，用于并发执行任务
 21. scheduledExecutorService: ScheduledExecutorService 调度执行服务，用于调度定时任务
 22. electionScheduledFuture : ScheduledFuture 调度的选举任务
 23. heartbeatScheduledFuture: ScheduledFuture 调度的心跳任务


     除了getter and setter 之外的方法：
     1. 构造器，初始化一个RaftNode 实例，在构造器中要执行以下操作：
        a. 初始化Raft配置选项：将传入的”raftOptions" 参数赋值给类的成员变量raftOptions
        b. 构建Raft配置， 首先创建RaftProto.Configuration.Builder 实例，并将所有的服务器都添加到配置中，最终生成“configuration"对象
        c. 设置本地服务器和状态机
        d. 加载日志和快照数据：初始化 “SegmentedLog" 和"Snapshot" 实例，并重新加载快照数据
        e. 初始化当前任期和投票状态： 从日志元数据中获取并设置当前任期”currentTerm" 和投票状态"votedFor"
        f. 初始化提交索引：根据快照元数据和日志元数据 设置“commitIndex"
        g. 丢弃旧的日志条目： 如果快照包含和最后一个索引大于0 且日志的第一个索引小于等于该索引，就丢弃旧的日志条目。
        h. 应用快照中的配置： 如果快照配置不为空，则将其应用到当前配置
        i. 读取并应用快照数据到状态机
        j. 将日志应用到状态机：遍历并应用从快照的最后一个索引到提交索引之间的所有日志条目
        k. 更新最后应用索引：将commitIndex 赋值给lastAppliedIndex，确保状态机与提交索引一致
     2.public void init() : 该方法为了初始化Raft 节点， 包括加载日志和快照，初始化线程池和定时器。 首先初始化Peer 列表，如果peerMap中不包含该服务器，并且该服务器不是本地服务器，则创建一个新的Peer 对象，将其下一个日志索引设置为日志的最后一个索引加一，然后添加到peerMap中。
然后初始化线程池， 创建一个ThreadPoolExecutor 实例， 用于处理并发任务。 线程池的大小由 raftOptions.getRaftConsensusThreadNum() 决定，线程空闲时间为60s, 使用"LinkedBlockingQueue" 作为任务队列。然后， 初始化定时任务线程池， 创建一个”SlcheduledExecutorService" 实例用于管理定时服务。接下来就是安排一个定时任务， 该任务会定期调用“takeSnapshot" 方法进行快照操作。执行周期由 ”raftOptions.getSnapshotPeriodSeconds()"来决定。最后启动 选举定时器。

3.  public boolean relicate(Byte[] data, RaftProto.EntryTpe entryType) : 这个方法的目的是将数据作为日志条目复制到Raft集群中，并确保这些日志条目被提交并应用到状态机 成功后返回true,否则false。 首先，检查节点状态是否为领导者，如果不是领导者直接返回false. 若是，创建日志条目 （包含当前任期，条目类型和数据），将新创建的条目放到日志条目列表中。 将日志条目追加到日志文件中，并获取新的最后一个日志索引。然后遍历集群中的所有服务器，为每一个服务器创建一个任务，通过线程池异步执行“appendEntries”方法，将日志条目发送给集群中的其他服务器。如果配置为异步写入，则在领导者节点写入成功后立即返回“true",不等待日志条目被提交。如果不是异步写入，则等待日志条目被提交，通过条件变量”commitIndexCondition"等待直到lastAppliedIndex 大于或者等于“newLastLogIndex" 或达到超时时间。最后检查日志是否成功提交，检查”lastAppliedIndex" 是否大于或等于“newLastLogIndex”,如果是表明成功提交 返回true, 否则返回“false"

4. public boolean appendEntries(Peer peer) : 这个方法是将日志条目从领导者复制到跟随者，并处理响应结果。 首先，创建”AppendEntriesRequest" 请求构造器，初始化请求构造器和变量，用于构建“AppendEntriesRequest" 请求。 然后检查是否需要安装快照，当一个跟随者的‘nextIndex"小于领导者日志的”firstLogIndex" 时，需要安装快照。
如果需要安装快照，则进行安装，调用“installSnapshot"方法。并在安装失败时返回false. 获取快照的最后一个日志条目的索引和任期。然后根据跟随者的”nextIndex"构建”AppendEntriesRequest“ 请求，包括设置服务器ID,当前任期，前一个日志条目的索引和任期，以及要发送的日志的条目和提交索引。 然后将构建好的请求发送给跟随者，并获取响应。 处理响应，根据响应结果更新跟随者的“matchIndex" 和nextIndex ,如果需要，调用”stepdown" 或”advanceCommitIndex"方法。

5. public void stepDown(long newTerm) : 这个方法的目的是在节点发现自己的任期小于其他节点时，将自身降级为跟随者。 首先，检查当前任期是否大于新任期，如果当前任期大于新任期，记录错误日志并返回。 如果当前日期小于新任期，更新当前任期号，重置领导者ID 和投票记录，并更新日志元数据。然后将节点设置为跟随者，以停止当前节点的领导者行为。
如果当前节点正在发送心跳消息，取消心跳定时任务，以停止领导者的心跳行为。最后重置选举计时器，以便在适当的时候重新发起选举。

6.public void takeSnapshot(): 目的是在日志文件达到一定大小或者其他条件满足时，生成系统状态的快照，以减少日志条目和加快恢复速度。首先，检查是否已经在进行快照操作，确保不会同时多个快照操作，如果已经在进行快照操作，则记录日志并返回。然后初始化快照所需的变量并检查是否满足快照条件（如日志文件大小是否达到最小快照大小），如果不满足条件则返回。 再者，创建临时快照目录，更新元数据，并将状态机的数据写入快照，然后重命名临时快照目录为正式快照目录。如果快照成功，重新加载快照并删除旧的快照日志条目，以减少日志文件的大小。无论快照操作是否成功， 最总都要重置快照标志位，以允许下一次快照操作。



7.public void applyConfiguration(RaftProto.LogEntry entry): 这个方法的目的是应用新的Raft 配置，该配置可能包含新的集群成员。首先，从传入的日志条目中解析新配置，并将其设置为当前配置。然后遍历新的配置中的服务器列表，检查是否有新的服务器需要添加到“peerMap" 中，如果服务器id 不存在于‘peerMap'中，并且不是本地服务器，则创建新的Peer 实例，设置其’nextIndex'为当前日志的最后一个索引加一，然后将其添加到peerMap 中。 然后使用日志记录新的配置和当前的领导者id,以便于后续调试和问题追踪。 捕获并处理解析配置时可能出现的异常，确保程序的健壮性。 


8.public long getLastLogTerm(): 这个方法是为了获取最后一个日志条目的任期。如果日志为空，则返回快照中最后一个包含的日志条目任期。


9.private void resetElectionTimer(): 这个方法是为了重置选举计时器，以准备下一次的选举超时。 首先，检查并取消现有的选举计时器，然后使用”scheduledExecutorService" 重新安排选举计时器，在选举超时时间到达后，执行”startPrevote()" 方法，开始预投票阶段。


10.private int getElectionTimoutMs(): 这个方法是为了计算一个随机的选举超时时间，以防止多个节点同时发起选举，导致冲突。 首先获取“ThreadLocalRandom" 来生成一个随机数，以确保在多线程环境中具有更好的性能。然后基于配置中的选举超时时间，添加一个0 到选举超时时间之间的随机值，计算出一个随机的选举超时时间，并且使用日志记录新的选举超时时间，以便调试和监控。


11.private void startPreVote(): 这个方法的是发起一个预投票请求，以检查节点是否可以在不增加任期的情况下开始选举。 首先为了线程安全，加锁，防止多个线程同时修改节点状态。然后检查本地节点是否在配置中，如果本地节点不在配置中，重置选举定时器并返回，否则，记录当前任期内发起预投票，并将节点状态设置为预候选人。
遍历配置中的所有服务器，跳过本地服务器。对于每一个服务器，获取对应的peer， 并提交一个异步任务，发起投票请求。最后重置选举定时器。

12. private void startVote(): 这个方法是发起一个正式的投票，以尝试成为集群的领导者。首先加锁，确保线程安全。然后检查本地节点是否在配置中，如果本地节点不在配置中，重置选举定时器并返回。否则，增加当前任期，记录当前任期内发起正式投票，并将节点状态设置为候选人。重置leaderId 并设置投票给本地服务器。 遍历配置中的所有服务器，跳过本地服务器，对于每个服务器，获取对应的peer 并提交一个异步任务发起正式投票请求。

13. private void preVote(Peer peer): 这个方法的目的是向peer 发起预投票请求，以检查是否可以在不增加任期的情况下开始选举。 首先，记录日志，表示开始预投票请求。然后创建一个‘voteRequest'的构造器用于构建预投票请求。加锁确保线程安全。然后设置peer 的投票结果为空，初始化预投票请求的相关参数，包含服务器id,当前任期号，最后日志索引和最后日志任期号。 然后根据构建器构建预投票请求对象。最后异步发送投票请求到peer,并使用PreVoteResponseCallback 处理响应。  


14. private void requestVote(Peer peer):目的是向指向的 peer 节点发起正式投票请求，以尝试成为集群中的领导者。 首先，记录日志，表示正式投票请求，然后创建一个VoteRequest 构建器，用于构建正式投票请求。加锁，确保线程安全，设置peer 投票结果为空，初始化正式投票请求的相关参数，包括服务器id,当前任期号，最后日志索引，最后日志任期号，然后根据构建器，构建正式投票请求对象，异步发送正式投票请求到peer, 并使用PreVoteResponseCallback 处理响应。VoteResponseCallback 类会处理投票请求的响应，判断是否成功获得投票。


15. PreVoteResponseCallback 类 ： 这个类是用于处理pre-vote 请求响应的回调类，该类实现了RpcCallback<RaftProto.VoteResponse> 接口，处理pre-vote 请求成功或者失败的情况。 成员变量有： peer 表示发送请求的目标节点， request ,表示pre vote 请求。success 方法 处理成功响应 ，获得响应后，加锁确保线程安全，设置peer 的投票结果，检查当前任期和状态，如果不匹配则忽略响应，如果响应的任期大于当前任期，则调用stepDown 的方法降级，如果获得投票则统计投票数，如果获得多数投票则发起正式选举投票。
fail 方法，处理失败响应。记录日志，标记pre-vote 请求失败，设置peer 的投票结果为false.

16. VoteResponseCallback 类 :  这个类用于处理正式选取的投票请求响应，该类实现了RpcCallback<RaftProto.VoteResponse> 接口，处理投票请求的成功和失败情况。
成员变量有： peer 表示发送请求的目标节点， request ,表示pre vote 请求。success 方法 处理成功响应 ，获得响应后，加锁确保线程安全，设置peer 的投票结果，检查当前任期和状态，如果不匹配则忽略响应，如果响应的任期大于当前任期，则调用stepDown 的方法降级，如果获得投票则统计投票数，如果获得多数投票则成为leader。fail 方法，处理失败响应。记录日志，标记pre-vote 请求失败，设置peer 的投票结果为false.


17.  private void becomeLeader():这个方法用于将当前节点状态设置为领导者，并初始化相关的心跳机制和选举计时器。该方法在节点成功赢得选举成为leader 时调用。
首先，设置节点状态为LEADER. 然后设置leaderId 为本地服务器ID。 然后检查是否存在未完成的选举计时器任务，如果存在则取消该任务，以停止选举计时器。最后调用”startNewHeartbeat"的方法，启动新的心跳计时器，以确保领导者定期发送心跳包给其他节点，维护集群稳定性。

18.  private void resetHearbeatTimer():这个方法用于重置心跳计时器，该方法在需要重新启动心跳机制时调用。以确保领导者节点定期发送心跳消息给其他节点，首先，如果当前存在未完成的心跳计时器任务，则取消该任务。这一步确保在重新启动心跳计时器之前，旧的心跳计时器已经被清理。然后使用“scheduledExecutorService 安排一个新的心跳计时器任务，这个任务将在指定的心跳周期（由 raftOptions.getHerbeatPeriodMillseconds() 返回的值决定）后执行。 任务内容是调用startNewHearbeat方法，启动新的心跳

19.  private void startNewHeartbeat(): 用于启动新的心跳机制。首先，记录新的心跳的日志信息，这个日志信息包括了所有参与心跳的节点（PeerMap 的key）然后对peerMap中所有节点（即集群中所有跟随者节点）进行遍历。对于每个节点，提交一个异步任务到executorService ，该任务会调用一个appendEntries 方法，向对应的跟随者发送日志追加请求。使用executorService.summit 方法将Runnable 任务提交到线程池中进行异步执行，确保心跳包发送不会阻塞主线程。最后重置心跳计时器。


20. private void advanceCommitInde(): 该方法用于推荐日志的提交索引（commitIndex）通过计算新的提交索引，并将其应用到状态机中，从而确保日志条目被正确地提交和应用。首先，获取集群中所有节点的matchIndex (匹配的日志索引)，将所有从节点的mathIndex 存入数组matchIndexes， 最后一项为领导者节点的最后日志索引。 然后对macthIndexes 进行排序，以便找到中位数索引。通过排序后的matchindex 数组的中位数，计算新的提交索引 newCommitIndex.然后检查新的提交索引是否在当前任期内，如果新的提交索引条目不在当前任期内，则返回，不进行提交。如果新的条目索引大于当前提交索引，则更新提交索引并更新日志元数据。最后遍历从旧的提交索引到新的提交索引之间的所有日志条目，将其应用到状态集中。更新最后应用索引lastAppliedIndex, 记录调试日志，并唤醒所有等待提交索引条件的线程。

21. private long packEntries(long nextIndex, RaftProto.AppendEntriesRequest.Builder requestBuilder) ： 这个方法主要的功能是 将日志条目从nextIndex 开始打包到AppendEntriesRequest请求中，并返回时间打包日志条目数量。（是领导者节点向跟随者节点发送日志复制请求的一部分）。首先，计算lastindex,通过取日志的最后一个索引和一次请求的最大日志条目数量的最小值，确保请求中不包含过多的日志条目,也不会超过一次请求的最大日志条目的数量限制。 然后从nextindex 开始，逐个获取日志条目将其添加到requestBuilder 中直到lastIndex.

22. priavte boolean installSnapshot(Peer peer): 这个方法用于 跟随者节点条目太落后时，领导者节点向跟随者节点安装快照，以确保跟随者节点可以快速追上更新状态。首先，检查是否已经进行快照操作，如果是，返回false。 使用原子操作compareAndSet 确保不会同时进行多个快照安装操作。 然后初始化成功标志isSuccess 为true, 打开快照数据文件并记录总文件数。然后通过buildInstallSnapshotRequest 方法逐个构建并发送快照安装请求，判断请求是否成功，记录最后一个文件名，偏移量和数据长度。 如果快照安装成功，获取快照中最后包含的日志条目索引，并更新跟随者节点的nextIndex. 最后无论快照安装是否成功，关闭快照数据文件，并重置快照标志物。

23. private RaftProto.InstallSnapshotRequest buildInstallSnapshotRequest(TreeMap<String, Snapshot.SnapshotDataFile> snapshotDataFileMap, String lastFileName, long lastOffset, long lastlength): 这个方法用于构建一个快照安装请求，以便在日志条目落后太多的时候，领导者节点向跟随者发送部分或全部的快照数据。首先，创建一个installSnapshotRequest 的构建器，然后获取快照数据文件的锁 ，以确保线程安全,并且设置初始文件和偏移量。如果lastFileName 是null,则初始化为快照数据文件的第一个文件，将偏移量和长度设置为0。 然后读取快照数据并构建请求。计算当前文件名，偏移量和数据大小。 如果当前偏移量和长度没有超过文件长度，则计算当前数据大校如果超过文件长度，则处理跨文件读取，并更新当前文件名，偏移量和数据大小。 然后设置请求的开始和结束标志。如果是第一个文件的第一个偏移量，则设置请求为第一个数据块，并包含快照元数据。 然后获取当前任期和服务器ID, 并设置到请求构建器中。 最后构建并返回InstallSnapshotRequest
24. public boolean waitUntilApplied(): 这个方法是用于确保当前节点是领导者，并等待指定的日志条目（”readIndx"）之前的所有日志条目都被应用到状态机。这是通过发送心跳包到所有跟随者节点。并等待足够数量的响应来实现的。 首先。记录当前的commitIndex 为readIndex, 然后创建一个 CountDownLatch 其值为跟随者节点数的一半（加上领导者节点本身即可超过半数）然后遍历所有跟随者节点，并通过线程池异步发送心跳消息。如果成功收到响应则，CountDownLatch 计数减一。 然后等待CountDownLatch 减为0 或达到超时时间，如果在超时时间内CountDownLatch 减为0， 表示当前节点成功确认是领导者节点。最后等待readIndex 之前的节点被应用到状态机。

25. public boolean waitForLeaderCommitIndex(): 这个方法用于确保当前节点获取最新的“commitIndex" 无论从自身还是通过RPC 从领导者节点获取。然后，它等待直到commitIndex 之前的所有日志条目都被应用到状态机。 首先，记录commitIndex 为readIndex 。并判断是否需要通过RPC 获取领导者的commitIndex.如果需要通过RPC 获取领导者的commitIndex. 然后 检查readIndex 是否有效，如果有效 等待readIndex 之前的日志条目被应用到状态机。
     
     

# 线程和锁
线程和锁的使用是为了确保在多线程的环境中访问和修改共享资源时的一致性和安全性。
在这个类中使用了“ReentrantLock"和”Condition“ 来实现同步和线程间的通信。
- Lock 是 RentrantLock 的一个实例，用于确保在同一时间只有一个线程可以执行受保护的代码块，从而避免数据竞争和不一致
- commitIndexCondition 和catchUpCodition 两个条件变量，用于线程间的通信，等待待定条件满足后唤醒等待的线程

# Proto
这个Proto 文件定义了用于实现Raft 一致性算法的消息结构。以下是每个消息的功能和它们在协议中的作用

枚举类型
1. ResCode 响应码 ：
    - RES_CODE_SUCCESS  ：操作成功时返回
    - RES_CODE_FAIL: 操作失败时返回
    - RES_CODE_NOT_LEADER：操作失败，因为该服务器不是当前领导者
 2.EntryType 日志条目类型
    - ENTRY_TYPE_DATA  : 常规数据条目
    - ENTRY_TYPE_CONFIGURATION :表示包含配置信息日志条目
消息
1.Endpoint 端点：包含服务器主机和端口信息
2.Server 服务器：包含服务器ID 以及其对应的端点
3.Configuration: 包含当前配置中的服务器列表
4.LogMetaData 日志元数据：
  - current_term : 当前任期号
  - voted_for: 投票给
  - first_log_index: 第一个日志索引
  - commit_index:提交索引
5.SnapshotMetaData（快照元数据）：
 -last_included_index（包含的最后一个索引）：快照中包含的最后一个日志条目的索引。
 -last_included_term（包含的最后一个任期）：快照中包含的最后一个日志条目的任期。
 -configuration（配置）：快照时的集群配置。
6.LogEntry（日志条目）：
 -term（任期）：领导者接收到该条目时的任期。
 -index（索引）：日志条目的索引。
 -type（类型）：日志条目的类型（数据或配置）。
 -data（数据）：日志条目中包含的实际数据。
7.VoteRequest（投票请求）：
-server_id（服务器ID）：请求投票的候选人的ID。
-term（任期）：候选人的任期号。
-last_log_term（最后日志任期）：候选人最后日志条目的任期号。
-last_log_index（最后日志索引）：候选人最后日志条目的索引值。
8.VoteResponse（投票响应）：
-term（任期）：响应投票请求的服务器的当前任期号。
-granted（授予）：表示候选人是否赢得了此张选票。
9.AppendEntriesRequest（追加条目请求）：
-server_id（服务器ID）：领导者的ID。
-term（任期）：领导者的任期号。
-prev_log_index（前一个日志索引）：新的日志条目紧随的前一个日志索引。
-prev_log_term（前一个日志任期）：前一个日志条目的任期号。
-commit_index（提交索引）：领导者已经提交的日志的索引值。
-entries（条目）：准备存储的日志条目（如果是心跳消息则为空）。

10.AppendEntriesResponse（追加条目响应）：

-res_code（响应码）：表示跟随者是否包含了匹配上 prev_log_index 和 prev_log_term 的日志条目。
-term（任期）：当前的任期号，用于领导者更新自己。
-last_log_index（最后日志索引）：最后一个日志条目的索引。

11.InstallSnapshotRequest（安装快照请求）：
  -server_id（服务器ID）：请求的服务器ID。
  -term（任期）：任期号。
  -snapshot_meta_data（快照元数据）：包含快照的元数据。
  -file_name（文件名）：快照文件名。
  -offset（偏移量）：快照数据的偏移量。
  -data（数据）：快照的数据块。
  -is_first（是否为第一个块）：是否为第一个数据块。
  -is_last（是否为最后一个块）：是否为最后一个数据块。
12. InstallSnapshotResponse（安装快照响应）：
  -res_code（响应码）：响应码。
  -term（任期）：当前的任期号。
13.GetLeaderRequest（获取领导者请求）：无具体字段，表示请求领导者信息。
14.GetLeaderResponse（获取领导者响应）：
  -res_code（响应码）：响应码。
  -res_msg（响应消息）：响应消息。
  -leader（领导者）：领导者的端点信息。
15. AddPeersRequest（添加节点请求）：
   -servers（服务器）：要添加的服务器列表。
16. AddPeersResponse（添加节点响应）：
   -res_code（响应码）：响应码。
   -res_msg（响应消息）：响应消息。
17. RemovePeersRequest（移除节点请求）：
- servers（服务器）：要移除的服务器列表。
18. RemovePeersResponse（移除节点响应）：
  -res_code（响应码）：响应码。
  -res_msg（响应消息）：响应消息。
19. GetConfigurationRequest（获取配置请求）：无具体字段，表示请求集群配置信息。
20 GetConfigurationResponse（获取配置响应）：
   -res_code（响应码）：响应码。
   -res_msg（响应消息）：响应消息。
   -leader（领导者）：领导者服务器信息。
   -servers（服务器）：集群中的服务器列表。
21 GetLeaderCommitIndexRequest（获取领导者提交索引请求）：无具体字段，表示请求领导者的提交索引。
22.GetLeaderCommitIndexResponse（获取领导者提交索引响应）：
  -commit_index（提交索引）：领导者的提交索引。
# Service Package

 Impl package

 RaftClientServiceImpl Class 
 这个类实现了RaftClientService 接口。其主要作用是提供与Raft节点交互的客户端服务，包括获取领导者，获取集群配置，添加节点和移除节点。
 包含的属性：
 1. LOG: Logger 日志记录器
 2. jsonFormat:JsonFormat 用于将protobuf 消息格式化为json 字符串
 3. raftNode:RaftNode 当前raft节点
    除了构造函数外实现的方法：
    1. getLeader(RaftProto.GetLeaderRequest request) : 这个方法是获取集群领导者的请求。 首先，记录收到请求日志， 创建响应对象并设置默认成功响应码。然后获取节点锁，确保线程安全。再获取当前领导者ID, 如果没有领导者或者领导者id 为0 则设置响应码为失败。如果当前节点是领导者，设置响应中的领导者信息为本地服务器信息。如何当前节点不是领导者，则从配置中查找领导者并设置其信息。 最后释放锁，设置响应的领导者信息并构建响应对象，记录响应日志并返回响应
    
    2，getConfiguration(RaftProto.GetConfigurationRequest request)
处理获取当前集群配置的请求。
逻辑：
创建响应对象并设置默认成功响应码。
获取 Raft 节点的锁，确保线程安全。
获取当前的集群配置。
从配置中获取领导者的信息并设置到响应中。
将配置中的所有服务器信息添加到响应中。
释放锁。
构建响应对象。
记录请求和响应的日志并返回响应。
addPeers(RaftProto.AddPeersRequest request)

作用：处理向集群添加节点的请求。
逻辑：
创建响应对象并设置默认失败响应码。
检查请求中的服务器数量是否为 2 的倍数且不为零。如果不满足条件，记录警告日志并设置响应消息为“添加的服务器数量只能是 2 的倍数”，然后返回失败响应。
检查要添加的服务器是否已经在集群配置中。如果存在，记录警告日志并设置响应消息为“服务器已经在配置中”，然后返回失败响应。
初始化要添加的节点列表并设置其初始状态。
为每个新节点提交添加条目请求。
获取 Raft 节点的锁，等待所有新节点同步完成。
如果所有新节点同步完成，更新集群配置并复制到集群中。
如果复制成功，设置响应码为成功。如果失败，移除新添加的节点并停止其 RPC 客户端。
构建响应对象并记录请求和响应的日志。
返回响应。
removePeers(RaftProto.RemovePeersRequest request)

作用：处理从集群移除节点的请求。
逻辑：
创建响应对象并设置默认失败响应码。
检查请求中的服务器数量是否为 2 的倍数且不为零。如果不满足条件，记录警告日志并设置响应消息为“移除的服务器数量只能是 2 的倍数”，然后返回失败响应。
获取 Raft 节点的锁，检查要移除的服务器是否在当前集群配置中。如果不存在，立即返回失败响应。
获取 Raft 节点的锁，生成新的集群配置并转换为字节数组。
复制新的配置到集群中。如果复制成功，设置响应码为成功。
记录请求和响应的日志。
返回响应。





# Storage Packge



# Util package



# Peer


# RaftOptions


# StateMachine
