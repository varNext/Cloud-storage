package com.cloud;

import com.cloud.model.UserFiles;
import com.cloud.model.Users;
import com.cloud.repository.FileRepository;
import com.cloud.services.FileService;
import io.qameta.allure.Description;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Модульные тесты для сервиса файловой системы")
class FileServiceModuleTests {

    @Mock
    private FileRepository fileRepository;

    @TempDir
    Path tempDir;

    private FileService fileService;
    private Users user;

    @BeforeEach
    void setUp() {
        fileService = new FileService(fileRepository, tempDir.toString());
        user = Users.builder()
                .id(1L)
                .userName("john")
                .password("pwd")
                .firstName("John")
                .lastName("Doe")
                .build();
    }


    @Nested
    @DisplayName("resolveStoredFile")
    class ResolveStoredFile {

        @Test
        @DisplayName("Позитивный: путь внутри папки пользователя")
        @Description("""
                Позитивный: file.filePath — относительный, путь резолвится внутри storage/user_{id}.
                """)
        void resolve_ok() {
            UserFiles f = new UserFiles("a.txt", 10L, "stored.bin", "text/plain", user);

            Path result = fileService.resolveStoredFile(f, user);

            assertTrue(result.startsWith(tempDir.toAbsolutePath().normalize().resolve("user_1")));
            assertEquals("stored.bin", result.getFileName().toString());
        }

        @Test
        @DisplayName("Негативный: path traversal — IllegalStateException")
        @Description("""
                Негативный: filePath = '../../etc/passwd' — результат 
                выходит за пределы папки пользователя → IllegalStateException.
                """)
        void resolve_pathTraversal_throws() {
            UserFiles f = new UserFiles("a.txt", 10L, "../../etc/passwd", "text/plain", user);

            assertThrows(IllegalStateException.class,
                    () -> fileService.resolveStoredFile(f, user));
        }

        @Test
        @DisplayName("Граничный: user без id — IllegalStateException")
        @Description("""
                Граничный: user == null или user.getId() == null — IllegalStateException.
                """)
        void resolve_userWithoutId_throws() {
            UserFiles f = new UserFiles("a.txt", 10L, "stored.bin", "text/plain", user);
            Users transientUser = new Users("x", "p", "X", "Y"); // id == null

            assertThrows(IllegalStateException.class,
                    () -> fileService.resolveStoredFile(f, transientUser));
            assertThrows(IllegalStateException.class,
                    () -> fileService.resolveStoredFile(f, null));
        }
    }


    @Nested
    @DisplayName("addFile")
    class AddFile {

        @Test
        @DisplayName("Позитивный: файл сохраняется на диск и метаданные в БД")
        @Description("""
                Позитивный: валидный MultipartFile → создаётся storage/user_1/<uuid>.bin,
                содержимое совпадает, fileRepository.save возвращает сущность.
                """)
        void addFile_success() throws IOException {
            MultipartFile mf = new MockMultipartFile(
                    "file", "hello.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));

            when(fileRepository.save(any(UserFiles.class))).thenAnswer(inv -> inv.getArgument(0));

            UserFiles result = fileService.addFile(mf, user);

            assertNotNull(result);
            assertEquals("hello.txt", result.getFileName());
            assertEquals(5L, result.getFileSize());
            assertEquals("text/plain", result.getContentType());

            Path stored = tempDir.resolve("user_1").resolve(result.getFilePath());
            assertTrue(Files.exists(stored));
            assertEquals("hello", Files.readString(stored));

            verify(fileRepository).save(any(UserFiles.class));
        }

        @Test
        @DisplayName("Негативный: пустой файл — 400 BAD_REQUEST")
        @Description("""
                Негативный: MultipartFile.isEmpty() == true → 400 BAD_REQUEST,
                ни диск, ни репозиторий не трогаются.
                """)
        void addFile_empty_throws400() {
            MultipartFile mf = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> fileService.addFile(mf, user));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
            verifyNoInteractions(fileRepository);
        }

        @Test
        @DisplayName("Негативный: null MultipartFile — 400 BAD_REQUEST")
        @Description("""
                Негативный: file == null → 400 BAD_REQUEST.
                """)
        void addFile_null_throws400() {
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> fileService.addFile(null, user));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        }

        @Test
        @DisplayName("Негативный: user без id — IllegalStateException")
        @Description("""
                Негативный: user == null или user.getId() == null → IllegalStateException,
                файл не пишется.
                """)
        void addFile_userWithoutId_throws() {
            MultipartFile mf = new MockMultipartFile("file", "a.txt", "text/plain", "x".getBytes());
            Users transientUser = new Users("x", "p", "X", "Y"); // id == null

            assertThrows(IllegalStateException.class,
                    () -> fileService.addFile(mf, transientUser));
            assertThrows(IllegalStateException.class,
                    () -> fileService.addFile(mf, null));
        }

