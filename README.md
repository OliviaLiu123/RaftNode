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
- getLeader(RaftProto.GetLeaderRequest request) : 这个方法是获取集群领导者的请求。 首先，记录收到请求日志， 创建响应对象并设置默认成功响应码。然后获取节点锁，确保线程安全。再获取当前领导者ID, 如果没有领导者或者领导者id 为0 则设置响应码为失败。如果当前节点是领导者，设置响应中的领导者信息为本地服务器信息。如何当前节点不是领导者，则从配置中查找领导者并设置其信息。 最后释放锁，设置响应的领导者信息并构建响应对象，记录响应日志并返回响应
    
- getConfiguration(RaftProto.GetConfigurationRequest request)：处理获取当前集群配置的请求。首先创建响应对象并设置默认成功响应码，获取 Raft 节点的锁，确保线程安全。然后 获取当前的集群配置，从配置中获取领导者的信息并设置到响应中。再将配置中的所有服务器信息添加到响应中。最后释放锁，构建响应对象，记录请求和响应的日志并返回响应。
    
- addPeers(RaftProto.AddPeersRequest request)：处理向集群添加节点的请求。
首先创建响应对象并设置默认失败响应码。接下来检查请求中的服务器数量是否为 2 的倍数且不为零。如果不满足条件，记录警告日志并设置响应消息为“添加的服务器数量只能是 2 的倍数”，然后返回失败响应。然后检查要添加的服务器是否已经在集群配置中。如果存在，记录警告日志并设置响应消息为“服务器已经在配置中”，然后返回失败响应。
然后初始化要添加的节点列表并设置其初始状态，为每个新节点提交添加条目请求。此时获取 Raft 节点的锁，等待所有新节点同步完成。如果所有新节点同步完成，更新集群配置并复制到集群中。如果复制成功，设置响应码为成功。如果失败，移除新添加的节点并停止其 RPC 客户端。
最后构建响应对象并记录请求和响应的日志。返回响应。

- removePeers(RaftProto.RemovePeersRequest request)：处理从集群移除节点的请求。首先创建响应对象并设置默认失败响应码。检查请求中的服务器数量是否为 2 的倍数且不为零。如果不满足条件，记录警告日志并设置响应消息为“移除的服务器数量只能是 2 的倍数”，然后返回失败响应。此时获取 Raft 节点的锁，检查要移除的服务器是否在当前集群配置中。如果不存在，立即返回失败响应。然后获取 Raft 节点的锁，生成新的集群配置并转换为字节数组。再者，复制新的配置到集群中。如果复制成功，设置响应码为成功。
最后记录请求和响应的日志。返回响应。

RaftConsensusServiceImpl class 实现了RaftConcensusService 接口。主要作用是处理Raft节点之前的共识请求，包括投票请求。预投票请求和追加日志条目请求。确保各节点之前状态的一致性和协调。
包含的属性：
LOG：日志记录器，用于记录类的操作日志。
JsonFormat PRINTER：用于将 Protobuf 消息格式化为 JSON 字符串。
raftNode：RaftNode表示当前的 Raft 节点，用于执行和管理 Raft 协议相关的操作。

除构造函数以外的方法：
1.preVote(RaftProto.VoteRequest request):处理预投票请求。首先获取 Raft 节点的锁以确保线程安全，然后创建响应对象并设置默认响应为不授予投票。
然后检查请求的服务器是否在当前配置中，请求的任期是否小于当前任期，请求的日志是否至少和当前节点的日志一样新。如果日志新，则授予投票并记录日志。最后释放锁并返回响应。

2.requestVote(RaftProto.VoteRequest request)：处理投票请求。同上，如果请求的任期大于当前任期，则降级当前节点。检查请求的日志是否至少和当前节点的日志一样新。如果日志新且当前节点未投票，则授予投票并更新元数据。最后记录日志，释放锁并返回响应。

