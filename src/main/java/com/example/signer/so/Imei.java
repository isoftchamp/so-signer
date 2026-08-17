package com.example.signer.so;

/**
 * Validates and completes an IMEI using its standard Luhn check digit.
 */
final class Imei {

    private static final int BODY_LENGTH = 14;

    private Imei() {
    }

    static String complete(String value) {
        String digits = value == null ? "" : value.strip();
        if (!digits.matches("\\d{14,15}")) {
            throw new IllegalArgumentException(
                    "IMEI must contain 14 digits; the check digit is calculated automatically.");
        }

        String body = digits.substring(0, BODY_LENGTH);
        return body + calculateCheckDigit(body);
    }

    private static int calculateCheckDigit(String body) {
        int sum = 0;
        for (int index = 0; index < BODY_LENGTH; index++) {
            int digit = body.charAt(index) - '0';
            if ((index & 1) == 1) {
                digit *= 2;
                digit = digit / 10 + digit % 10;
            }
            sum += digit;
        }
        return (10 - sum % 10) % 10;
    }
}
