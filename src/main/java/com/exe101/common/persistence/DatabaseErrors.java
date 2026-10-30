package com.exe101.common.persistence;

import java.sql.SQLException;
import java.util.Set;

public final class DatabaseErrors {
    // SQL Server duplicate-key errors: unique index (2601) and unique or primary-key constraint (2627).
    private static Set<Integer> duplicateKeyErrors = Set.of(2601, 2627);

    private DatabaseErrors() {
        throw new UnsupportedOperationException("This class should never be instantiated");
    }

    public static boolean isDuplicateKey(Throwable exception) {
        return duplicateKeyErrors.contains(errorCode(exception));
    }

    public static boolean hasErrorCode(Throwable exception, int errorCode) {
        return errorCode(exception) == errorCode;
    }

    private static int errorCode(Throwable exception) {
        Throwable cause = exception;

        while (cause != null) {
            if (cause instanceof SQLException sql) {
                return sql.getErrorCode();
            }

            cause = cause.getCause();
        }

        return 0;
    }
}