3. appendEntries(RaftProto.AppendEntriesRequest request)：处理追加日志条目请求。首先获取 Raft 节点的锁以确保线程安全。创建响应对象并设置默认响应为失败。
然后检查请求的任期是否小于当前任期。如果请求的任期大于当前任期，则降级当前节点并设置新的领导者。再检查请求的领导者是否与当前领导者一致，请求的前一个日志索引是否在当前节点的日志中存在，请求的前一个日志项的任期是否匹配。如果请求中没有日志条目，则认为是心跳请求并更新响应。如果有日志条目，则追加这些条目到当前节点的日志中。最新提交索引最后记录日志，释放锁并返回响应。
4. installSnapshot(RaftProto.InstallSnapshotRequest request)处理安装快照请求。首先创建响应对象并设置默认响应码为失败。获取 Raft 节点的锁并检查请求的任期，如果请求任期小于当前任期，则返回失败响应。如果请求任期大于等于当前任期，则降级当前节点并设置新的领导者。如果节点当前正在进行快照操作，则记录警告日志并返回失败响应。
然后设置节点状态为正在安装快照并获取快照锁。如果请求是第一个快照包，初始化临时快照目录并更新元数据。将快照数据写入临时目录中的文件。如果这是最后一个快照包，将临时目录移动为正式快照目录，并更新响应码为成功。记录日志，释放快照锁并关闭文件。如果是最后一个快照包，应用状态机并重新加载快照，截断旧的日志条目。设置节点状态为未在安装快照。返回响应。

5.advanceCommitIndex(RaftProto.AppendEntriesRequest request)：在从节点上推进提交索引。
首先计算新的提交索引并更新节点的提交索引。如果最后应用的日志索引小于提交索引，则依次应用状态机，更新最后应用的索引。最后唤醒等待提交索引的线程。

6.getLeaderCommitIndex(RaftProto.GetLeaderCommitIndexRequest request):获取领导者的提交索引。

一些接口
RaftClientService 中有获取Leader, 获取配置，加Peer ,removePeer 方法
RaftClientServiceAsync 中有异步获取集群中leader, 配置，加Peer,RemovePeer 方法
RaftConsensusService 中有 处理preVote， requestVote, appendEntries, installSnapshot ， 和getLeaderCommitIndex 方法
RaftConsensusServiceAsync中有 异步处理preVote， requestVote, appendEntries, installSnapshot ， 和getLeaderCommitIndex 方法

# Storage Packge
Segment class 这个类的主要目的是管理和存储Raft 日志条目，它提供了对日志条目的访问，写入和存储管理功能。每个Segment 对象表示Raft 日志的一段，通过维护日志条目列表已经与文件系统的交互，实现日志的持久化存储。
 

内部类 Record 目的是封装日志条目的偏移了和实际条目数据。这个偏移量是用于记录袋日志条目在文件中的位置，这样在需要读取，恢复或处理日志条目时，可以通过偏移量快速定位到具体位置，从而提高操作中效率和准确性。

Segment 类的主要属性：
1. canWrite: boolean 该段是否可写
2. startIndex：long 该段起始索引
3. endIndex：long 该段结束索引
4. fileSize：long 该段文件的大小
5. fileName：String 该段文件的名字
6. randomAccessFile：RandomAccessFile 与该段文件关联的随机访问文件对象
7. entreis: List<Record> : 存储日志条目列表

除了构造器，getter,setter 的方法：
RaftProto.LogEntry getEntry(long index)： 获取指定索引处的日志条目。首先检查 startIndex 和 endIndex 是否为 0。如果是，返回 null。然后检查指定的索引是否在当前 Segment 的索引范围内。如果不在范围内，返回 null。最后计算索引在日志条目列表中的位置，并返回相应的日志条目。


SegmentedLog 类的目的 是管理和维护Raft 日志条目段的存储，读取和操作。它通过分段管理日志文件。实现日志持久存储和高效访问，并提供日志条目追加，截断，加载等功能。

