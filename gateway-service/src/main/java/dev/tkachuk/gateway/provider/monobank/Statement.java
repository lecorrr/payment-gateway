package dev.tkachuk.gateway.provider.monobank;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The part of monobank's statement (GET /api/merchant/statement) the gateway reads: each invoice's reference. */
@JsonIgnoreProperties(ignoreUnknown = true)
record Statement(@Nullable List<Item> list) {

    /** The id of the invoice charged with this reference, if the statement has one. */
    Optional<String> invoiceIdOf(UUID reference) {
        if (list == null) {
            return Optional.empty();
        }
        return list.stream()
                .filter(item -> item != null && reference.toString().equals(item.reference()))
                .map(Item::invoiceId)
                .filter(Objects::nonNull)
                .findFirst();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Item(@Nullable String invoiceId, @Nullable String reference) {
    }
}
