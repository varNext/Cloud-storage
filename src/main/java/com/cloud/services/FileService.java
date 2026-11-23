package com.cloud.services;

import com.cloud.model.UserFiles;
import com.cloud.model.Users;
import com.cloud.repository.FileRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class FileService {

    private final FileRepository fileRepository;
    private final String storageDir;

    public FileService(FileRepository fileRepository,
                       @Value("${app.storage.dir:./storage}") String storageDir) {
        this.fileRepository = fileRepository;
        this.storageDir = storageDir;
    }

    /** Папка пользователя: storage/user_{id}*/
    private Path userDir(Users user) {
        if (user == null || user.getId() == null) {
            throw new IllegalStateException("User must be persisted before storing files");
        }
        return Paths.get(storageDir, "user_" + user.getId())
                .toAbsolutePath().normalize();
    }

    /**
     * Собирает полный путь к файлу и проверяет, что он внутри папки пользователя.

     */
    public Path resolveStoredFile(UserFiles file, Users user) {
        Path base = userDir(user);
        Path full = base.resolve(file.getFilePath()).normalize();
        if (!full.startsWith(base)) {
            throw new IllegalStateException("Invalid stored path for file id=" + file.getId());
        }
        return full;
    }

    @Transactional
    public UserFiles addFile(MultipartFile file, Users user) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Файл не был загружен или пуст");
        }

        Path dir = userDir(user);   // заодно проверяет user.getId() != null
        String storedName = UUID.randomUUID() + ".bin";
        Path target = dir.resolve(storedName).normalize();

        if (!target.startsWith(dir)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid file path");
        }

        try {
            Files.createDirectories(dir);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("Ошибка сохранения файла для user id={}", user.getId(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to store file");
        }

        UserFiles meta = new UserFiles(
                file.getOriginalFilename(),
                file.getSize(),
                storedName,
                file.getContentType(),
                user
        );

        try {
            return fileRepository.save(meta);
        } catch (RuntimeException e) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException io) {
                log.warn("Не удалось удалить файл {}", target, io);
            }
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public List<UserFiles> getFilesByUser(Users user) {
        if (user == null || user.getId() == null) {
            throw new IllegalStateException("User must be persisted");
        }
        return fileRepository.findByUser(user);
    }

    @Transactional(readOnly = true)
    public UserFiles getFileForUser(Long id, Users user) {
        if (user == null || user.getId() == null) {
            throw new IllegalStateException("User must be persisted");
        }
        return fileRepository.findByIdAndUser(id, user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found"));
    }

    @Transactional
    public void deleteFile(Long id, Users user) {
        if (user == null || user.getId() == null) {
            throw new IllegalStateException("User must be persisted");
        }

        UserFiles file = fileRepository.findByIdAndUser(id, user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found"));

        Path full = resolveStoredFile(file, user);
        try {
            Files.deleteIfExists(full);
        } catch (IOException e) {
            log.warn("Не удалось удалить файл с диска: {}", full, e);
               }
        fileRepository.delete(file);
    }


}