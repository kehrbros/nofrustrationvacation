package com.vacantagent.auth.api;

import com.vacantagent.auth.logic.ConnectionLogic;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Page of the IdP listing the applications (MCP clients) connected to the account, with revoke.
 */
@Controller
@RequiredArgsConstructor
public class ConnectionsController {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private final ConnectionLogic connectionLogic;

    @GetMapping("/account/connections")
    public String connections(Authentication user, Model model) {
        model.addAttribute("username", user.getName());
        model.addAttribute("connections", connectionLogic.connections(user.getName()).stream()
                .map(connection -> new View(connection, connection.scopes().stream().map(Scopes::describe).toList(),
                        connection.lastIssuedAt() == null ? "" : TIME.format(connection.lastIssuedAt())))
                .toList());
        return "auth/connections";
    }

    @PostMapping("/account/connections/revoke")
    public String revoke(@RequestParam String id, Authentication user) {
        connectionLogic.revoke(user.getName(), id);
        return "redirect:/account/connections?revoked";
    }

    record View(ConnectionLogic.Connection connection, List<Scopes.Scope> scopes, String lastIssuedAt) {
    }
}
