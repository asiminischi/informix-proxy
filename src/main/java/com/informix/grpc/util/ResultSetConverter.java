package com.informix.grpc.util;

import com.informix.grpc.ColumnMetadata;
import com.informix.grpc.Value;
import com.google.protobuf.ByteString;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts JDBC result sets into gRPC Value / ColumnMetadata messages.
 */
public final class ResultSetConverter {

    private ResultSetConverter() {}

    /**
     * Builds a list of ColumnMetadata from the current ResultSet's metadata.
     */
    public static List<ColumnMetadata> extractColumnMetadata(ResultSetMetaData meta) throws SQLException {
        int columnCount = meta.getColumnCount();
        List<ColumnMetadata> columns = new ArrayList<>(columnCount);

        for (int i = 1; i <= columnCount; i++) {
            columns.add(ColumnMetadata.newBuilder()
                    .setName(meta.getColumnName(i))
                    .setType(meta.getColumnTypeName(i))
                    .setPrecision(meta.getPrecision(i))
                    .setScale(meta.getScale(i))
                    .setNullable(meta.isNullable(i) == ResultSetMetaData.columnNullable)
                    .build());
        }
        return columns;
    }

    /**
     * Converts the value at the given column index from the current row into a gRPC Value.
     */
    public static Value convertValue(ResultSet rs, int columnIndex, int sqlType) throws SQLException {
        Value.Builder builder = Value.newBuilder();
        Object value = rs.getObject(columnIndex);

        if (value == null || rs.wasNull()) {
            return builder.setIsNull(true).build();
        }

        switch (sqlType) {
            case Types.VARCHAR:
            case Types.CHAR:
            case Types.LONGVARCHAR:
            case Types.CLOB:
                builder.setStringData(rs.getString(columnIndex));
                break;
            case Types.INTEGER:
            case Types.SMALLINT:
            case Types.TINYINT:
                builder.setIntData(rs.getInt(columnIndex));
                break;
            case Types.BIGINT:
                builder.setLongData(rs.getLong(columnIndex));
                break;
            case Types.DECIMAL:
            case Types.NUMERIC:
            case Types.REAL:
            case Types.FLOAT:
            case Types.DOUBLE:
                builder.setDoubleData(rs.getDouble(columnIndex));
                break;
            case Types.BOOLEAN:
            case Types.BIT:
                builder.setBoolData(rs.getBoolean(columnIndex));
                break;
            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
            case Types.BLOB:
                byte[] bytes = rs.getBytes(columnIndex);
                if (bytes != null) {
                    builder.setBytesData(ByteString.copyFrom(bytes));
                } else {
                    builder.setIsNull(true);
                }
                break;
            case Types.DATE:
            case Types.TIME:
            case Types.TIMESTAMP:
                // Return as string to preserve precision; clients can parse
                builder.setStringData(rs.getString(columnIndex));
                break;
            default:
                builder.setStringData(value.toString());
                break;
        }
        return builder.build();
    }
}