1.LOG：Logger日志记录器，用于记录类的操作日志。
2.logDir：String日志文件的目录。
3.logDataDir：String日志数据文件的目录。
4.maxSegmentFileSize：int单个段文件的最大大小。
5.metaData：RaftProto.LogMetaData 日志的元数据。
6.startLogIndexSegmentMap：TreeMap<Long, Segment>存储日志段的映射，键是段的起始索引，值是段对象。
7.totalSize：volatile long所有段文件的总大小，用于判断是否需要进行快照。
方法（除了getter/setter 方法）
1.构造器：在构造器中，设置了日志目录和日志数据目录路径，每个文件的最大大小。 如何不存在就创建了日志数据目录。 调用readSegments（） 方法，读取日志目录中的所有段文件并加载它们。调用readMetaData方法，读取日志的元数据。然后检查元数据，如果为null, 并且存在段文件，则记录错误日志并抛出异常。如果为null且不存在段文件，则初始化一个新的元数据对象，并将firstLogIndex 设置为1.
2.public RaftProto.LogEntry getEntry(long index)：获取指定索引处的日志条目。
首先获取日志的第一个和最后一个索引，检查索引是否在有效范围内（大于等于第一个索引且小于等于最后一个索引）。如果不在范围内，返回 null 并记录调试信息。如果没有段存在，返回 null。然后查找包含该索引的段，并返回相应的日志条目。

3.public long getEntryTerm(long index)：获取指定索引处日志条目的任期。首先获取指定索引的日志条目。如果日志条目为 null，返回 0。
否则返回日志条目的任期。 

4. public long getFirstLogIndex() 获取日志的第一个索引。返回元数据中的第一个日志索引。有两种情况segment为空，1）第一次初始化firstLogIndex=1 ,lastLogIndex =0 2.snapshot 刚完成，日志正好被清理掉，firstLogIndex = snapshotIndex = 1,lastLogIndex = snapshotIndex

5. public long getLastLogIndex():获取日志的最后一个索引。首先检查段是否为空。如果为空且这是第一次初始化，返回第一个日志索引减一。如果快照刚完成，返回第一个日志索引减一。获取最后一个段并返回其结束索引。
   
6. public long append(List<RaftProto.LogEntry> entries)追加一组日志条目到日志中。首先获取当前的最后一个日志索引。遍历传入的日志条目，并依次追加：增加日志条目的索引，计算日志条目的大小，判断是否需要新建段文件。如果当前没有段或当前段不可写或段文件大小超过最大限制，则需要新建段文件；如果当前段不可写，将其关闭并重命名。
创建或获取当前的段文件，将日志条目写入段文件并更新段的元数据，返回新的最后一个日志索引。

7.public void truncatePrefix(long newFirstIndex)：截断日志前缀，将起始索引移到指定的新索引。首先检查新索引是否大于当前第一个索引，如果不大于则返回。循环删除起始索引之前的所有段文件：如果段文件是可写的，停止删除。否则删除段文件并更新总大小。更新元数据中的第一个日志索引。最后记录截断操作日志。

8. public void truncateSuffix(long newEndIndex)：截断日志后缀，将结束索引移到指定的新索引。首先检查新索引是否小于当前最后一个索引，如果不小于则返回。循环删除结束索引之后的所有段文件：如果新索引等于段的结束索引，停止删除。如果新索引小于段的起始索引，删除整个段文件并更新总大小。如果新索引在段的范围内，截断段文件并更新段的元数据和总大小。最后记录截断操作日志。

9. public void loadSegmentData(Segment segment)：加载段文件的数据到内存中。首先打开段文件并读取其内容，将读取到的每个日志条目添加到段的日志条目列表中。
然后更新段的起始和结束索引和总大小。
10.public void readSegments():读取日志目录中的所有段文件并加载它们。首先获取日志目录中的所有文件名并排序。遍历文件名，解析段的起始和结束索引。如果文件名不符合格式，记录警告日志。创建段对象并设置其属性，打开段文件并加载其数据，将段添加到映射中。
11.public RaftProto.LogMetaData readMetaData()：读取日志元数据文件。首先打开元数据文件，从文件中读取元数据对象。返回读取的元数据对象。如果文件不存在，记录警告日志并返回 null。
12.public void updateMetaData(Long currentTerm, Integer votedFor, Long firstLogIndex, Long commitIndex)：更新日志元数据文件。首先根据传入的参数更新元数据对象，将新的元数据对象写入元数据文件。最后记录新的元数据信息。

