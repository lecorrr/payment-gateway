package dev.tkachuk.gateway.risk;

/** What the risk check means for a new payment. */
public enum RiskVerdict {

    /** Charge it: risk-service allowed it, or couldn't answer and gateway.risk.when-unavailable is allow. */
    ALLOW,

    /** Don't charge it: risk-service declined it. The payment is DECLINED with risk_declined. */
    DECLINE,

    /**
     * Don't charge it: risk-service couldn't answer and gateway.risk.when-unavailable is decline. The payment is
     * FAILED with risk_unavailable.
     */
    FAIL;

    public static final String DECLINED_CODE = "risk_declined";
    public static final String UNAVAILABLE_CODE = "risk_unavailable";
}
