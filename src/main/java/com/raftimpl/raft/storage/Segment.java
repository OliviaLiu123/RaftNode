package com.raftimpl.raft.storage;
import com.raftimpl.raft.proto.RaftProto;

import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
//管理 Raft 协议中的日志段，存储和读取日志条目，并维护相关元数据
/*
日志段是日志的一部分，用于存储和管理日志条目。每个日志条目记录了客户端的命令，这些命令会被应用到状态机中，以确保分布式系统的一致性。
主要作用
1. 存储日志条目：Segment 类维护一个日志条目列表（entries），这些日志条目记录了客户端的命令和状态信息。

2.管理日志段的元数据：该类还存储了日志段的元数据，如日志段的起始索引（startIndex）、结束索引（endIndex）、文件大小（fileSize）和文件名（fileName）。

3.日志条目的读写操作：提供了根据索引获取日志条目的方法（getEntry），还可以设置和获取日志段的元数据。

4.文件操作：使用 RandomAccessFile 进行文件的随机访问，支持对日志段文件的读取和写入操作。
 */
public class Segment {
    // 内部类，用于表示日志记录
    public static class Record{
        public long offset; // 偏移量
        public RaftProto.LogEntry entry; // 日志条目

        public Record(long offset, RaftProto.LogEntry entry) {
            this.offset = offset;
            this.entry = entry;
        }
    }

    private boolean canWrite; // 该段是否写入
    private long startIndex; // 该段的起始索引。

    private long endIndex; //该段的结束索引。

    private long fileSize; //存储该段的文件大小。

    private String fileName; //存储该段的文件名。

    private RandomAccessFile randomAccessFile; //用于随机访问文件。

    private List<Record> entries = new ArrayList<>(); //日志条目列表，存储该段中的所有日志记录。

//根据索引获取日志条目，如果索引不在范围内或段为空，则返回
    public RaftProto.LogEntry getEntry(long index){
        if(startIndex ==0 || endIndex ==0){
            return null;

        }
        if (index < startIndex || index > endIndex) {
            return null;
        }
        int indexInList = (int) (index - startIndex);
        return entries.get(indexInList).entry;
    }

    public boolean isCanWrite() {
        return canWrite;
    }

    public void setCanWrite(boolean canWrite) {
        this.canWrite = canWrite;
    }

    public long getStartIndex() {
        return startIndex;
    }

    public void setStartIndex(long startIndex) {
        this.startIndex = startIndex;
    }

    public long getEndIndex() {
        return endIndex;
    }

    public void setEndIndex(long endIndex) {
        this.endIndex = endIndex;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public RandomAccessFile getRandomAccessFile() {
        return randomAccessFile;
    }

    public void setRandomAccessFile(RandomAccessFile randomAccessFile) {
        this.randomAccessFile = randomAccessFile;
    }

    public List<Record> getEntries() {
        return entries;
    }

    public void setEntries(List<Record> entries) {
        this.entries = entries;
    }
}
