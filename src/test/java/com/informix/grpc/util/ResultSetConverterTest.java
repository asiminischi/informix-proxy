package com.informix.grpc.util;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.informix.grpc.ColumnMetadata;
import com.informix.grpc.Value;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ResultSetConverterTest {

    @Mock ResultSet rs;
    @Mock ResultSetMetaData meta;

    @Test
    void shouldReturnNullValue() throws Exception {
        when(rs.getObject(1)).thenReturn(null);
        // when(rs.wasNull()).thenReturn(true);

        Value val = ResultSetConverter.convertValue(rs, 1, Types.VARCHAR);
        assertThat(val.getIsNull()).isTrue();
    }

    @Test
    void shouldConvertString() throws Exception {
        when(rs.getObject(1)).thenReturn("abc");
        when(rs.wasNull()).thenReturn(false);
        when(rs.getString(1)).thenReturn("abc");

        Value val = ResultSetConverter.convertValue(rs, 1, Types.VARCHAR);
        assertThat(val.getStringData()).isEqualTo("abc");
    }

    @Test
    void shouldConvertInt() throws Exception {
        when(rs.getObject(1)).thenReturn(10);
        when(rs.wasNull()).thenReturn(false);
        when(rs.getInt(1)).thenReturn(10);

        Value val = ResultSetConverter.convertValue(rs, 1, Types.INTEGER);
        assertThat(val.getIntData()).isEqualTo(10);
    }

    @Test
    void shouldConvertBigInt() throws Exception {
        when(rs.getObject(1)).thenReturn(123456789L);
        when(rs.wasNull()).thenReturn(false);
        when(rs.getLong(1)).thenReturn(123456789L);

        Value val = ResultSetConverter.convertValue(rs, 1, Types.BIGINT);
        assertThat(val.getLongData()).isEqualTo(123456789L);
    }

    @Test
    void shouldConvertDouble() throws Exception {
        when(rs.getObject(1)).thenReturn(2.718);
        when(rs.wasNull()).thenReturn(false);
        when(rs.getDouble(1)).thenReturn(2.718);

        Value val = ResultSetConverter.convertValue(rs, 1, Types.DOUBLE);
        assertThat(val.getDoubleData()).isEqualTo(2.718);
    }

    @Test
    void shouldConvertBoolean() throws Exception {
        when(rs.getObject(1)).thenReturn(true);
        when(rs.wasNull()).thenReturn(false);
        when(rs.getBoolean(1)).thenReturn(true);

        Value val = ResultSetConverter.convertValue(rs, 1, Types.BOOLEAN);
        assertThat(val.getBoolData()).isTrue();
    }

    @Test
    void shouldConvertBytes() throws Exception {
        byte[] data = {1, 2, 3};
        when(rs.getObject(1)).thenReturn(data);
        when(rs.wasNull()).thenReturn(false);
        when(rs.getBytes(1)).thenReturn(data);

        Value val = ResultSetConverter.convertValue(rs, 1, Types.BINARY);
        assertThat(val.getBytesData().toByteArray()).containsExactly(data);
    }

    @Test
    void shouldConvertDateToString() throws Exception {
        when(rs.getObject(1)).thenReturn("2025-01-15");
        when(rs.wasNull()).thenReturn(false);
        when(rs.getString(1)).thenReturn("2025-01-15");

        Value val = ResultSetConverter.convertValue(rs, 1, Types.DATE);
        assertThat(val.getStringData()).isEqualTo("2025-01-15");
    }

    @Test
    void shouldConvertUnknownTypeToString() throws Exception {
        when(rs.getObject(1)).thenReturn(42);
        when(rs.wasNull()).thenReturn(false);

        Value val = ResultSetConverter.convertValue(rs, 1, Types.OTHER);
        assertThat(val.getStringData()).isEqualTo("42");
    }

    @Test
    void shouldExtractColumnMetadata() throws Exception {
        when(meta.getColumnCount()).thenReturn(2);
        when(meta.getColumnName(1)).thenReturn("id");
        when(meta.getColumnTypeName(1)).thenReturn("INTEGER");
        when(meta.getPrecision(1)).thenReturn(9);
        when(meta.getScale(1)).thenReturn(0);
        when(meta.isNullable(1)).thenReturn(ResultSetMetaData.columnNoNulls);
        when(meta.getColumnName(2)).thenReturn("name");
        when(meta.getColumnTypeName(2)).thenReturn("VARCHAR");
        when(meta.getPrecision(2)).thenReturn(100);
        when(meta.getScale(2)).thenReturn(0);
        when(meta.isNullable(2)).thenReturn(ResultSetMetaData.columnNullable);

        List<ColumnMetadata> columns = ResultSetConverter.extractColumnMetadata(meta);
        assertThat(columns).hasSize(2);
        assertThat(columns.get(0).getName()).isEqualTo("id");
        assertThat(columns.get(0).getType()).isEqualTo("INTEGER");
        assertThat(columns.get(0).getNullable()).isFalse();
        assertThat(columns.get(1).getName()).isEqualTo("name");
        assertThat(columns.get(1).getNullable()).isTrue();
    }
}
