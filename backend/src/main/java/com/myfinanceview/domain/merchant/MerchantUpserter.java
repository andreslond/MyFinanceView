package com.myfinanceview.domain.merchant;

import com.myfinanceview.jooq.generated.tables.records.MerchantsRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Applies the merchant feedback loop after a user re-categorises a transaction (design.md D5).
 *
 * <p>Branching:
 * <ul>
 *   <li><b>Branch A — transaction already has a merchant.</b>
 *     <ul>
 *       <li>If the merchant's current category equals the new one → <b>re-confirm</b>:
 *           {@code confidence += 0.10} capped at {@code 1.00}, {@code match_count += 1},
 *           {@code last_confirmed_at = NOW()}.</li>
 *       <li>If the categories differ → <b>drift reset</b>: {@code category_id = new},
 *           {@code confidence = 0.50}, {@code match_count = 1}, {@code last_confirmed_at = NOW()}.
 *           {@code display_name} is preserved. A structured INFO log is emitted with
 *           {@code event=merchant_drift_reset} containing only ids (no amount, no description).</li>
 *     </ul>
 *   </li>
 *   <li><b>Branch B — transaction has no merchant yet (first learning).</b>
 *       Normalize the description via {@link MerchantNormalizer}, UPSERT by
 *       {@code (user_id, raw_pattern)} with {@code confidence = 0.50}, {@code match_count = 1}.
 *       The UPSERT is idempotent: a race against an n8n insert with the same normalized pattern
 *       resolves to a single merchant row.</li>
 * </ul>
 *
 * <p>This component MUST be called from within the calling service's {@code @Transactional}
 * boundary so the merchant mutation and the {@code UPDATE transactions} commit atomically.
 */
@Component
public class MerchantUpserter {

    private static final Logger log = LoggerFactory.getLogger(MerchantUpserter.class);

    private final MerchantRepository merchants;

    public MerchantUpserter(MerchantRepository merchants) {
        this.merchants = merchants;
    }

    /**
     * Apply the feedback loop and return the merchant id that the transaction should point to
     * after this PATCH.
     *
     * @param userId               authenticated user id (owner of the transaction & merchant).
     * @param txCurrentMerchantId  the merchant id currently on the transaction, may be null.
     * @param txDescription        the transaction description (used only when Branch B is taken to
     *                             derive {@code raw_pattern} via {@link MerchantNormalizer}).
     * @param newCategoryId        the category the user is assigning to the transaction.
     * @return the merchant id the transaction now belongs to. In Branch A this is the unchanged
     *         {@code txCurrentMerchantId}; in Branch B this is the UPSERTed merchant id.
     * @throws IllegalStateException if Branch A's merchant lookup fails (merchant id on the
     *         transaction but no row visible — should never happen, signals corruption).
     */
    public UUID applyFeedback(UUID userId, UUID txCurrentMerchantId, String txDescription, UUID newCategoryId) {
        if (txCurrentMerchantId != null) {
            // Branch A — merchant exists for this transaction.
            Optional<MerchantsRecord> opt = merchants.findById(txCurrentMerchantId, userId);
            if (opt.isEmpty()) {
                // Should not happen: tx.merchant_id is owned by the same user (FK + same user_id
                // invariant). Treat as a corruption signal — rollback the @Transactional.
                throw new IllegalStateException("merchant referenced by transaction not found");
            }
            MerchantsRecord merchant = opt.get();
            UUID currentCategoryId = merchant.getCategoryId();

            if (Objects.equals(currentCategoryId, newCategoryId)) {
                // Re-confirmation: same category as before. Bump confidence + match_count.
                int affected = merchants.confirmCategory(merchant.getId(), userId);
                if (affected != 1) {
                    throw new IllegalStateException(
                        "confirmCategory affected " + affected + " rows, expected 1");
                }
            } else {
                // Drift: user disagrees with the learned category. Reset the merchant.
                int affected = merchants.resetForDrift(merchant.getId(), userId, newCategoryId);
                if (affected != 1) {
                    throw new IllegalStateException(
                        "resetForDrift affected " + affected + " rows, expected 1");
                }
                // Structured INFO log — ids only, never amount/description (design D5 + standards §6).
                log.info("event=merchant_drift_reset merchant_id={} old_category_id={} new_category_id={} user_id={}",
                    merchant.getId(), currentCategoryId, newCategoryId, userId);
            }
            return merchant.getId();
        }

        // Branch B — first learning: derive raw_pattern from description and UPSERT.
        String rawPattern = MerchantNormalizer.normalize(txDescription);
        return merchants.upsertByRawPattern(userId, rawPattern, txDescription, newCategoryId);
    }
}
