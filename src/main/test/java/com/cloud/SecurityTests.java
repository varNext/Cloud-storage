package com.cloud;

import com.cloud.model.Users;
import com.cloud.repository.UserRepository;
import com.cloud.security.UserDetailsImpl;
import com.cloud.security.UserDetailsServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SecurityTests {


    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:security_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
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
    private PasswordEncoder passwordEncoder;

    @Autowired
    private UserDetailsServiceImpl userDetailsService;

    private static final String USERNAME = "john";
    private static final String PASSWORD = "secret123";

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        userRepository.save(new Users(
                USERNAME,
                passwordEncoder.encode(PASSWORD),
                "John", "Doe"));
    }


    @Nested
    @DisplayName("Публичные эндпоинты (permitAll)")
    class PublicEndpoints {

        @Test
        @DisplayName("GET /register — 200 без логина")
        void registerIsPublic() throws Exception {
            mockMvc.perform(get("/register"))
                    .andExpect(status().isOk())
                    .andExpect(unauthenticated());
        }

        @Test
        @DisplayName("GET /login — 200 без логина")
        void loginIsPublic() throws Exception {
            mockMvc.perform(get("/login"))
                    .andExpect(status().isOk())
                    .andExpect(unauthenticated());
        }

        @Test
        @DisplayName("GET /error — доступен без логина")
        void errorIsPublic() throws Exception {
            mockMvc.perform(get("/error"))
                    .andExpect(status().is5xxServerError());
        }

        @Test
        @DisplayName("GET /css/** и /js/** — доступны без логина")
        void staticResourcesArePublic() throws Exception {
            mockMvc.perform(get("/css/style.css"))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get("/js/app.js"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("POST /register — доступен без логина (с CSRF)")
        void registerPostIsPublic() throws Exception {
            mockMvc.perform(post("/register")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .param("userName", "alice")
                            .param("password", "secret123")
                            .param("firstName", "Alice")
                            .param("lastName", "Smith"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/login?registered"));
        }
    }


    @Nested
    @DisplayName("Защищённые эндпоинты (anyRequest().authenticated())")
    class ProtectedEndpoints {

        @Test
        @DisplayName("GET /files — редирект на /login")
        void filesRedirectsToLogin() throws Exception {
            mockMvc.perform(get("/files"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrlPattern("**/login"))
                    .andExpect(unauthenticated());
        }

        @Test
        @DisplayName("GET /files/download/1 — редирект на /login")
        void downloadRedirectsToLogin() throws Exception {
            mockMvc.perform(get("/files/download/1"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrlPattern("**/login"))
                    .andExpect(unauthenticated());
        }

        @Test
        @DisplayName("POST /files/upload — редирект на /login, файл не создан")
        void uploadRedirectsToLogin() throws Exception {
            mockMvc.perform(post("/files/upload").with(csrf()))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrlPattern("**/login"))
                    .andExpect(unauthenticated());
        }

        @Test
        @DisplayName("POST /account/delete — редирект на /login, пользователь не удалён")
        void deleteAccountRedirectsToLogin() throws Exception {
            mockMvc.perform(post("/account/delete").with(csrf()))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrlPattern("**/login"))
                    .andExpect(unauthenticated());

            assertThat(userRepository.findByUserName(USERNAME)).isPresent();
        }

        @Test
        @DisplayName("несуществующий URL — тоже требует аутентификации (anyRequest)")
        void unknownUrlRequiresAuth() throws Exception {
            mockMvc.perform(get("/some/unknown/path"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrlPattern("**/login"));
        }
    }


    @Nested
    @DisplayName("formLogin")
    class FormLogin {

        @Test
        @DisplayName("верные credentials — аутентификация, редирект на /files")
        void loginSuccess() throws Exception {
            mockMvc.perform(formLogin("/login")
                            .user(USERNAME)
                            .password(PASSWORD))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/files"))
                    .andExpect(authenticated().withUsername(USERNAME));
        }

        @Test
        @DisplayName("неверный пароль — редирект на /login?error, не аутентифицирован")
        void loginWrongPassword() throws Exception {
            mockMvc.perform(formLogin("/login")
                            .user(USERNAME)
                            .password("wrong-password"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/login?error"))
                    .andExpect(unauthenticated());
        }

        @Test
        @DisplayName("несуществующий пользователь — редирект на /login?error")
        void loginUnknownUser() throws Exception {
            mockMvc.perform(formLogin("/login")
                            .user("ghost")
                            .password("whatever"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/login?error"))
                    .andExpect(unauthenticated());
        }

        @Test
        @DisplayName("пустые credentials — редирект на /login?error")
        void loginEmptyCredentials() throws Exception {
            mockMvc.perform(formLogin("/login")
                            .user("")
                            .password(""))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/login?error"))
                    .andExpect(unauthenticated());
        }

        @Test
        @DisplayName("после успешного логина GET /files доступен")
        void afterLoginFilesAccessible() throws Exception {
            MvcResult loginResult = mockMvc.perform(formLogin("/login")
                            .user(USERNAME)
                            .password(PASSWORD))
                    .andExpect(authenticated())
                    .andReturn();

            MockHttpSession session =
                    (MockHttpSession) loginResult.getRequest().getSession(false);
            assertThat(session).isNotNull();

            mockMvc.perform(get("/files").session(session))
                    .andExpect(status().isOk())
                    .andExpect(authenticated().withUsername(USERNAME));
        }
    }

    // =====================================================================
    // 4. logout — POST /logout с CSRF, сессия инвалидируется
    // =====================================================================

    @Nested
    @DisplayName("logout")
    class Logout {

        @Test
        @DisplayName("POST /logout — редирект на /login?logout, не аутентифицирован")
        void logoutSuccess() throws Exception {
            MvcResult loginResult = mockMvc.perform(formLogin("/login")
                            .user(USERNAME)
                            .password(PASSWORD))
                    .andExpect(authenticated())
                    .andReturn();

            MockHttpSession session =
                    (MockHttpSession) loginResult.getRequest().getSession(false);
            assertThat(session).isNotNull();

            mockMvc.perform(post("/logout").session(session).with(csrf()))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/login?logout"))
                    .andExpect(unauthenticated());
        }

        @Test
        @DisplayName("после logout GET /files снова редиректит на /login")
        void afterLogoutFilesRedirects() throws Exception {
            MvcResult loginResult = mockMvc.perform(formLogin("/login")
                            .user(USERNAME)
                            .password(PASSWORD))
                    .andReturn();

            MockHttpSession session =
                    (MockHttpSession) loginResult.getRequest().getSession(false);
            assertThat(session).isNotNull();

            mockMvc.perform(post("/logout").session(session).with(csrf()));

            mockMvc.perform(get("/files").session(session))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrlPattern("**/login"));
        }

        @Test
        @DisplayName("POST /logout без CSRF — 403, сессия остаётся аутентифицированной")
        void logoutWithoutCsrfForbidden() throws Exception {
            MvcResult loginResult = mockMvc.perform(formLogin("/login")
                            .user(USERNAME)
                            .password(PASSWORD))
                    .andReturn();

            MockHttpSession session =
                    (MockHttpSession) loginResult.getRequest().getSession(false);

            mockMvc.perform(post("/logout").session(session))
                    .andExpect(status().isForbidden());

            mockMvc.perform(get("/files").session(session))
                    .andExpect(authenticated().withUsername(USERNAME));
        }
    }


    @Nested
    @DisplayName("CSRF")
    class Csrf {

        @Test
        @DisplayName("POST /register без CSRF — 403, пользователь не создан")
        void registerWithoutCsrfForbidden() throws Exception {
            mockMvc.perform(post("/register")
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .param("userName", "alice")
                            .param("password", "secret123")
                            .param("firstName", "Alice")
                            .param("lastName", "Smith"))
                    .andExpect(status().isForbidden());

            assertThat(userRepository.findByUserName("alice")).isEmpty();
        }



        @Test
        @DisplayName("GET-запросы не требуют CSRF")
        void getRequestsDoNotRequireCsrf() throws Exception {
            mockMvc.perform(get("/register"))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/login"))
                    .andExpect(status().isOk());
        }
    }


    @Nested
    @DisplayName("UserDetailsServiceImpl / UserDetailsImpl")
    class UserDetailsTests {

        @Test
        @DisplayName("loadUserByUsername: существующий пользователь — корректный UserDetails")
        void loadExistingUser() {
            assertThat(userDetailsService).isNotNull();

            UserDetails details = userDetailsService.loadUserByUsername(USERNAME);

            assertThat(details).isInstanceOf(UserDetailsImpl.class);
            assertThat(details.getUsername()).isEqualTo(USERNAME);
            assertThat(details.getPassword()).startsWith("$2");
            assertThat(details.getAuthorities())
                    .extracting(GrantedAuthority::getAuthority)
                    .containsExactly("USER");
            assertThat(details.isEnabled()).isTrue();
            assertThat(details.isAccountNonExpired()).isTrue();
            assertThat(details.isAccountNonLocked()).isTrue();
            assertThat(details.isCredentialsNonExpired()).isTrue();
        }

        @Test
        @DisplayName("loadUserByUsername: неизвестный пользователь — UsernameNotFoundException")
        void loadUnknownUser() {
            assertThrows(
                    UsernameNotFoundException.class,
                    () -> userDetailsService.loadUserByUsername("ghost"));
        }

        @Test
        @DisplayName("UserDetailsImpl.getId() возвращает id из Users")
        void userDetailsId() {
            Users user = userRepository.findByUserName(USERNAME).orElseThrow();
            var details = new UserDetailsImpl(user);

            assertThat(details.getId()).isNotNull().isEqualTo(user.getId());
            assertThat(details.getUsername()).isEqualTo(USERNAME);
        }
    }


    @Nested
    @DisplayName("PasswordEncoder (BCrypt)")
    class Encoder {

        @Test
        @DisplayName("BCrypt: хеш не равен паролю, matches работает")
        void bcryptWorks() {
            String raw = "secret123";
            String encoded = passwordEncoder.encode(raw);

            assertThat(encoded).isNotEqualTo(raw).startsWith("$2");
            assertThat(passwordEncoder.matches(raw, encoded)).isTrue();
            assertThat(passwordEncoder.matches("wrong", encoded)).isFalse();
        }

        @Test
        @DisplayName("BCrypt: два хеша одного пароля разные ")
        void bcryptIsSalted() {
            String h1 = passwordEncoder.encode("secret123");
            String h2 = passwordEncoder.encode("secret123");
            assertThat(h1).isNotEqualTo(h2);
            assertThat(passwordEncoder.matches("secret123", h1)).isTrue();
            assertThat(passwordEncoder.matches("secret123", h2)).isTrue();
        }
    }
}