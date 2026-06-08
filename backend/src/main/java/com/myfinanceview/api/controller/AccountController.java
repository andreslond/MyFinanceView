package com.myfinanceview.api.controller;

import com.myfinanceview.api.dto.AccountDTO;
import com.myfinanceview.domain.account.AccountService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for {@code GET /api/v1/accounts}.
 *
 * <p>Returns the list of accounts belonging to the authenticated user. The {@code name} field in
 * the response is mapped from {@code accounts.nickname} in the schema (design.md D6 — no DB rename,
 * mapper handles it).
 */
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping
    public ResponseEntity<List<AccountDTO>> listAccounts(
        @AuthenticationPrincipal UUID userId
    ) {
        return ResponseEntity.ok(accountService.listForUser(userId));
    }
}