        @Test
        @DisplayName("Негативный: ошибка save() — файл откатывается с диска")
        @Description("""
                Негативный: fileRepository.save бросает RuntimeException →
                ранее сохранённый файл удаляется с диска, исключение пробрасывается.
                """)
        void addFile_saveFails_rollsBackDisk() {
            MultipartFile mf = new MockMultipartFile("file", "a.txt", "text/plain", "x".getBytes());
            when(fileRepository.save(any(UserFiles.class)))
                    .thenThrow(new RuntimeException("db down"));

            assertThrows(RuntimeException.class,
                    () -> fileService.addFile(mf, user));

            Path dir = tempDir.resolve("user_1");
            try (var files = Files.list(dir)) {
                assertEquals(0, files.count());
            } catch (IOException e) {
                fail("Не удалось проверить папку: " + e.getMessage());
            }
        }
    }


    @Nested
    @DisplayName("getFilesByUser")
    class GetFilesByUser {

        @Test
        @DisplayName("Позитивный: список файлов пользователя")
        @Description("""
                Позитивный: fileRepository.findByUser возвращает список.
                """)
        void getFiles_ok() {
            UserFiles f = new UserFiles("a.txt", 1L, "s.bin", "text/plain", user);
            when(fileRepository.findByUser(user)).thenReturn(List.of(f));

            List<UserFiles> result = fileService.getFilesByUser(user);

            assertEquals(1, result.size());
            verify(fileRepository).findByUser(user);
        }

        @Test
        @DisplayName("Позитивный: пустой список")
        @Description("""
                Граничный: у пользователя нет файлов — пустой список.
                """)
        void getFiles_empty() {
            when(fileRepository.findByUser(user)).thenReturn(List.of());

            assertTrue(fileService.getFilesByUser(user).isEmpty());
        }

        @Test
        @DisplayName("Негативный: user без id — IllegalStateException")
        @Description("""
                Негативный: user == null или getId() == null — IllegalStateException.
                """)
        void getFiles_userWithoutId_throws() {
            assertThrows(IllegalStateException.class,
                    () -> fileService.getFilesByUser(new Users("x", "p", "X", "Y")));
            assertThrows(IllegalStateException.class,
                    () -> fileService.getFilesByUser(null));
        }
    }

    @Nested
    @DisplayName("getFileForUser")
    class GetFileForUser {

        @Test
        @DisplayName("Позитивный: файл найден")
        @Description("""
                Позитивный: findByIdAndUser находит запись — возвращается сущность.
                """)
        void getFile_ok() {
            UserFiles f = new UserFiles("a.txt", 1L, "s.bin", "text/plain", user);
            when(fileRepository.findByIdAndUser(10L, user)).thenReturn(Optional.of(f));

            UserFiles result = fileService.getFileForUser(10L, user);

            assertEquals(f, result);
        }

        @Test
        @DisplayName("Негативный: файл не найден — 404 NOT_FOUND")
        @Description("""
                Негативный: findByIdAndUser возвращает Optional.empty() → 404 NOT_FOUND.
                """)
        void getFile_notFound() {
            when(fileRepository.findByIdAndUser(99L, user)).thenReturn(Optional.empty());

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> fileService.getFileForUser(99L, user));

            assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        }

        @Test
        @DisplayName("Граничный: null id → пустой Optional → 404")
        @Description("""
                Граничный: id == null — findByIdAndUser(null, user) → empty → 404.
                """)
        void getFile_nullId() {
            when(fileRepository.findByIdAndUser(null, user)).thenReturn(Optional.empty());

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> fileService.getFileForUser(null, user));

            assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        }

        @Test
        @DisplayName("Негативный: user без id — IllegalStateException")
        @Description("""
                Негативный: user == null или getId() == null — IllegalStateException.
                """)
        void getFile_userWithoutId_throws() {
            assertThrows(IllegalStateException.class,
                    () -> fileService.getFileForUser(1L, new Users("x", "p", "X", "Y")));
            assertThrows(IllegalStateException.class,
                    () -> fileService.getFileForUser(1L, null));
        }
    }


    @Nested
    @DisplayName("deleteFile")
    class DeleteFile {

        @Test
        @DisplayName("Позитивный: файл и метаданные удалены")
        @Description("""
                Позитивный: файл найден → удалён с диска → fileRepository.delete вызван.
                """)
        void deleteFile_success() throws IOException {
            Path dir = Files.createDirectories(tempDir.resolve("user_1"));
            Path stored = Files.createFile(dir.resolve("s.bin"));

            UserFiles f = new UserFiles("a.txt", 1L, "s.bin", "text/plain", user);
            when(fileRepository.findByIdAndUser(10L, user)).thenReturn(Optional.of(f));

            fileService.deleteFile(10L, user);

            assertFalse(Files.exists(stored));
            verify(fileRepository).delete(f);
        }

        @Test
        @DisplayName("Позитивный (отказоустойчивость): файла нет на диске — метаданные всё равно удаляются")
        @Description("""
                Позитивный (отказоустойчивость): файл уже удалён с диска —
                deleteIfExists=false, fileRepository.delete всё равно вызывается.
                """)
        void deleteFile_missingOnDisk_stillDeletesMeta() {
            UserFiles f = new UserFiles("a.txt", 1L, "s.bin", "text/plain", user);
            when(fileRepository.findByIdAndUser(10L, user)).thenReturn(Optional.of(f));

            assertDoesNotThrow(() -> fileService.deleteFile(10L, user));
            verify(fileRepository).delete(f);
        }

        @Test
        @DisplayName("Негативный: файл не найден — 404, delete не вызывается")
        @Description("""
                Негативный: findByIdAndUser пуст → 404 NOT_FOUND, fileRepository.delete не вызывается.
                """)
        void deleteFile_notFound() {
            when(fileRepository.findByIdAndUser(99L, user)).thenReturn(Optional.empty());

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> fileService.deleteFile(99L, user));

            assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
            verify(fileRepository, never()).delete(any());
        }

        @Test
        @DisplayName("Граничный: null id → 404")
        @Description("""
                Граничный: id == null → findByIdAndUser(null, user) → empty → 404.
                """)
        void deleteFile_nullId() {
            when(fileRepository.findByIdAndUser(null, user)).thenReturn(Optional.empty());

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> fileService.deleteFile(null, user));

            assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        }
    }
}