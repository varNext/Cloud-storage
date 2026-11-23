package com.cloud.controller;

import com.cloud.model.RegisterForm;
import com.cloud.model.Users;
import com.cloud.security.UserDetailsImpl;
import com.cloud.services.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Controller
@RequestMapping
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    /**
     * Форма регистрации.
     */
    @GetMapping("/register")
    public String showRegisterForm(Model model) {
        if (!model.containsAttribute("user")) {
            model.addAttribute("user", new Users());
        }
        return "register";
    }


    /**
     * Приём формы регистрации.
     */
    @PostMapping("/register")
    public String register(@Valid @ModelAttribute("user") RegisterForm form,
                           BindingResult bindingResult,
                           Model model) {
        if (bindingResult.hasErrors()) {
            return "register";
        }

        try {
            userService.createUser(form);
            return "redirect:/login?registered";
        } catch (ResponseStatusException e) {
            model.addAttribute("error", e.getReason());
            return "register";
        }
    }

    /**
     * Страница входа. Саму аутентификацию выполняет Spring Security.
     */
    @GetMapping("/login")
    public String loginPage() {
        return "login";
    }


    /**
     * Удаление аккаунта вместе с файлами.
     */
    @PostMapping("/account/delete")
    public String deleteAccount(@AuthenticationPrincipal UserDetailsImpl principal,
                                HttpServletRequest request) {
        if (principal == null || principal.getId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }

        Long userId = principal.getId();
        log.info("Пользователь id={} удаляет аккаунт", userId);

        userService.deleteUser(userId);

        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }

        return "redirect:/login?deleted";
    }
}