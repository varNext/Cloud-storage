package com.cloud;

import com.cloud.model.RegisterForm;
import com.cloud.model.UserFiles;
import com.cloud.model.Users;
import com.cloud.repository.FileRepository;
import com.cloud.repository.UserRepository;
import com.cloud.services.FileService;
import com.cloud.services.UserService;
import io.qameta.allure.Description;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Модульные тесты для сервиса пользователя")
class UserServiceModuleTests {

    @Mock
    private UserRepository userRepository;

    @Mock
    private FileRepository fileRepository;

    @Mock
    private FileService fileService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserService userService;

    private RegisterForm form;

    @BeforeEach
    void setUp() {
        form = new RegisterForm();
        form.setUserName("john");
        form.setPassword("secret");
        form.setFirstName("John");
        form.setLastName("Doe");
    }


    @Nested
    @DisplayName("createUser")
    class CreateUser {

        @Test
        @DisplayName("Позитивный: успешное создание пользователя")
        @Description("""
                Позитивный: создание пользователя с валидными данными.
                exists=false → пароль кодируется → save возвращает сущность.
                """)
        void createUser_success() {
            when(userRepository.existsByUserName("john")).thenReturn(false);
            when(passwordEncoder.encode("secret")).thenReturn("encoded");

            Users saved = new Users("john", "encoded", "John", "Doe");
            when(userRepository.save(any(Users.class))).thenReturn(saved);

            Users result = userService.createUser(form);

            assertNotNull(result);
            assertEquals("john", result.getUserName());
            assertEquals("encoded", result.getPassword());
            assertEquals("John", result.getFirstName());
            assertEquals("Doe", result.getLastName());

            verify(userRepository).existsByUserName("john");
            verify(passwordEncoder).encode("secret");
            verify(userRepository).save(any(Users.class));
        }

        @Test
        @DisplayName("Позитивный: пароль кодируется перед сохранением")
        @Description("""
                Позитивный: пароль сохраняется в захэшированном виде.
                """)
        void createUser_passwordIsEncoded() {
            when(userRepository.existsByUserName("john")).thenReturn(false);
            when(passwordEncoder.encode("secret")).thenReturn("HASHED");
            when(userRepository.save(any(Users.class))).thenAnswer(inv -> inv.getArgument(0));

            Users result = userService.createUser(form);

            assertEquals("HASHED", result.getPassword());
            verify(passwordEncoder).encode("secret");
        }

        @Test
        @DisplayName("Негативный: username уже существует — 409 CONFLICT")
        @Description("""
                Негативный: username уже занят.
                Ожидаем 409 CONFLICT, save и passwordEncoder не вызываются.
                """)
        void createUser_usernameTaken() {
            when(userRepository.existsByUserName("john")).thenReturn(true);

            ResponseStatusException ex = assertThrows(
                    ResponseStatusException.class,
                    () -> userService.createUser(form));

            assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
            assertTrue(ex.getReason().contains("Username already taken"));

            verify(userRepository, never()).save(any());
            verifyNoInteractions(passwordEncoder);
        }


        @Test
        @DisplayName("Граничный: пустой username — передаётся как есть в репозиторий")
        @Description("""
                Граничный: пустой username передаётся в репозиторий без валидации на уровне сервиса.
                """)
        void createUser_emptyUserName() {
            form.setUserName("");
            when(userRepository.existsByUserName("")).thenReturn(false);
            when(passwordEncoder.encode("secret")).thenReturn("encoded");
            when(userRepository.save(any(Users.class))).thenAnswer(inv -> inv.getArgument(0));

            Users result = userService.createUser(form);

            assertEquals("", result.getUserName());
            verify(userRepository).existsByUserName("");
        }

        @Test
        @DisplayName("Граничный: null username — NPE не ловится в сервисе")
        @Description("""
                Граничный: null username — сервис не падает, передаёт null в репозиторий.
                """)
        void createUser_nullUserName() {
            form.setUserName(null);
            when(userRepository.existsByUserName(null)).thenReturn(false);
            when(passwordEncoder.encode("secret")).thenReturn("encoded");
            when(userRepository.save(any(Users.class))).thenAnswer(inv -> inv.getArgument(0));

            Users result = userService.createUser(form);

            assertNull(result.getUserName());
        }

        @Test
        @DisplayName("Граничный: username длиной 50 символов — успешно")
        @Description("""
                Граничный: username ровно 50 символов (верхняя граница колонки) — успешно.
                """)
        void createUser_maxLengthUserName() {
            String longName = "a".repeat(50);
            form.setUserName(longName);
            when(userRepository.existsByUserName(longName)).thenReturn(false);
            when(passwordEncoder.encode("secret")).thenReturn("encoded");
            when(userRepository.save(any(Users.class))).thenAnswer(inv -> inv.getArgument(0));

            Users result = userService.createUser(form);

            assertEquals(50, result.getUserName().length());
        }


        @Nested
        @DisplayName("getUserByUserName")
        class GetUserByUserName {

