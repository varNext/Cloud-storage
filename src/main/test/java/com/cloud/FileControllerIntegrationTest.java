package com.cloud;

import com.cloud.model.UserFiles;
import com.cloud.model.Users;
import com.cloud.repository.FileRepository;
import com.cloud.repository.UserRepository;
import com.cloud.security.UserDetailsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;


import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
//FileControllerIntegrationTest  →  FileController + FileService + диск + БД
class FileControllerIntegrationTest {


    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:file_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("app.storage.dir", () -> storageRoot.toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FileRepository fileRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;


    private Users testUser;
    private UserDetailsImpl principal;

    @BeforeEach
    void setUp() {
        fileRepository.deleteAll();
        userRepository.deleteAll();

        testUser = userRepository.save(new Users(
                "john", passwordEncoder.encode("secret123"), "John", "Doe"));
        principal = new UserDetailsImpl(testUser);
    }


    @Nested
    @DisplayName("GET /files")
    class ListFiles {

        @Test
        @DisplayName("пустой список: view 'files', модель содержит пустой files и userName")
        void emptyList() throws Exception {
            mockMvc.perform(get("/files").with(user(principal)))
                    .andExpect(status().isOk())
                    .andExpect(view().name("files"))
                    .andExpect(model().attribute("userName", "john"))
                    .andExpect(model().attribute("files",
                            org.hamcrest.Matchers.hasSize(0)));
        }

        @Test
        @DisplayName("список с файлами: модель содержит записи из БД")
        void listWithFiles() throws Exception {
            fileRepository.save(new UserFiles(
                    "a.txt", 5L, "uuid-a.bin", "text/plain", testUser));
            fileRepository.save(new UserFiles(
                    "b.pdf", 10L, "uuid-b.bin", "application/pdf", testUser));

            mockMvc.perform(get("/files").with(user(principal)))
                    .andExpect(status().isOk())
                    .andExpect(view().name("files"))
                    .andExpect(model().attribute("files",
                            org.hamcrest.Matchers.hasSize(2)))
                    .andExpect(model().attribute("userName", "john"));
        }

        @Test
        @DisplayName("неаутентифицированный — редирект на /login")
        void unauthenticatedRedirects() throws Exception {
            mockMvc.perform(get("/files"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrlPattern("**/login"));
        }
    }


    @Nested
    @DisplayName("POST /files/upload")
    class UploadFile {

        @Test
        @DisplayName("успешная загрузка: файл на диске + запись в БД + редирект")
        void uploadSuccess() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "hello.txt", "text/plain",
                    "hello world".getBytes(StandardCharsets.UTF_8));

            mockMvc.perform(multipart("/files/upload")
                            .file(file)
                            .with(csrf())
                            .with(user(principal)))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/files"));

            // Проверка БД
            var files = fileRepository.findByUser(testUser);
            assertThat(files).hasSize(1);
            UserFiles meta = files.get(0);
            assertThat(meta.getFileName()).isEqualTo("hello.txt");
            assertThat(meta.getFileSize()).isEqualTo(11L);
            assertThat(meta.getContentType()).isEqualTo("text/plain");
            assertThat(meta.getFilePath()).endsWith(".bin");

            // Проверка диска: storage/user_{id}/<uuid>.bin
            Path stored = storageRoot
                    .resolve("user_" + testUser.getId())
                    .resolve(meta.getFilePath());
            assertThat(stored).exists();
            assertThat(Files.readString(stored)).isEqualTo("hello world");
        }

        @Test
        @DisplayName("пустой файл — 400, view 'files' с error, в БД ничего")
        void uploadEmptyFile() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "empty.txt", "text/plain", new byte[0]);

            mockMvc.perform(multipart("/files/upload")
                            .file(file)
                            .with(csrf())
                            .with(user(principal)))
                    .andExpect(status().isOk())
                    .andExpect(view().name("files"))
                    .andExpect(model().attribute("error",
                            "Файл не был загружен или пуст"));