Snapshot class 这个类的主要目的是管理和处理Raft协议中的快照操作。它负责创建，加载和维护快照元数据，以及处理快照数据文件的打开和关闭操作。快照在分布式系统中用于优化日志存储和加快状态恢复。

属性： 
1.LOG：Logger日志记录器，用于记录类的操作日志。
2.snapshotDir：String快照文件的目录路径。
3.metaData：RaftProto.SnapshotMetaData 快照的元数据。
4.isInstallSnapshot：AtomicBoolean 标志是否正在安装快照。
5.isTakeSnapshot：AtomicBoolean标志节点是否正在进行快照操作。
6.lock：Lock 锁对象，用于同步快照操作。
内部类
SnapshotDataFile 封装快照数据文件的信息。包括文件名和文件的随机访问句柄。
Snapshot 类的方法（除了 getter/setter）：
1.构造函数 初始化 Snapshot 对象。首先设置快照目录路径。创建快照数据目录，设置快照目录路径：this.snapshotDir = raftDataDir + File.separator + "snapshot";
将传入的 raftDataDir 和固定字符串组合，形成快照目录的路径。
创建快照数据目录：String snapshotDataDir = snapshotDir + File.separator + "data";
将快照目录路径和 data 组合，形成快照数据目录的路径。如果快照数据目录不存在，则创建该目录。

2.public void reload()：重新加载快照的元数据。读取元数据文件，如果元数据为空，初始化一个新的空的元数据对象。

3.public TreeMap<String, SnapshotDataFile> openSnapshotDataFiles()：打开快照数据目录下的所有文件，并返回文件名与文件句柄的映射。
首先获取快照数据目录的实际路径，读取快照数据目录中的所有文件名。对每个文件名，打开文件并创建 SnapshotDataFile 对象，将其放入映射中。
返回包含文件名和文件句柄的映射。

4.public void closeSnapshotDataFiles(TreeMap<String, SnapshotDataFile> snapshotDataFileMap)关闭快照数据文件。遍历文件名和文件句柄的映射，逐一关闭文件句柄。

5.public RaftProto.SnapshotMetaData readMetaData()读取快照的元数据文件。打开元数据文件并读取其中的元数据对象，如果文件不存在，记录警告日志并返回 null。
否则返回读取的元数据对象。

6.public void updateMetaData(String dir, Long lastIncludedIndex, Long lastIncludedTerm, RaftProto.Configuration configuration)：更新快照的元数据文件。根据传入的参数创建新的元数据对象。确保指定的目录存在。创建或覆盖元数据文件，并将新的元数据对象写入文件。如果发生异常，记录警告日志。



# Util package

ConfigurationUtils 类提供了一些用于处理 Raft 配置信息的工具方法。

1. containsServer(RaftProto.Configuration configuration, int serverId) 检查配置中是否包含指定的服务器。首先遍历配置中的所有服务器，检查是否存在与给定 serverId 匹配的服务器。如果找到匹配的服务器，返回 true，否则返回 false。

2.removeServers(RaftProto.Configuration configuration, List<RaftProto.Server> servers)：从配置中移除指定的服务器列表。
首先创建一个新的配置构建器。遍历原配置中的服务器，检查每个服务器是否在要移除的服务器列表中。如果不在移除列表中，将其添加到新的配置构建器中。最后构建并返回新的配置。

3.getServer(RaftProto.Configuration configuration, int serverId) 从配置中获取指定 serverId 的服务器。遍历配置中的所有服务器，检查是否存在与给定 serverId 匹配的服务器。如果找到匹配的服务器，返回该服务器对象。
如果没有找到匹配的服务器，返回 null。


