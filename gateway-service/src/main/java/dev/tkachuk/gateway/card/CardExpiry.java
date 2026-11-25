package dev.tkachuk.gateway.card;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;

/** The month printed on the card; the card works until the end of that month. */
public record CardExpiry(YearMonth month) {

    public CardExpiry {
        Objects.requireNonNull(month, "month");
    }

    public static CardExpiry of(int month, int year) {
        if (month < 1 || month > 12) {
            throw new IllegalArgumentException("The expiry month must be 1 to 12, got " + month);
        }
        return new CardExpiry(YearMonth.of(year, month));
    }

    public boolean isExpiredOn(LocalDate date) {
        return YearMonth.from(date).isAfter(month);
    }

    /** As printed on the card, with the full year: 09/2030. */
    @Override
    public String toString() {
        return String.format("%02d/%d", month.getMonthValue(), month.getYear());
    }
}
