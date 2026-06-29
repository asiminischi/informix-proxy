package com.informix.grpc.util;

import static org.mockito.Mockito.*;

import com.informix.grpc.Parameter;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ParameterBinderTest {

    @Mock PreparedStatement pstmt;

    @Test
    void shouldBindStringParameter() throws Exception {
        ParameterBinder.bind(pstmt, Collections.singletonList(
            Parameter.newBuilder().setStringValue("abc").build()));

        verify(pstmt).setString(1, "abc");
    }

    @Test
    void shouldBindNullParameter() throws Exception {
        ParameterBinder.bind(pstmt, Collections.singletonList(
            Parameter.newBuilder().setIsNull(true).build()));

        verify(pstmt).setNull(1, Types.NULL);
    }

    @Test
    void shouldBindIntParameter() throws Exception {
        ParameterBinder.bind(pstmt, Collections.singletonList(
            Parameter.newBuilder().setIntValue(42).build()));

        verify(pstmt).setInt(1, 42);
    }

    @Test
    void shouldBindBoolParameter() throws Exception {
        ParameterBinder.bind(pstmt, Collections.singletonList(
            Parameter.newBuilder().setBoolValue(true).build()));

        verify(pstmt).setBoolean(1, true);
    }

    @Test
    void shouldBindMultipleParameters() throws Exception {
        ParameterBinder.bind(pstmt, java.util.Arrays.asList(
            Parameter.newBuilder().setStringValue("x").build(),
            Parameter.newBuilder().setDoubleValue(3.14).build()));

        verify(pstmt).setString(1, "x");
        verify(pstmt).setDouble(2, 3.14);
    }
}
