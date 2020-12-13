package com.snail.commit;

import com.snail.mapped.MappedFile;
import com.snail.mapped.SelectMappedBuffer;
import com.snail.message.Message;
import lombok.Data;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @version V1.0
 * @author: csz
 * @Title
 * @Package: com.snail.commit
 * @Description:
 * @date: 2020/12/13
 */
@Data
public class CommitLog {

//    魔数
    public final static int MESSAGE_MAGIC_CODE = 0xababf;

//    写指针
    private AtomicInteger writePos;

//    最后一个添加消息的时间戳
    private AtomicLong latestWriteTimeStamp;

//    log文件映射对象
    private MappedFile mappedFile;

//    log文件区域映射对象
    private SelectMappedBuffer selectMappedBuffer;

//    用于持久化写指针的buffer
    private SelectMappedBuffer writePosSelectMappedBuffer;

    public CommitLog(File file, int fileSize, boolean autoCreate) throws IOException {
        this.mappedFile = new MappedFile(file, fileSize, autoCreate);
        init();
        this.selectMappedBuffer = mappedFile.select(writePos.get(), fileSize - writePos.get());
        this.writePosSelectMappedBuffer = mappedFile.select(4, 12);
    }

    private void init() {
        /*
        magic 4| 写指针位置 4 |最后一次写时间戳 8
         */
        SelectMappedBuffer selectMappedBuffer = this.mappedFile.select(0, 16);
        ByteBuffer byteBuffer = selectMappedBuffer.getByteBuffer();

        int magic = byteBuffer.getInt();
//        如果魔数不一致说明这个文件可能损坏或者是一个新文件
//        TODO 文件损坏检查
        if (MESSAGE_MAGIC_CODE != magic) {
            long currentTimeMillis = System.currentTimeMillis();
            byteBuffer.rewind();
            byteBuffer.putInt(MESSAGE_MAGIC_CODE);
            byteBuffer.putInt(16);
            byteBuffer.putLong(currentTimeMillis);
            writePos = new AtomicInteger(16);
            latestWriteTimeStamp = new AtomicLong(currentTimeMillis);
            return;
        }

        writePos = new AtomicInteger(byteBuffer.getInt());
        latestWriteTimeStamp = new AtomicLong(byteBuffer.getLong());

    }

    public void shutdown() {

        saveHeader();

        if (selectMappedBuffer != null) {
            selectMappedBuffer.release();
            selectMappedBuffer = null;
        }
        if (mappedFile != null) {
            mappedFile.shutdown();
            mappedFile = null;
        }
    }

    /**
     * 添加消息
     * TODO 判断这个文件是否写的下这个消息
     * @param message
     */
    public void addMessage(Message message) {

//        获取序列化数据
        ByteBuffer messageByteBuffer = message.serialize();

//        写入消息
//        TODO 刷盘政策
        this.selectMappedBuffer.getByteBuffer()
            .put(messageByteBuffer);

        updateWritePos(messageByteBuffer.limit());
    }

    /**
     * 更新写指针
     * @param size 写入数据大小
     */
    private void updateWritePos(int size) {
//        设置指针和最后时间
        this.writePos.addAndGet(size);
        this.latestWriteTimeStamp.set(System.currentTimeMillis());
        saveHeader();
    }

    /**
     * 保存写指针
     */
    private void saveHeader() {
        ByteBuffer byteBuffer = writePosSelectMappedBuffer.getByteBuffer();
        byteBuffer.putInt(this.writePos.intValue());
        byteBuffer.putLong(this.latestWriteTimeStamp.longValue());
        byteBuffer.rewind();
    }


}
