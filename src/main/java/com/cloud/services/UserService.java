package com.cloud.services;

import com.cloud.model.RegisterForm;
import com.cloud.model.UserFiles;
import com.cloud.model.Users;
import com.cloud.repository.FileRepository;
import com.cloud.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class UserService {

    private final UserRepository userRepository;
    private final FileRepository fileRepository;
    private final FileService fileService;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository,
                       FileRepository fileRepository,
                       FileService fileService,
                       PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.fileRepository = fileRepository;
        this.fileService = fileService;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public Users createUser(RegisterForm form) {
        if (userRepository.existsByUserName(form.getUserName())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already taken");
        }

        Users user = new Users(
                form.getUserName(),
                passwordEncoder.encode(form.getPassword()),
                form.getFirstName(),
                form.getLastName()
        );

        try {
            return userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            // гонка между existsByUserName и save — уникальный индекс в БД
           throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already taken");
        }
    }

    @Transactional(readOnly = true)
    public Optional<Users> getUserByUserName(String userName) {
        return userRepository.findByUserName(userName);
    }


    @Transactional
    public void deleteUser(Long userId) {
        Users user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "User not found: " + userId));

        List<UserFiles> files = fileRepository.findByUser(user);

        // Удаляем физические файлы с диска
        for (UserFiles f : files) {
            try {
                Path full = fileService.resolveStoredFile(f, user);
                boolean deleted = Files.deleteIfExists(full);
                if (!deleted) {
                    log.warn("Файл уже отсутствовал на диске: {}", full);
                }
            } catch (IOException e) {
                log.warn("Не удалось удалить файл с диска id={}, path={}",
                        f.getId(), f.getFilePath(), e);
            }
        }

        //  Удаляем записи о файлах из БД
        fileRepository.deleteAll(files);

        // Удаляет пользователя
        userRepository.delete(user);

        log.info("Пользователь id={} и {} файлов удалены", userId, files.size());
    }
}