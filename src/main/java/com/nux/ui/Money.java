package com.nux.ui;

/**
 * Formats an amount of money with the user's currency symbol. Small hourly amounts keep a
 * third decimal, or 3.7 cents an hour would read as "0.04" and a whole month's
 * projection would look rounded out of thin air.
 */
public final class Money {

    private Money() {}

    public static String format(String currency, double amount) {
        String symbol = currency == null ? "" : currency.trim();
        return symbol + String.format(Math.abs(amount) < 0.10 && amount != 0 ? "%.3f" : "%.2f", amount);
    }
}
