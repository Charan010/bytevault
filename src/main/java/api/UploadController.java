package api;

import dev.bytekv.core.Chunker;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import dev.bytekv.core.*;


import java.nio.file.*;

@RestController
public class UploadController {

    private final Chunker chunker = new Chunker(256, 33, (int)(Math.pow(2, 64)-1) ,1 << 15); 

    @PostMapping("/upload")
    public String upload(@RequestParam("file") MultipartFile file) throws Exception {

        Path temp = Files.createTempFile("upload-", file.getOriginalFilename());
        Files.copy(file.getInputStream(), temp, StandardCopyOption.REPLACE_EXISTING);

        String manifest = chunker.chunkFile(temp.toString());

        return "File processed. Manifest = " + manifest;
    }

    @GetMapping("/hello")
    public String hello() {
        return "Hello from ByteKV Spring API!";
    }
}
