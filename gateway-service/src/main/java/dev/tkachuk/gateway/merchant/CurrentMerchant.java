package dev.tkachuk.gateway.merchant;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/** The merchant whose API key came with the current request; ApiKeyFilter puts it there. */
public final class CurrentMerchant {

    private static final String ATTRIBUTE = CurrentMerchant.class.getName();

    private CurrentMerchant() {
    }

    public static Merchant get() {
        Object merchant = RequestContextHolder.currentRequestAttributes()
                .getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (merchant instanceof Merchant found) {
            return found;
        }
        throw new IllegalStateException("No merchant on this request: ApiKeyFilter must cover its path");
    }

    static void set(HttpServletRequest request, Merchant merchant) {
        request.setAttribute(ATTRIBUTE, merchant);
    }
}
