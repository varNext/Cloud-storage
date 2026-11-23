package com.cloud.controller;

import com.cloud.model.UserFiles;
import com.cloud.model.Users;
import com.cloud.repository.UserRepository;
import com.cloud.security.UserDetailsImpl;
import com.cloud.services.FileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;

@Slf4j
@Controller
@RequestMapping("/files")

public class FileController {

    private final FileService fileService;
    private final UserRepository userRepository;

    public FileController(FileService fileService, UserRepository userRepository) {
        this.fileService = fileService;
        this.userRepository = userRepository;
    }

    /** Загружает сущность текущего пользователя по id из principal. */
    private Users currentUser(UserDetailsImpl principal) {
        if (principal == null || principal.getId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        return userRepository.findById(principal.getId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Authenticated user not found"));
    }

    /** Список файлов текущего пользователя. */
    @GetMapping
    public String list(@AuthenticationPrincipal UserDetailsImpl principal, Model model) {
        Users user = currentUser(principal);
        model.addAttribute("files", fileService.getFilesByUser(user));
        model.addAttribute("userName", user.getUserName());
        return "files";
    }

    /** Загрузка файла. PRG: после успеха — redirect на список. */
    @PostMapping("/upload")
    public String upload(@RequestParam("file") MultipartFile file,
                         @AuthenticationPrincipal UserDetailsImpl principal,
                         Model model) {
        Users user = currentUser(principal);
        try {
            fileService.addFile(file, user);
            return "redirect:/files";
        } catch (ResponseStatusException e) {
            log.warn("Ошибка загрузки файла для {}: {}", user.getUserName(), e.getReason());
            model.addAttribute("files", fileService.getFilesByUser(user));
            model.addAttribute("userName", user.getUserName());
            model.addAttribute("error", e.getReason());
            return "files";
        }
    }

    /** Скачивание файла. Владелец проверяется в сервисе через findByIdAndUser. */
    @GetMapping("/download/{id}")
    public ResponseEntity<Resource> download(@PathVariable Long id,
                                             @AuthenticationPrincipal UserDetailsImpl principal) {
        Users user = currentUser(principal);
        UserFiles meta = fileService.getFileForUser(id, user);
        Path full = fileService.resolveStoredFile(meta, user);

        Resource resource = new FileSystemResource(full);
        if (!resource.exists() || !resource.isReadable()) {
            log.warn("Файл отсутствует на диске: {}", full);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found on disk");
        }

        String contentType = meta.getContentType() != null
                ? meta.getContentType()
                : MediaType.APPLICATION_OCTET_STREAM_VALUE;

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + meta.getFileName() + "\"")
                .contentLength(meta.getFileSize())
                .body(resource);
    }

    /** Удаление файла, после успеха — redirect на список. */
    @PostMapping("/delete/{id}")
    public String delete(@PathVariable Long id,
                         @AuthenticationPrincipal UserDetailsImpl principal) {
        Users user = currentUser(principal);
        fileService.deleteFile(id, user);
        return "redirect:/files";
    }
}