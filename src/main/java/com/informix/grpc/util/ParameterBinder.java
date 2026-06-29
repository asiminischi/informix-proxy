package com.informix.grpc.util;

import com.informix.grpc.Parameter;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

/**
 * Binds a list of gRPC Parameter messages to a JDBC PreparedStatement.
 */
public final class ParameterBinder {

    private ParameterBinder() {}

    /**
     * Sets parameters on the statement. Parameters are 1-indexed.
     */
    public static void bind(PreparedStatement pstmt, List<Parameter> parameters) throws SQLException {
        for (int i = 0; i < parameters.size(); i++) {
            Parameter param = parameters.get(i);
            int index = i + 1;

            if (param.getIsNull()) {
                pstmt.setNull(index, Types.NULL);
                continue;
            }

            switch (param.getValueCase()) {
                case STRING_VALUE:
                    pstmt.setString(index, param.getStringValue());
                    break;
                case INT_VALUE:
                    pstmt.setInt(index, param.getIntValue());
                    break;
                case LONG_VALUE:
                    pstmt.setLong(index, param.getLongValue());
                    break;
                case DOUBLE_VALUE:
                    pstmt.setDouble(index, param.getDoubleValue());
                    break;
                case BOOL_VALUE:
                    pstmt.setBoolean(index, param.getBoolValue());
                    break;
                case BYTES_VALUE:
                    pstmt.setBytes(index, param.getBytesValue().toByteArray());
                    break;
                default:
                    // No value provided - treat as NULL
                    pstmt.setNull(index, Types.NULL);
                    break;
            }
        }
    }
}
