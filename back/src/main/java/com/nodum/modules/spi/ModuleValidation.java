package com.nodum.modules.spi;

import java.util.List;

public record ModuleValidation(boolean valid, List<String> errors) {
    public ModuleValidation {
        errors = List.copyOf(errors == null ? List.of() : errors);
        if (valid && !errors.isEmpty()) throw new IllegalArgumentException("A valid result cannot contain errors");
        if (!valid && errors.isEmpty()) throw new IllegalArgumentException("An invalid result must explain why");
    }

    public static ModuleValidation ok() { return new ModuleValidation(true, List.of()); }
    public static ModuleValidation invalid(String error) { return new ModuleValidation(false, List.of(error)); }
}
