package dev.bytekv.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/*
   * The main issue with fixed size file chunking is that if current some bytes gets written at starting of file.
     then, whole fixed size would shift by number of bytes written and when it tries to check if this chunk exists already.
     It returns false. So instead of solely depending upon offset , we need to do sliding window efficiently based on content.
     So, I'm using rabin karp rolling algorithm here. 

 
     M = a large prime number
     base = for balancing collision reduction and less computation (choosing something like 31 or 33).
      Smaller the base, more probability of colliding with other chunks.

   
     initial hash = ((b0 * base^N + b1 * base^N + b2 *base^N + ...)%M 
     new_hash = ((old_hash - b_out * Base ^ N)* base + b_in)%M
     take some pattern lets say if hash & mask = 0 then start chunking from that byte

*/

public class Chunker {

    private final int windowSize;
    private final int base;
    private final long M;
    private final int maskBits;
    private final long mask;
    private final long maxChunkSize;
    private static final String manifestDirPath = "manifests";
    private static final String chunkFolderPath = "objects";

    private final ChunkManager chunkManager;

    public Chunker(int windowSize, int base, long M, int maskBits) {
        this.windowSize = windowSize;
        this.base = base;
        this.M = M;
        this.maskBits = maskBits;
        this.mask = (1L << maskBits) - 1;
        this.maxChunkSize = 1L << 20;
        this.chunkManager = new ChunkManager(chunkFolderPath);
    }

    private int getScore(byte b) {
        return b & 0xFF;
    }

    private String generateSHA256(byte[] chunk) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(chunk);
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes)
                sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public String chunkFile(String filePath) throws IOException {
        Path inputFile = Paths.get(filePath);
        Files.createDirectories(Paths.get(chunkFolderPath));
        Files.createDirectories(Paths.get(manifestDirPath));

        List<String> manifestChunkHashes = new ArrayList<>();

        if (Files.size(inputFile) < windowSize) {
            byte[] data = Files.readAllBytes(inputFile);
            String chunkHash = generateSHA256(data);
            manifestChunkHashes.add(chunkHash);
            chunkManager.onChunk(chunkHash, data);

            writeManifestFile(new ArrayList<>(manifestChunkHashes));
            System.out.printf("Single small chunk: %d bytes [%s]%n", data.length, chunkHash);
            return chunkHash;
        }

        try (InputStream in = new BufferedInputStream(Files.newInputStream(inputFile))) {

            Deque<Byte> window = new ArrayDeque<>();
            ByteArrayOutputStream chunkBuf = new ByteArrayOutputStream();
            long rollingHash = 0;
            long powBase = 1;

            for (int i = 0; i < windowSize - 1; ++i)
                powBase = (powBase * base) % M;

            int b;
            long offset = 0;

            while (window.size() < windowSize && (b = in.read()) != -1) {
                byte bb = (byte) b;
                window.addLast(bb);
                chunkBuf.write(bb);
                rollingHash = (rollingHash * base + getScore(bb)) % M;
                offset++;
            }

            while ((b = in.read()) != -1) {
                byte newByte = (byte) b;
                byte outByte = window.removeFirst();
                window.addLast(newByte);
                chunkBuf.write(newByte);

                long val = (rollingHash + M - (getScore(outByte) * powBase) % M) % M;
                rollingHash = (val * base + getScore(newByte)) % M;
                offset++;

                boolean boundary = ((rollingHash & mask) == 0) || (chunkBuf.size() >= maxChunkSize);

                if (boundary) {
                    byte[] chunkBytes = chunkBuf.toByteArray();
                    String chunkHash = generateSHA256(chunkBytes);
                    manifestChunkHashes.add(chunkHash);
                    chunkManager.onChunk(chunkHash, chunkBytes);
                    chunkBuf.reset();
                }
            }

            if (chunkBuf.size() > 0) {
                byte[] chunkBytes = chunkBuf.toByteArray();
                String chunkHash = generateSHA256(chunkBytes);
                manifestChunkHashes.add(chunkHash);
                chunkManager.onChunk(chunkHash, chunkBytes);
            }
        }

        return writeManifestFile(new ArrayList<>(manifestChunkHashes));
    }

   private String writeManifestFile(ArrayList<String> manifestChunkHashes) throws IOException {

    if (manifestChunkHashes == null || manifestChunkHashes.isEmpty()) {
        System.out.println("Manifest chunk hash list is empty or null");
        return null;
    }

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    DataOutputStream hashStream = new DataOutputStream(baos);

        for (String hash : manifestChunkHashes) {
            byte[] bytes = hash.getBytes();
            hashStream.writeInt(bytes.length);  
            hashStream.write(bytes);   
        }

        String manifestHash = generateSHA256(baos.toByteArray());

        File outFile = new File(manifestDirPath, manifestHash + ".bin");
        if (outFile.exists()) {
            System.out.println("Manifest already exists: " + outFile.getName());
            return manifestHash;  
        }
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(outFile))) {
            for (String hash : manifestChunkHashes) {
                dos.writeUTF(hash);
            }
        }

        System.out.println("Manifest written: " + outFile.getAbsolutePath());
        return manifestHash;
    }

   public void constructFile(String manifestHash, String outputPath) throws IOException {
    File manifestFile = new File(manifestDirPath, manifestHash + ".bin");
    if (!manifestFile.exists()) {
        throw new FileNotFoundException("Manifest not found: " + manifestHash);
    }

    List<String> chunkHashes = new ArrayList<>();
    try (DataInputStream dis = new DataInputStream(new FileInputStream(manifestFile))) {
        while (dis.available() > 0) {
            String hash = dis.readUTF();
            chunkHashes.add(hash);
        }
    }

    File outFile = new File(outputPath);
    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(outFile))) {
        for (String chunkHash : chunkHashes) {
            File chunkFile = new File(chunkFolderPath, chunkHash + ".bin");
            if (!chunkFile.exists()) {
                throw new FileNotFoundException("Missing chunk: " + chunkHash);
            }

            try (InputStream in = new BufferedInputStream(new FileInputStream(chunkFile))) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            }
        }
    }

    System.out.println("File reconstructed at: " + outFile.getAbsolutePath());
}
}