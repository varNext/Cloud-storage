package com.cloud;

import com.cloud.controller.FileController;
import com.cloud.model.UserFiles;
import com.cloud.model.Users;
import com.cloud.repository.UserRepository;
import com.cloud.security.UserDetailsImpl;
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
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Модульные тесты для файлового контроллера")
class FileControllerModuleTests {

    @Mock
    private FileService fileService;

    @Mock
    private UserRepository userRepository;

    @TempDir
    Path tempDir;

    private FileController controller;
    private Users user;
    private UserDetailsImpl principal;

    @BeforeEach
    void setUp() {
        controller = new FileController(fileService, userRepository);
        user = Users.builder()
                .id(1L)
                .userName("john")
                .password("pwd")
                .firstName("John")
                .lastName("Doe")
                .build();
        principal = new UserDetailsImpl(user);
    }


    @Nested
    @DisplayName("currentUser")
    class CurrentUserTests {

        @Test
        @DisplayName("Негативный: principal == null → 401 UNAUTHORIZED")
        @Description("""
                Негативный: principal == null → ResponseStatusException(401),
                userRepository и fileService не вызываются.
                """)
        void currentUser_nullPrincipal_throws401() {
            Model model = new ExtendedModelMap();

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> controller.list(null, model));

            assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
            assertEquals("Not authenticated", ex.getReason());
            verifyNoInteractions(userRepository, fileService);
        }

        @Test
        @DisplayName("Негативный: principal без id → 401 UNAUTHORIZED")
        @Description("""
                Негативный: principal.getId() == null → 401, репозиторий не вызывается.
                """)
        void currentUser_principalWithoutId_throws401() {
            UserDetailsImpl noId = new UserDetailsImpl(
                    Users.builder()
                            .userName("john").password("pwd")
                            .firstName("John").lastName("Doe")
                            .build());
            Model model = new ExtendedModelMap();

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> controller.list(noId, model));

            assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
            verifyNoInteractions(userRepository, fileService);
        }

        @Test
        @DisplayName("Негативный: пользователь не найден в БД → 401 UNAUTHORIZED")
        @Description("""
                Негативный: userRepository.findById → empty → 401
                с reason "Authenticated user not found".
                """)
        void currentUser_userNotFound_throws401() {
            when(userRepository.findById(1L)).thenReturn(Optional.empty());
            Model model = new ExtendedModelMap();

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> controller.list(principal, model));

            assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
            assertEquals("Authenticated user not found", ex.getReason());
            verifyNoInteractions(fileService);
        }
    }


    @Nested
    @DisplayName("list")
    class ListTests {

        @Test
        @DisplayName("Позитивный: модель содержит files и userName, view = \"files\"")
        @Description("""
                Позитивный: getFilesByUser возвращает список, в модель кладутся
                files и userName, view = "files".
                """)
        void list_success() {
            UserFiles f = new UserFiles("a.txt", 1L, "s.bin", "text/plain", user);
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            when(fileService.getFilesByUser(user)).thenReturn(List.of(f));
            Model model = new ExtendedModelMap();

            String view = controller.list(principal, model);

            assertEquals("files", view);
            assertEquals(List.of(f), model.getAttribute("files"));
            assertEquals("john", model.getAttribute("userName"));
            verify(fileService).getFilesByUser(user);
        }

        @Test
        @DisplayName("Граничный: пустой список файлов")
        @Description("""
                Граничный: у пользователя нет файлов — в модель кладётся пустой список.
                """)
        void list_emptyFiles() {
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            when(fileService.getFilesByUser(user)).thenReturn(List.of());
            Model model = new ExtendedModelMap();

            String view = controller.list(principal, model);

            assertEquals("files", view);
            assertEquals(List.of(), model.getAttribute("files"));
        }
    }


    @Nested
    @DisplayName("upload")
    class UploadTests {

        @Test
        @DisplayName("Позитивный: файл загружен — redirect:/files")
        @Description("""
                Позитивный: addFile успешен → "redirect:/files",
                getFilesByUser не вызывается.
                """)
        void upload_success_redirects() {
            MultipartFile mf = new MockMultipartFile(
                    "file", "hello.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            when(fileService.addFile(any(), eq(user)))
                    .thenReturn(new UserFiles("hello.txt", 5L, "uuid.bin", "text/plain", user));
            Model model = new ExtendedModelMap();

            String view = controller.upload(mf, principal, model);

            assertEquals("redirect:/files", view);
            verify(fileService).addFile(any(), eq(user));
            verify(fileService, never()).getFilesByUser(any());
        }

        @Test
        @DisplayName("Негативный: 400 от сервиса → view \"files\" + error")
        @Description("""
                Негативный: addFile бросает 400 → контроллер ловит, кладёт error
                и список файлов в модель, возвращает view "files" (без редиректа).
                """)
        void upload_badRequest_returnsViewWithError() {
            MultipartFile mf = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            when(fileService.addFile(any(), eq(user)))
                    .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Файл пуст"));
            when(fileService.getFilesByUser(user)).thenReturn(List.of());
            Model model = new ExtendedModelMap();

            String view = controller.upload(mf, principal, model);

            assertEquals("files", view);
            assertEquals("Файл пуст", model.getAttribute("error"));
            assertEquals("john", model.getAttribute("userName"));
            assertEquals(List.of(), model.getAttribute("files"));
            verify(fileService).getFilesByUser(user);
        }

        @Test
        @DisplayName("Негативный: 500 от сервиса → view \"files\" + error")
        @Description("""
                Негативный: addFile бросает 500 → тот же путь обработки:
                view "files", error, список файлов.
                """)
        void upload_serverError_returnsViewWithError() {
            MultipartFile mf = new MockMultipartFile("file", "a.txt", "text/plain", "x".getBytes());
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            when(fileService.addFile(any(), eq(user)))
                    .thenThrow(new ResponseStatusException(
                            HttpStatus.INTERNAL_SERVER_ERROR, "Failed to store file"));
            when(fileService.getFilesByUser(user)).thenReturn(List.of());
            Model model = new ExtendedModelMap();

            String view = controller.upload(mf, principal, model);

            assertEquals("files", view);
            assertEquals("Failed to store file", model.getAttribute("error"));
        }
    }

    @Nested
    @DisplayName("download")
    class DownloadTests {

        @Test
        @DisplayName("Позитивный: 200 + Content-Type")
        @Description("""
                Позитивный: метаданные + файл на диске → 200, Content-Type из meta,
                Content-Disposition: attachment, Content-Length = fileSize.
                """)
        void download_success() throws IOException {
            Path dir = Files.createDirectories(tempDir.resolve("user_1"));
            Path stored = Files.writeString(dir.resolve("s.bin"), "hello");

            UserFiles meta = new UserFiles("hello.txt", 5L, "s.bin", "text/plain", user);
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            when(fileService.getFileForUser(10L, user)).thenReturn(meta);
            when(fileService.resolveStoredFile(meta, user)).thenReturn(stored);

            ResponseEntity<Resource> response = controller.download(10L, principal);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals("text/plain", response.getHeaders().getContentType().toString());
            assertEquals("attachment; filename=\"hello.txt\"",
                    response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
            assertEquals(5L, response.getHeaders().getContentLength());
            assertTrue(response.getBody().exists());
            assertTrue(response.getBody().isReadable());
        }

        @Test
        @DisplayName("Граничный: contentType == null → application/octet-stream")
        @Description("""
                Граничный: meta.contentType == null → fallback на application/octet-stream.
                """)
        void download_nullContentType_fallback() throws IOException {
            Path dir = Files.createDirectories(tempDir.resolve("user_1"));
            Path stored = Files.writeString(dir.resolve("s.bin"), "x");

            UserFiles meta = new UserFiles("a.bin", 1L, "s.bin", null, user);
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            when(fileService.getFileForUser(10L, user)).thenReturn(meta);
            when(fileService.resolveStoredFile(meta, user)).thenReturn(stored);

            ResponseEntity<Resource> response = controller.download(10L, principal);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals("application/octet-stream",
                    response.getHeaders().getContentType().toString());
        }

        @Test
        @DisplayName("Негативный: файла нет на диске → 404 NOT_FOUND")
        @Description("""
                Негативный: resolveStoredFile вернул несуществующий путь →
                404 NOT_FOUND с reason "File not found on disk".
                """)
        void download_fileMissingOnDisk_throws404() {
            UserFiles meta = new UserFiles("a.txt", 1L, "s.bin", "text/plain", user);
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            when(fileService.getFileForUser(10L, user)).thenReturn(meta);
            when(fileService.resolveStoredFile(meta, user))
                    .thenReturn(tempDir.resolve("user_1").resolve("missing.bin"));

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> controller.download(10L, principal));

            assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
            assertEquals("File not found on disk", ex.getReason());
        }

        @Test
        @DisplayName("Негативный: getFileForUser → 404")
        @Description("""
                Негативный: fileService.getFileForUser бросает 404 —
                контроллер не ловит,
                resolveStoredFile не вызывается.
                """)
        void download_fileNotOwned_throws404() {
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            when(fileService.getFileForUser(99L, user))
                    .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found"));

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> controller.download(99L, principal));

            assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
            verify(fileService, never()).resolveStoredFile(any(), any());
        }
    }

    @Nested
    @DisplayName("delete")
    class DeleteTests {

        @Test
        @DisplayName("Позитивный: удаление — redirect:/files")
        @Description("""
                Позитивный: deleteFile не бросает → "redirect:/files".
                """)
        void delete_success_redirects() {
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            doNothing().when(fileService).deleteFile(10L, user);

            String view = controller.delete(10L, principal);

            assertEquals("redirect:/files", view);
            verify(fileService).deleteFile(10L, user);
        }

    }
}