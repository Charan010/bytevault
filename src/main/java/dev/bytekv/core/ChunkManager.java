package dev.bytekv.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.*;


public class ChunkManager {

    private ReentrantReadWriteLock rw;
    private ReadLock readerLock;
    private WriteLock writerLock;

    private ConcurrentHashMap<String, Boolean> chunkCache;
    private final File chunkDir;

    public ChunkManager(String chunkFolderPath){

        rw = new ReentrantReadWriteLock();
        readerLock = rw.readLock();
        writerLock = rw.writeLock();
        chunkCache = new ConcurrentHashMap<>();
        
        this.chunkDir = new File(chunkFolderPath);
        if(!chunkDir.exists())
            chunkDir.mkdirs();
    }
    
    public void onChunk(String hash,byte[] chunk) throws IOException{
        if (chunkCache.putIfAbsent(hash, Boolean.TRUE) == null) 
            writeChunkToFile(hash, chunk);
    }

    private void writeChunkToFile(String hash, byte[] chunkData)throws IOException{
            File outFile = new File(chunkDir, hash + ".bin");
            if(outFile.exists())
                return;

            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                writerLock.lock();
                fos.write(chunkData);

            }finally{
                writerLock.unlock();
            }
    }
    

}