package dev.tkachuk.gateway.common;

/** The currencies a merchant can charge in. Amounts are in minor units; all three have two decimals. */
public enum Currency {
    UAH(980),
    USD(840),
    EUR(978);

    private final int numericCode;

    Currency(int numericCode) {
        this.numericCode = numericCode;
    }

    /** The ISO 4217 numeric code, which some providers take instead of the letters (980 for UAH). */
    public int numericCode() {
        return numericCode;
    }
}
