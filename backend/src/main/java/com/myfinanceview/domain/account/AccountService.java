package com.myfinanceview.domain.account;

import com.myfinanceview.api.dto.AccountDTO;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Read-only service for {@code GET /api/v1/accounts}. Thin wrapper over
 * {@link AccountRepository} that maps rows to {@link AccountDTO}s.
 *
 * <p><b>Isolation invariant</b> (design.md D2): every method takes a {@code userId} and forwards
 * it to the repository, which filters with {@code WHERE user_id = ?}.
 */
@Service
public class AccountService {

    private final AccountRepository repo;

    public AccountService(AccountRepository repo) {
        this.repo = repo;
    }

    /**
     * Return the user's accounts ordered by display name (matches the repository's
     * {@code ORDER BY nickname ASC} since the mapper renames {@code nickname → name}).
     */
    public List<AccountDTO> listForUser(UUID userId) {
        return repo.findAllByUserId(userId).stream()
            .map(AccountMapper::fromRecord)
            .toList();
    }
}
