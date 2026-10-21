package dev.localsearch.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;

public final class Json {
    private Json() {}
    public static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    public static String encode(Object value) throws IOException { return MAPPER.writeValueAsString(value); }
}