            @Test
            @DisplayName("Позитивный: пользователь найден")
            @Description("""
                    Позитивный: пользователь найден по username.
                    """)
            void getUser_found() {
                Users user = new Users("john", "pwd", "John", "Doe");
                when(userRepository.findByUserName("john")).thenReturn(Optional.of(user));

                Optional<Users> result = userService.getUserByUserName("john");

                assertTrue(result.isPresent());
                assertEquals("john", result.get().getUserName());
                verify(userRepository).findByUserName("john");
            }

            @Test
            @DisplayName("Негативный: пользователь не найден — пустой Optional")
            @Description("""
                    Негативный: пользователь отсутствует — возвращается пустой Optional.
                    """)
            void getUser_notFound() {
                when(userRepository.findByUserName("ghost")).thenReturn(Optional.empty());

                Optional<Users> result = userService.getUserByUserName("ghost");

                assertFalse(result.isPresent());
            }

            @Test
            @DisplayName("Граничный: null username — без падения сервиса")
            @Description("""
                    Граничный: null username — репозиторий вызывается с null, сервис не падает.
                    """)
            void getUser_nullUserName() {
                when(userRepository.findByUserName(null)).thenReturn(Optional.empty());

                Optional<Users> result = userService.getUserByUserName(null);

                assertTrue(result.isEmpty());
                verify(userRepository).findByUserName(null);
            }

            @Test
            @DisplayName("Граничный: пустая строка username")
            @Description("""
                    Граничный: пустая строка username — возвращается пустой Optional.
                    """)
            void getUser_emptyUserName() {
                when(userRepository.findByUserName("")).thenReturn(Optional.empty());

                Optional<Users> result = userService.getUserByUserName("");

                assertTrue(result.isEmpty());
            }
        }

        @Nested
        @DisplayName("deleteUser")
        class DeleteUser {

            @TempDir
            Path tempDir;

            private Users user;
            private UserFiles file1;
            private UserFiles file2;

            @BeforeEach
            void initDeleteCase() {
                user = new Users("john", "pwd", "John", "Doe");
                file1 = mock(UserFiles.class);
                file2 = mock(UserFiles.class);
            }

            @Test
            @DisplayName("Позитивный: удаление пользователя и всех файлов с диска")
            @Description("""
                    Позитивный: пользователь найден, оба файла физически удалены с диска,
                    затем вызывается userRepository.delete(user).
                    """)
            void deleteUser_success() throws IOException {
                Path f1 = Files.createFile(tempDir.resolve("f1.txt"));
                Path f2 = Files.createFile(tempDir.resolve("f2.txt"));

                when(userRepository.findById(1L)).thenReturn(Optional.of(user));
                when(fileRepository.findByUser(user)).thenReturn(List.of(file1, file2));
                when(fileService.resolveStoredFile(file1, user)).thenReturn(f1);
                when(fileService.resolveStoredFile(file2, user)).thenReturn(f2);

                userService.deleteUser(1L);

                assertFalse(Files.exists(f1));
                assertFalse(Files.exists(f2));
                verify(userRepository).delete(user);
            }

            @Test
            @DisplayName("Позитивный: пользователь без файлов")
            @Description("""
                    Позитивный: у пользователя нет файлов — fileService не задействован.
                    """)
            void deleteUser_noFiles() {
                when(userRepository.findById(1L)).thenReturn(Optional.of(user));
                when(fileRepository.findByUser(user)).thenReturn(Collections.emptyList());

                userService.deleteUser(1L);

                verify(userRepository).delete(user);
                verifyNoInteractions(fileService);
            }

            @Test
            @DisplayName("Негативный: пользователь не найден — 404 NOT_FOUND")
            @Description("""
                    Негативный: пользователь не найден — 404 NOT_FOUND,
                    fileRepository/fileService/delete не вызываются.
                    """)
            void deleteUser_notFound() {
                when(userRepository.findById(99L)).thenReturn(Optional.empty());

                ResponseStatusException ex = assertThrows(
                        ResponseStatusException.class,
                        () -> userService.deleteUser(99L));

                assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
                assertTrue(ex.getReason().contains("User not found"));
                verify(userRepository, never()).delete(any());
                verifyNoInteractions(fileRepository, fileService);
            }

            @Test
            @DisplayName("Позитивный: файл уже отсутствует на диске — сервис не падает")
            @Description("""
                    отказоустойчивость: файл уже отсутствует на диске —
                    deleteIfExists=false логируется, пользователь всё равно удаляется.
                    """)
            void deleteUser_fileAlreadyMissing() throws IOException {
                Path missing = tempDir.resolve("missing.txt"); // не создаём
                when(userRepository.findById(1L)).thenReturn(Optional.of(user));
                when(fileRepository.findByUser(user)).thenReturn(List.of(file1));
                when(fileService.resolveStoredFile(file1, user)).thenReturn(missing);

                userService.deleteUser(1L);

                verify(userRepository).delete(user);
                verify(fileService).resolveStoredFile(file1, user);
            }

            @Test
            @DisplayName("Граничный: null userId — findById(null) → пустой Optional → 404")
            @Description("""
                    Граничный: null userId → findById(null)=empty → 404 NOT_FOUND.
                    """)
            void deleteUser_nullId() {
                when(userRepository.findById(null)).thenReturn(Optional.empty());

                ResponseStatusException ex = assertThrows(
                        ResponseStatusException.class,
                        () -> userService.deleteUser(null));

                assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
            }
        }
    }
}