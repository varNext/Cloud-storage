package com.cloud;

import com.cloud.controller.AuthController;
import com.cloud.model.RegisterForm;
import com.cloud.model.Users;
import com.cloud.security.UserDetailsImpl;
import com.cloud.services.UserService;
import io.qameta.allure.Description;
import io.qameta.allure.Story;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Модульные тесты для контроллера авторизации")
class AuthControllerModuleTests {

    @Mock
    private UserService userService;

    @InjectMocks
    private AuthController authController;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }


    private RegisterForm validForm() {
        RegisterForm f = new RegisterForm();
        f.setUserName("alice");
        f.setPassword("secret1");
        f.setFirstName("Alice");
        f.setLastName("Smith");
        return f;
    }

    private BindingResult bindingResultFor(RegisterForm form) {
        return new BeanPropertyBindingResult(form, "user");
    }


    @Test
    @Story("Показ формы регистрации")
    @DisplayName("GET /register кладёт пустую форму в модель, если её нет")
    @Description("""
            Когда модель пуста:
            1. Контроллер видит, что атрибута 'user' нет.
            2. Кладёт новый RegisterForm в модель.
            3. Возвращает view 'register'.
            4. Не обращается к UserService.
            """)
    void showRegisterForm_addsEmptyFormToModel() {
        Model model = new ExtendedModelMap();

        String view = authController.showRegisterForm(model);

        assertThat(view).isEqualTo("register");
        assertThat(model.getAttribute("user")).isInstanceOf(Users.class);
        verifyNoInteractions(userService);
    }

    @Test
    @Story("Показ формы регистрации")
    @DisplayName("GET /register не перезатирает существующую форму в модели")
    @Description("""
            Когда в модели уже есть атрибут 'user' (например, после ошибки валидации):
            1. Контроллер не создаёт новый объект.
            2. Возвращает view 'register'.
            3. Не обращается к UserService.
            """)
    void showRegisterForm_doesNotOverrideExistingForm() {
        Model model = new ExtendedModelMap();
        RegisterForm existing = validForm();
        model.addAttribute("user", existing);

        String view = authController.showRegisterForm(model);

        assertThat(view).isEqualTo("register");
        assertThat(model.getAttribute("user")).isSameAs(existing);
        verifyNoInteractions(userService);
    }


    @Test
    @Story("Приём формы регистрации")
    @DisplayName("POST /register: ошибки валидации → view 'register', сервис не вызывается")
    @Description("""
            Когда BindingResult содержит ошибки:
            1. Контроллер возвращает 'register' (без redirect).
            2. UserService.createUser НЕ вызывается.
            3. В модель не кладётся 'error' вручную — ошибки уже в BindingResult.
            """)
    void register_validationErrors() {
        RegisterForm form = new RegisterForm();      // пустая — все @NotBlank сработают
        BindingResult bindingResult = bindingResultFor(form);
        bindingResult.rejectValue("userName", "NotBlank", "Username is required");

        Model model = new ExtendedModelMap();

        String view = authController.register(form, bindingResult, model);

        assertThat(view).isEqualTo("register");
        assertThat(model.containsAttribute("error")).isFalse();
        verifyNoInteractions(userService);
    }

    @Test
    @Story("Приём формы регистрации")
    @DisplayName("POST /register при успехе редиректит на /login?registered")
    @Description("""
            Успешная регистрация:
            1. UserService.createUser(form) возвращает сохранённого пользователя.
            2. Контроллер возвращает redirect:/login?registered (PRG).
            3. В модель НЕ кладётся 'error'.
            4. createUser вызван ровно один раз с той же формой.
            """)
    void register_success_redirects() {
        RegisterForm form = validForm();
        BindingResult bindingResult = bindingResultFor(form);
        Model model = new ExtendedModelMap();

        when(userService.createUser(any(RegisterForm.class)))
                .thenReturn(new Users("alice", "hash", "Alice", "Smith"));

        String view = authController.register(form, bindingResult, model);

        assertThat(view).isEqualTo("redirect:/login?registered");
        assertThat(model.containsAttribute("error")).isFalse();
        verify(userService, times(1)).createUser(form);
        verifyNoMoreInteractions(userService);
    }

    @Test
    @Story("Приём формы регистрации")
    @DisplayName("POST /register при занятом username возвращает форму с ошибкой")
    @Description("""
            Конфликт имени пользователя:
            1. UserService.createUser кидает ResponseStatusException(409).
            2. Контроллер ловит исключение и кладёт reason в модель как 'error'.
            3. Возвращается view 'register', а не redirect.
            4. createUser вызван один раз с исходной формой.
            """)
    void register_duplicateUsername() {
        RegisterForm form = validForm();
        BindingResult bindingResult = bindingResultFor(form);
        Model model = new ExtendedModelMap();

        ResponseStatusException conflict = new ResponseStatusException(
                HttpStatus.CONFLICT, "Username already taken: alice");
        when(userService.createUser(any(RegisterForm.class))).thenThrow(conflict);

        String view = authController.register(form, bindingResult, model);

        assertThat(view).isEqualTo("register");
        assertThat(model.getAttribute("error"))
                .isEqualTo("Username already taken: alice");
        verify(userService, times(1)).createUser(form);
    }

    @Test
    @Story("Приём формы регистрации")
    @DisplayName("POST /register при прочих ошибках сервиса (400) возвращает форму с ошибкой")
    @Description("""
            Ошибка бизнес-логики (например, 400 от сервиса):
            1. Контроллер ловит ResponseStatusException.
            2. Кладёт reason в модель как 'error'.
            3. Возвращает view 'register'.
            """)
    void register_serviceBadRequest() {
        RegisterForm form = validForm();
        BindingResult bindingResult = bindingResultFor(form);
        Model model = new ExtendedModelMap();

        ResponseStatusException badRequest = new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Invalid username");
        when(userService.createUser(any(RegisterForm.class))).thenThrow(badRequest);

        String view = authController.register(form, bindingResult, model);

        assertThat(view).isEqualTo("register");
        assertThat(model.getAttribute("error")).isEqualTo("Invalid username");
        verify(userService, times(1)).createUser(form);
    }

    @Test
    @Story("Страница входа")
    @DisplayName("GET /login возвращает view 'login'")
    @Description("""
            Сама аутентификация выполняется Spring Security,
            контроллер лишь отдаёт шаблон login.html.
            """)
    void loginPage_returnsLoginView() {
        String view = authController.loginPage();

        assertThat(view).isEqualTo("login");
        verifyNoInteractions(userService);
    }


    @Test
    @Story("Удаление аккаунта")
    @DisplayName("POST /account/delete удаляет пользователя и инвалидирует сессию")
    @Description("""
            Успешное удаление собственного аккаунта:
            1. Principal содержит id аутентифицированного пользователя.
            2. UserService.deleteUser вызывается с этим id.
            3. SecurityContextHolder очищается.
            4. HttpSession инвалидируется (если существует).
            5. Возвращается redirect:/login?deleted.
            """)
    void deleteAccount() {
        Long userId = 42L;
        UserDetailsImpl principal = mock(UserDetailsImpl.class);
        when(principal.getId()).thenReturn(userId);

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpSession session = mock(HttpSession.class);
        when(request.getSession(false)).thenReturn(session);

        String view = authController.deleteAccount(principal, request);

        assertThat(view).isEqualTo("redirect:/login?deleted");
        verify(userService, times(1)).deleteUser(userId);
        verify(request).getSession(false);
        verify(session).invalidate();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @Story("Удаление аккаунта")
    @DisplayName("POST /account/delete не падает, если сессии нет")
    @Description("""
            Граничный случай: request.getSession(false) вернул null.
            Контроллер не должен кидать NPE — проверка if (session != null)
            защищает от этого. Удаление пользователя всё равно происходит.
            """)
    void deleteAccount_sessionIsNull() {
        Long userId = 7L;
        UserDetailsImpl principal = mock(UserDetailsImpl.class);
        when(principal.getId()).thenReturn(userId);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getSession(false)).thenReturn(null);

        String view = authController.deleteAccount(principal, request);

        assertThat(view).isEqualTo("redirect:/login?deleted");
        verify(userService).deleteUser(userId);
        verify(request).getSession(false);
    }

    @Test
    @Story("Удаление аккаунта")
    @DisplayName("POST /account/delete без principal → 401 Unauthorized")
    @Description("""
            Защита от неаутентифицированного запроса:
            1. principal == null.
            2. Контроллер кидает ResponseStatusException(401).
            3. UserService.deleteUser НЕ вызывается.
            4. request.getSession НЕ вызывается.
            """)
    void deleteAccount_noPrincipal() {
        HttpServletRequest request = mock(HttpServletRequest.class);

        assertThatThrownBy(() -> authController.deleteAccount(null, request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(rse.getReason()).isEqualTo("Not authenticated");
                });

        verifyNoInteractions(userService);
        verifyNoInteractions(request);
    }

    @Test
    @Story("Удаление аккаунта")
    @DisplayName("POST /account/delete с principal без id → 401 Unauthorized")
    @Description("""
            Защита от principal != null, но getId() == null.
            Контроллер кидает ResponseStatusException(401).
            """)
    void deleteAccount_principalWithoutId() {
        UserDetailsImpl principal = mock(UserDetailsImpl.class);
        when(principal.getId()).thenReturn(null);

        HttpServletRequest request = mock(HttpServletRequest.class);

        assertThatThrownBy(() -> authController.deleteAccount(principal, request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));

        verify(userService, never()).deleteUser(anyLong());
        verifyNoInteractions(request);
    }

    @Test
    @DisplayName("POST /register: ResponseStatusException без reason — error = null")
    @Description("""
            Граничный: reason == null → в модель кладётся null.
            Шаблон должен корректно это обработать (th:if="${error}").
            """)
    void register_conflictWithoutReason_errorIsNull() {
        RegisterForm form = validForm();
        BindingResult bindingResult = bindingResultFor(form);
        Model model = new ExtendedModelMap();

        when(userService.createUser(any(RegisterForm.class)))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT));

        String view = authController.register(form, bindingResult, model);

        assertThat(view).isEqualTo("register");
        assertThat(model.getAttribute("error")).isNull();
    }

    @Test
    @DisplayName("POST /account/delete: session.invalidate() бросает — исключение проксируется")
    @Description("""
            Граничный: session.invalidate() бросает IllegalStateException
            (сессия уже инвалидирована) — контроллер не ловит, но SecurityContext
            уже очищен (clearContext() вызван до invalidate()).
            """)
    void deleteAccount_invalidateThrows_propagates() {
        Long userId = 42L;
        UserDetailsImpl principal = mock(UserDetailsImpl.class);
        when(principal.getId()).thenReturn(userId);

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpSession session = mock(HttpSession.class);
        when(request.getSession(false)).thenReturn(session);
        doThrow(new IllegalStateException("session already invalidated")).when(session).invalidate();

        assertThatThrownBy(() -> authController.deleteAccount(principal, request))
                .isInstanceOf(IllegalStateException.class);

        verify(userService).deleteUser(userId);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}