RaftFileUtils 类提供了一些用于处理文件操作的工具方法，特别是与 Raft 协议相关的文件操作。

方法
1.getSortedFilesInDirectory(String dirName, String rootDirName)获取指定目录下的所有文件，并按排序顺序返回文件名列表。检查指定目录是否存在且是目录。
遍历目录及其子目录中的所有文件，将文件路径添加到文件列表中，对文件列表进行排序并返回。

2.openFile(String dir, String fileName, String mode)打开指定目录下的文件，并以指定模式返回 RandomAccessFile 对象。
首先构建完整的文件路径。打开文件并返回 RandomAccessFile 对象。如果文件不存在，记录警告日志并抛出 RuntimeException。

3.closeFile(RandomAccessFile randomAccessFile)关闭 RandomAccessFile 文件。首先检查文件对象是否为 null。关闭文件，并捕获并记录任何 IOException 异常。

4.closeFile(FileInputStream inputStream)关闭 FileInputStream 文件。检查文件对象是否为 null，关闭文件，并捕获并记录任何 IOException 异常。

5.closeFile(FileOutputStream outputStream)关闭 FileOutputStream 文件。检查文件对象是否为 null。关闭文件，并捕获并记录任何 IOException 异常。

6.<T extends Message> T readProtoFromFile(RandomAccessFile raf, Class<T> clazz)从文件中读取并解析 Protobuf 消息。
首先读取文件中的 CRC32 校验码和数据长度，读取数据并计算其 CRC32 校验码。如果校验码匹配，使用反射调用 Protobuf 的 parseFrom 方法解析数据并返回消息对象。
如果发生任何异常或校验码不匹配，记录警告日志并返回 null。

7.<T extends Message> void writeProtoToFile(RandomAccessFile raf, T message)将 Protobuf 消息写入文件。将消息转换为字节数组。计算消息的 CRC32 校验码。写入校验码和消息长度，然后写入消息数据。如果发生任何异常，记录警告日志并抛出 RuntimeException。
8. long getCRC32(byte[] data)计算给定数据的 CRC32 校验码。使用 CRC32 类更新数据并返回计算的校验码。

# Peer

Peer 类 的目的是表示Raft 集群中的一个除自己以为的其他节点（一个同伴），并管理改节点的通信和状态同步。它封装了与远程节点进行通信的客户端，并维护了与该节点相关的各种状态信息。
属性
  1.server：RaftProto.Server表示该节点的信息。
  2.rpcClient：RpcClient 用于与该节点通信的 RPC 客户端。
  3.raftConsensusServiceAsync：RaftConsensusServiceAsync 用于异步调用 Raft 共识服务。
  4.nextIndex：long 需要发送给 follower 的下一个日志条目的索引值，仅对 leader 有效。
  5.matchIndex： long 已复制日志的最高索引值。
  6.voteGranted：volatile Boolean 表示该节点是否授予了投票。
  7.isCatchUp：volatile boolean 表示该节点是否已经追赶上 leader 的日志状态。

# RaftOptions
RaftOptions 类用于配置和管理 Raft 协议的各种选项和参数。这些选项包括选举超时时间、心跳周期、快照相关设置、日志条目同步配置等。通过这些配置选项，可以调整 Raft 协议的行为和性能。

属性
private int electionTimeoutMilliseconds = 5000; 跟随者在指定毫秒数内未收到领导者消息时，会变为候选人（默认5000毫秒）。
private int heartbeatPeriodMilliseconds = 500;领导者发送心跳的周期，即使没有数据要发送（默认500毫秒）。
private int snapshotPeriodSeconds = 3600;快照定时器执行间隔，以秒为单位（默认3600秒）。
private int snapshotMinLogSize = 100 * 1024 * 1024;日志条目大小达到指定值时才进行快照（默认100MB）。
private int maxSnapshotBytesPerRequest = 500 * 1024;每个快照请求的最大字节数（默认500KB）。
private int maxLogEntriesPerRequest = 5000;每个请求的最大日志条目数（默认5000）。
private int maxSegmentFileSize = 100 * 1000 * 1000;单个段文件的最大大小（默认100MB）。
private long catchupMargin = 500;跟随者与领导者的日志差距在指定范围内时，才可以参与选举和提供服务（默认500）。
private long maxAwaitTimeout = 1000;同步最大等待超时时间，以毫秒为单位（默认1000毫秒）。
private int raftConsensusThreadNum = 20;进行同步和选举操作的线程池大小（默认20）。
private boolean asyncWrite = false;是否异步写数据，true 表示主节点保存后立即返回，然后异步同步给从节点；false 表示主节点同步给多数从节点后才返回。
private String dataDir = System.getProperty("com.github.raftimpl.raft.data.dir");Raft的日志和快照的父目录，绝对路径。


