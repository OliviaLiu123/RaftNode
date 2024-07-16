package com.raftimpl.raft.util;
import com.google.protobuf.Message;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.zip.CRC32;


//一组实用工具方法，以便在Raft实现中处理文件操作，ProtoBuf对象的读写和CRC32校验相关的操作
public class RaftFileUtils {
    private static final Logger LOG = LoggerFactory.getLogger(RaftFileUtils.class);


    // 获取目录下的排序后的文件列表 并按字典顺序排序
    public static List<String> getSortedFilesInDirectory( String dirName, String rootDirName) throws IOException{
        List<String> fileList = new ArrayList<>();
        File rootDir = new File(rootDirName);
        File dir = new File(rootDirName);

        if(!rootDir.isDirectory() || !dir.isDirectory()){
            return fileList;
        }

        String rootPath = rootDir.getCanonicalPath();
        if(!rootPath.endsWith("/")){
            rootPath = rootPath +"/";
        }

        File[] files = dir.listFiles();
        for (File file : files) {
            if (file.isDirectory()) {
                fileList.addAll(getSortedFilesInDirectory(file.getCanonicalPath(), rootPath));
            } else {
                fileList.add(file.getCanonicalPath().substring(rootPath.length()));
            }
        }
        Collections.sort(fileList);
        return fileList;

    }
    // 打开文件，返回RandomAccessFile对象
    public static RandomAccessFile openFile(String dir,String fileName,String mode ){
        try {
            String fullFileName = dir + File.separator + fileName;
            File file = new File(fullFileName);
            return new RandomAccessFile(file, mode);
        } catch (FileNotFoundException ex) {
            LOG.warn("file not fount, file={}", fileName);
            throw new RuntimeException("file not found, file=" + fileName);
        }
    }
    // 关闭RandomAccessFile对象
    public static void closeFile(RandomAccessFile randomAccessFile){
        try{
            if (randomAccessFile !=null){
                randomAccessFile.close();
            }
        }catch(IOException ex){
            LOG.warn("close file error, msg={,}",ex.getMessage());

        }
    }
    // 关闭FileInputStream对象
    public static void closeFile(FileInputStream inputStream) {
        try {
            if (inputStream != null) {
                inputStream.close();
            }
        } catch (IOException ex) {
            LOG.warn("close file error, msg={}", ex.getMessage());
        }
    }
    // 关闭FileOutputStream对象
    public static void closeFile(FileOutputStream outputStream) {
        try {
            if (outputStream != null) {
                outputStream.close();
            }
        } catch (IOException ex) {
            LOG.warn("close file error, msg={}", ex.getMessage());
        }
    }

    // 从文件中读取Proto对象
    public static <T extends Message> T readProtoFromFile(RandomAccessFile raf, Class<T> clazz){
        try{
            long crc32FromFile = raf.readLong(); // 读取文件中的CRC32校验码
            int dataLen = raf.readInt();// 读取数据长度
            int hasReadLen = (Long.SIZE +Integer.SIZE)/Byte.SIZE;
            if (raf.length() - hasReadLen < dataLen) {
                LOG.warn("file remainLength < dataLen");
                return null;
            }
            byte[] data = new byte[dataLen];
            int readLen = raf.read(data);
            if (readLen != dataLen) {
                LOG.warn("readLen != dataLen");
                return null;
            }
            long crc32FromData = getCRC32(data); // 计算读取数据的CRC32校验码
            if (crc32FromFile != crc32FromData) {
                LOG.warn("crc32 check failed");
                return null;
            }
            Method method = clazz.getMethod("parseFrom", byte[].class);
            T message = (T) method.invoke(clazz, data);  // 调用parseFrom方法解析Proto对象
            return message;

        }catch (Exception ex){
            LOG.warn("readProtoFromFile meet exception, {}", ex.getMessage());
            return null;
        }

    }
    public static  <T extends Message> void writeProtoToFile(RandomAccessFile raf, T message) {
        byte[] messageBytes = message.toByteArray();
        long crc32 = getCRC32(messageBytes); // 计算数据的CRC32校验码
        try {
            raf.writeLong(crc32); // 写入CRC32校验码
            raf.writeInt(messageBytes.length); // 写入数据长度
            raf.write(messageBytes);  // 写入数据
        } catch (IOException ex) {
            LOG.warn("write proto to file error, msg={}", ex.getMessage());
            throw new RuntimeException("write proto to file error");
        }
    }
    // 计算CRC32校验码
    public static long getCRC32(byte[] data) {
        CRC32 crc32 = new CRC32();
        crc32.update(data);
        return crc32.getValue();
    }


}
