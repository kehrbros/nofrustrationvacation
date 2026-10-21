package com.vacantagent.user.api;

import com.vacantagent.user.dto.AppUserDto;
import com.vacantagent.user.security.CurrentUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    @GetMapping("/me")
    public AppUserDto getProfile(@AuthenticationPrincipal CurrentUser currentUser) {
        return new AppUserDto(currentUser.appUserId(), currentUser.username());
    }
}