# StateMachine
StateMachine 接口 定义了 Raft 状态机的操作方法。状态机在分布式系统中用于保存和管理应用状态。Raft 协议通过状态机来应用日志条目、创建和读取快照，以及查询和修改状态机的数据。

方法
1.void writeSnapshot(String snapshotDir, String tmpSnapshotDataDir, RaftNode raftNode, long localLastAppliedIndex);对状态机中的数据进行快照，每个节点本地定时调用。
参数：
snapshotDir：旧快照目录。
tmpSnapshotDataDir：新快照数据目录。
raftNode：Raft 节点对象。
localLastAppliedIndex：已应用到复制状态机的最大日志条目索引。

2.void readSnapshot(String snapshotDir);从快照读取数据到状态机中，节点启动时调用。
参数：snapshotDir：快照数据目录。

3.void apply(byte[] dataBytes);将数据应用到状态机，通常是将日志条目应用到状态机。
参数：dataBytes：数据的二进制表示。

4.byte[] get(byte[] dataBytes);从状态机读取数据。
参数：dataBytes：键的二进制表示。
返回：值的二进制表示。

在 example module 里面 有 四个类都实现了Statemachine 接口
这四个类是——BitCaskStateMachine、BTreeStateMachine、LevelDBStateMachine 和 RocksDBStateMachine，它们使用不同的存储引擎来管理和操作数据。以下是它们的主要区别：
1. BitCaskStateMachine
存储引擎：使用 BitCask。
特点：
BitCask 是一个简单、快速、键值对存储系统，主要用于高性能的读写操作。
通过 BitCaskOptions 配置数据库，使用 put 和 getString 方法来存储和检索数据。
快照处理：
writeSnapshot 方法将快照数据复制到临时目录，并将未应用的日志条目应用到临时数据库。
readSnapshot 方法将快照数据复制到数据目录，并打开 BitCask 数据库。

2. BTreeStateMachine
存储引擎：使用 BTreeIndex。 
特点：
BTreeIndex 使用 B 树数据结构进行存储，适合于需要有序访问的数据。
通过 putValue 和 getValue 方法来存储和检索数据。
快照处理：
writeSnapshot 方法将快照数据复制到临时目录，并将未应用的日志条目应用到临时数据库。
readSnapshot 方法将快照数据复制到数据目录，并打开 BTreeIndex 数据库。

3. LevelDBStateMachine
存储引擎：使用 LevelDB。
特点：
LevelDB 是一个高性能的键值对存储系统，支持高效的顺序写入和随机读取。
通过 put 和 get 方法来存储和检索数据。
快照处理：
writeSnapshot 方法将快照数据复制到临时目录，并将未应用的日志条目应用到临时数据库。
readSnapshot 方法将快照数据复制到数据目录，并打开 LevelDB 数据库。

4. RocksDBStateMachine
存储引擎：使用 RocksDB。 
特点：
RocksDB 是 LevelDB 的一个高效扩展版本，支持更高的写入吞吐量和更低的读写延迟。
通过 put 和 get 方法来存储和检索数据。
快照处理：
writeSnapshot 方法使用 Checkpoint 类创建数据库的快照。
readSnapshot 方法将快照数据复制到数据目录，并打开 RocksDB 数据库。
