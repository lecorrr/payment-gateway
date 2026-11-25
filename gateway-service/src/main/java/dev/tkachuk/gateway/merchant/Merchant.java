package dev.tkachuk.gateway.merchant;

import java.util.UUID;

public record Merchant(UUID id, String code, String name) {
}
