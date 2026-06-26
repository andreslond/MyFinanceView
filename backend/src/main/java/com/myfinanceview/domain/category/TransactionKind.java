package com.myfinanceview.domain.category;

/**
 * Pure-domain mirror of the {@code transaction_type} DB enum (V001).
 * The service layer converts jOOQ's generated enum to this type before
 * entering the domain — no jOOQ import is allowed inside {@code domain/**}.
 */
public enum TransactionKind {
    CREDIT_CARD_PURCHASE,
    DEBIT_PURCHASE,
    CREDIT_CARD_PAYMENT,
    INCOMING_TRANSFER,
    OUTGOING_TRANSFER,
    INCOMING_PAYMENT
}
