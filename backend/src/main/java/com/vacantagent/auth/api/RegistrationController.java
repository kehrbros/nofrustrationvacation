package com.vacantagent.auth.api;

import com.vacantagent.auth.logic.RegistrationLogic;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.regex.Pattern;

/**
 * Registration page of the IdP, linked from the login page. After registering, the user logs in and the
 * pending authorization request of the frontend continues.
 */
@Controller
@RequiredArgsConstructor
public class RegistrationController {
    private static final Pattern USERNAME_PATTERN = Pattern.compile("[a-zA-Z0-9._-]{3,64}");
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final RegistrationLogic registrationLogic;

    @GetMapping("/register")
    public String registrationForm() {
        return "auth/register";
    }

    @PostMapping("/register")
    public String register(@RequestParam String username, @RequestParam String password,
                           @RequestParam(required = false) String email, Model model) {
        String error = validate(username, password);
        if (error == null) {
            try {
                registrationLogic.register(username, password, email == null || email.isBlank() ? null : email.trim());
                return "redirect:/login?registered";
            } catch (RegistrationLogic.UsernameTakenException e) {
                error = "Username is already taken.";
            }
        }
        model.addAttribute("error", error);
        model.addAttribute("username", username);
        model.addAttribute("email", email);
        return "auth/register";
    }

    private static String validate(String username, String password) {
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            return "Username must have 3 to 64 characters (letters, digits, '.', '_', '-').";
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            return "Password must have at least " + MIN_PASSWORD_LENGTH + " characters.";
        }
        return null;
    }
}
