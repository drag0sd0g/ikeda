package com.ikeda.store;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

final class Sql {
    @FunctionalInterface
    interface RowMapper<T> {
        T map(ResultSet row) throws SQLException;
    }

    @FunctionalInterface
    interface Binder {
        Binder NONE = statement -> { };

        void bind(PreparedStatement statement) throws SQLException;
    }

    private Sql() {
    }
}
