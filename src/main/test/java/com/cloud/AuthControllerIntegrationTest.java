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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
//AuthControllerIntegrationTest  →  AuthController + UserService + БД (без диска)
class AuthControllerIntegrationTest {


    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:cloud_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }


    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FileRepository fileRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void cleanUp() {
        fileRepository.deleteAll();
        userRepository.deleteAll();
    }


    @Nested
    @DisplayName("GET /register")
    class ShowRegisterForm {

        @Test
        @DisplayName("возвращает 200, view 'register', в модели пустой Users")
        void returnsRegisterView() throws Exception {
            mockMvc.perform(get("/register"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("register"))
                    .andExpect(model().attributeExists("user"))
                    .andExpect(model().attribute("user",
                            org.hamcrest.Matchers.instanceOf(Users.class)));
        }
    }


    @Nested
    @DisplayName("POST /register")
    class Register {

        @Test
        @DisplayName("успешная регистрация: пользователь реально в БД, пароль BCrypt, редирект")
        void createsUserAndRedirects() throws Exception {
            mockMvc.perform(post("/register")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .param("userName", "alice")
                            .param("password", "secret123")
                            .param("firstName", "Alice")
                            .param("lastName", "Smith"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/login?registered"));

            Optional<Users> saved = userRepository.findByUserName("alice");
            assertThat(saved).isPresent();

            Users user = saved.get();
            assertThat(user.getId()).isNotNull();
            assertThat(user.getFirstName()).isEqualTo("Alice");
            assertThat(user.getLastName()).isEqualTo("Smith");
            assertThat(user.getPassword())
                    .isNotEqualTo("secret123")
                    .startsWith("$2");
            assertThat(passwordEncoder.matches("secret123", user.getPassword())).isTrue();
        }

        @Test
        @DisplayName("дубликат username: 409 → форма с error, второй пользователь не создан")
        void duplicateUsername() throws Exception {
            userRepository.save(new Users(
                    "bob",
                    passwordEncoder.encode("secret123"),
                    "Bob", "Brown"));

            mockMvc.perform(post("/register")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .param("userName", "bob")
                            .param("password", "secret123")
                            .param("firstName", "Bob")
                            .param("lastName", "Brown"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("register"))
                    .andExpect(model().attribute("error", "Username already taken"));

            assertThat(userRepository.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("username короче 3 символов — ошибка валидации, в БД пусто")
        void invalidUsernameFails() throws Exception {
            mockMvc.perform(post("/register")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .param("userName", "ab")
                            .param("password", "secret123")
                            .param("firstName", "A")
                            .param("lastName", "B"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("register"))
                    .andExpect(model().attributeHasFieldErrors("user", "userName"));

            assertThat(userRepository.count()).isZero();
        }

        @Test
        @DisplayName("username с недопустимыми символами — ошибка валидации")
        void invalidCharsInUsername() throws Exception {
            mockMvc.perform(post("/register")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .param("userName", "bad user!")
                            .param("password", "secret123")
                            .param("firstName", "X")
                            .param("lastName", "Y"))
                    .andExpect(status().isOk())
                    .andExpect(model().attributeHasFieldErrors("user", "userName"));

            assertThat(userRepository.count()).isZero();
        }

        @Test
        @DisplayName("пароль короче 6 символов — ошибка валидации, в БД пусто")
        void shortPasswordFailsValidation() throws Exception {
            mockMvc.perform(post("/register")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .param("userName", "charlie")
                            .param("password", "123")
                            .param("firstName", "C")
                            .param("lastName", "D"))
                    .andExpect(status().isOk())
                    .andExpect(model().attributeHasFieldErrors("user", "password"));

            assertThat(userRepository.count()).isZero();
        }

        @Test
        @DisplayName("пустые firstName/lastName — ошибки валидации, в БД пусто")
        void blankNamesFailValidation() throws Exception {
            mockMvc.perform(post("/register")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .param("userName", "dave")
                            .param("password", "secret123")
                            .param("firstName", "")
                            .param("lastName", ""))
                    .andExpect(status().isOk())
                    .andExpect(model().attributeHasFieldErrors("user", "firstName", "lastName"));

            assertThat(userRepository.count()).isZero();
        }
    }


    @Test
    @DisplayName("GET /login возвращает представление 'login'")
    void loginPageRenders() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(view().name("login"));
    }


    @Nested
    @DisplayName("POST /account/delete")
    class DeleteAccount {

        @Test
        @DisplayName("аутентифицированный пользователь удаляется из БД вместе с файлами")
        void deletesAuthenticatedUserWithFiles() throws Exception {
            Users user = userRepository.save(new Users(
                    "frank",
                    passwordEncoder.encode("secret123"),
                    "Frank", "Green"));

            fileRepository.save(new UserFiles(
                    "notes.txt", 42L,
                    "00000000-0000-0000-0000-000000000001.bin",
                    "text/plain", user));

            UserDetailsImpl details = new UserDetailsImpl(user);

            mockMvc.perform(post("/account/delete")
                            .with(csrf())
                            .with(user(details)))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/login?deleted"));

            assertThat(userRepository.findById(user.getId())).isEmpty();
            assertThat(fileRepository.findByUser(user)).isEmpty();
        }

        @Test
        @DisplayName("удаление пользователя без файлов проходит успешно")
        void deletesUserWithoutFiles() throws Exception {
            Users user = userRepository.save(new Users(
                    "grace",
                    passwordEncoder.encode("secret123"),
                    "Grace", "Hopper"));

            UserDetailsImpl details = new UserDetailsImpl(user);

            mockMvc.perform(post("/account/delete")
                            .with(csrf())
                            .with(user(details)))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/login?deleted"));

            assertThat(userRepository.findById(user.getId())).isEmpty();
        }
    }
}