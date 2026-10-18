package com.wengu.backend.question.controller;

import com.wengu.backend.common.exception.BizException;
import com.wengu.backend.common.result.Result;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/media")
public class MediaController {

    @Data
    @AllArgsConstructor
    public static class MediaVO {
        private String url;
    }

    @Value("${wengu.media-dir}")
    private String mediaDir;

    private static final Set<String> ALLOWED = Set.of("png", "jpg", "jpeg", "webp", "gif");

    @PostMapping("/upload")
    public Result<MediaVO> upload(@RequestParam("file") MultipartFile f) {
        if (f == null || f.isEmpty()) throw new BizException(400, "空文件");
        if (f.getSize() > 5 * 1024 * 1024) throw new BizException(400, "单张图片最大5MB");
        String name = f.getOriginalFilename();
        int i = (name == null) ? -1 : name.lastIndexOf('.');
        String ext = (i < 0 || i == name.length() - 1) ? null : name.substring(i + 1).toLowerCase();
        if (ext == null || !ALLOWED.contains(ext)) {
            throw new BizException(400, "仅支持 png/jpg/jpeg/webp/gif");
        }
        // ★ UUID重命名 + 按月分目录：防原名冲突/防路径穿越/防目录爆炸；存盘与回参共用同一个 fileName
        String month = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
        try {
            Path dir = Paths.get(mediaDir, month);
            Files.createDirectories(dir);
            String fileName = UUID.randomUUID() + "." + ext;
            Files.copy(f.getInputStream(), dir.resolve(fileName));
            return Result.success(new MediaVO("/media/" + month + "/" + fileName));
        } catch (Exception e) {
            throw new BizException(500, "上传失败");
        }
    }
}
