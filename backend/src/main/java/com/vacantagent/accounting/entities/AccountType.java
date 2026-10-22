package com.vacantagent.accounting.entities;

/** Every user has up to one account of each type, see Docs/accounting.md. */
public enum AccountType {
    /** Platform credit of a consumer: topped up via PayPal, spent on LLM usage, not paid out. */
    CREDIT,
    /** Earnings of a provider from sold tokens, paid out via PayPal. */
    EARNINGS
}