            assertThat(fileRepository.findByUser(testUser)).isEmpty();
        }

        @Test
        @DisplayName("файл уже есть — второй upload создаёт второй файл")
        void uploadTwiceCreatesTwoFiles() throws Exception {
            MockMultipartFile f1 = new MockMultipartFile(
                    "file", "a.txt", "text/plain", "aaa".getBytes());
            MockMultipartFile f2 = new MockMultipartFile(
                    "file", "b.txt", "text/plain", "bbb".getBytes());

            mockMvc.perform(multipart("/files/upload").file(f1)
                            .with(csrf()).with(user(principal)))
                    .andExpect(status().is3xxRedirection());
            mockMvc.perform(multipart("/files/upload").file(f2)
                            .with(csrf()).with(user(principal)))
                    .andExpect(status().is3xxRedirection());

            assertThat(fileRepository.findByUser(testUser)).hasSize(2);
        }

        @Test
        @DisplayName("неаутентифицированный — редирект на /login, файл не создан")
        void uploadUnauthenticated() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "x.txt", "text/plain", "x".getBytes());

            mockMvc.perform(multipart("/files/upload")
                            .file(file)
                            .with(csrf()))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrlPattern("**/login"));

            assertThat(fileRepository.count()).isZero();
        }
    }


    @Nested
    @DisplayName("GET /files/download/{id}")
    class DownloadFile {

        @Test
        @DisplayName("успешное скачивание: 200, Content-Disposition, тело = содержимое файла")
        void downloadSuccess() throws Exception {

            Path userDir = Files.createDirectories(
                    storageRoot.resolve("user_" + testUser.getId()));
            Path stored = Files.writeString(userDir.resolve("uuid-d.bin"), "payload");

            UserFiles meta = fileRepository.save(new UserFiles(
                    "report.txt", 7L, "uuid-d.bin", "text/plain", testUser));

            mockMvc.perform(get("/files/download/" + meta.getId())
                            .with(user(principal)))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition",
                            "attachment; filename=\"report.txt\""))
                    .andExpect(content().contentType(MediaType.TEXT_PLAIN))
                    .andExpect(content().string("payload"));

            assertThat(stored).exists();
        }

        @Test
        @DisplayName("файла нет в БД — 404")
        void downloadNotFoundInDb() throws Exception {
            mockMvc.perform(get("/files/download/9999")
                            .with(user(principal)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("запись в БД есть, но файла на диске нет — 404")
        void downloadMissingOnDisk() throws Exception {
            UserFiles meta = fileRepository.save(new UserFiles(
                    "ghost.txt", 5L, "ghost.bin", "text/plain", testUser));

            mockMvc.perform(get("/files/download/" + meta.getId())
                            .with(user(principal)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("чужой файл — 404 (владелец проверяется в сервисе)")
        void downloadAnotherUsersFile() throws Exception {
            Users other = userRepository.save(new Users(
                    "eve", passwordEncoder.encode("secret123"), "Eve", "Evil"));

            Path otherDir = Files.createDirectories(
                    storageRoot.resolve("user_" + other.getId()));
            Files.writeString(otherDir.resolve("uuid-e.bin"), "secret");

            UserFiles meta = fileRepository.save(new UserFiles(
                    "secret.txt", 6L, "uuid-e.bin", "text/plain", other));

            mockMvc.perform(get("/files/download/" + meta.getId())
                            .with(user(principal)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("неаутентифицированный — редирект на /login")
        void downloadUnauthenticated() throws Exception {
            mockMvc.perform(get("/files/download/1"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrlPattern("**/login"));
        }
    }

    @Nested
    @DisplayName("POST /files/delete/{id}")
    class DeleteFile {

        @Test
        @DisplayName("успешное удаление: файл с диска и запись из БД удалены, редирект")
        void deleteSuccess() throws Exception {
            Path userDir = Files.createDirectories(
                    storageRoot.resolve("user_" + testUser.getId()));
            Path stored = Files.writeString(userDir.resolve("uuid-del.bin"), "bye");

            UserFiles meta = fileRepository.save(new UserFiles(
                    "bye.txt", 3L, "uuid-del.bin", "text/plain", testUser));

            mockMvc.perform(post("/files/delete/" + meta.getId())
                            .with(csrf())
                            .with(user(principal)))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/files"));

            assertThat(fileRepository.findById(meta.getId())).isEmpty();
            assertThat(stored).doesNotExist();
        }

        @Test
        @DisplayName("файла нет в БД — 404, delete не падает")
        void deleteNotFound() throws Exception {
            mockMvc.perform(post("/files/delete/9999")
                            .with(csrf())
                            .with(user(principal)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("запись в БД есть, файла на диске нет — запись всё равно удаляется")
        void deleteMissingOnDisk() throws Exception {
            UserFiles meta = fileRepository.save(new UserFiles(
                    "ghost.txt", 5L, "ghost.bin", "text/plain", testUser));

            mockMvc.perform(post("/files/delete/" + meta.getId())
                            .with(csrf())
                            .with(user(principal)))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/files"));

            assertThat(fileRepository.findById(meta.getId())).isEmpty();
        }

        @Test
        @DisplayName("чужой файл — 404, запись не удалена")
        void deleteAnotherUsersFile() throws Exception {
            Users other = userRepository.save(new Users(
                    "eve", passwordEncoder.encode("secret123"), "Eve", "Evil"));
            UserFiles meta = fileRepository.save(new UserFiles(
                    "other.txt", 5L, "uuid-o.bin", "text/plain", other));

            mockMvc.perform(post("/files/delete/" + meta.getId())
                            .with(csrf())
                            .with(user(principal)))
                    .andExpect(status().isNotFound());

            assertThat(fileRepository.findById(meta.getId())).isPresent();
        }

        @Test
        @DisplayName("неаутентифицированный — редирект на /login, файл не удалён")
        void deleteUnauthenticated() throws Exception {
            UserFiles meta = fileRepository.save(new UserFiles(
                    "keep.txt", 4L, "uuid-k.bin", "text/plain", testUser));

            mockMvc.perform(post("/files/delete/" + meta.getId()).with(csrf()))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrlPattern("**/login"));

            assertThat(fileRepository.findById(meta.getId())).isPresent();
        }
    }
}