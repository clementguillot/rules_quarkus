package com.clementguillot.quarkifier.model.transport;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small dependency-free JSON parser used only for the versioned model transport. */
public final class StrictJson {

  private static final int MAX_NESTING = 128;

  private StrictJson() {}

  public static Object parse(String input) {
    if (input == null) {
      throw error("document must not be null", 0);
    }
    var parser = new Parser(input);
    Object result = parser.parseValue(0);
    parser.skipWhitespace();
    if (!parser.atEnd()) {
      throw parser.error("unexpected trailing content");
    }
    return result;
  }

  private static BazelApplicationModelException error(String message, int offset) {
    return new BazelApplicationModelException("Invalid JSON at offset " + offset + ": " + message);
  }

  @SuppressWarnings({
    "PMD.AvoidDuplicateLiterals",
    "PMD.CyclomaticComplexity",
    "PMD.TooManyMethods"
  })
  private static final class Parser {

    private final String input;
    private int offset;

    private Parser(String input) {
      this.input = input;
    }

    private Object parseValue(int depth) {
      if (depth > MAX_NESTING) {
        throw error("maximum nesting depth exceeded");
      }
      skipWhitespace();
      if (atEnd()) {
        throw error("expected a value");
      }
      return switch (input.charAt(offset)) {
        case '{' -> parseObject(depth + 1);
        case '[' -> parseArray(depth + 1);
        case '"' -> parseString();
        case 't' -> parseLiteral("true", Boolean.TRUE);
        case 'f' -> parseLiteral("false", Boolean.FALSE);
        case 'n' -> parseLiteral("null", null);
        default -> parseNumber();
      };
    }

    private Map<String, Object> parseObject(int depth) {
      offset++;
      skipWhitespace();
      var result = new LinkedHashMap<String, Object>();
      if (consume('}')) {
        return result;
      }
      while (true) {
        skipWhitespace();
        if (atEnd() || input.charAt(offset) != '"') {
          throw error("expected an object member name");
        }
        String name = parseString();
        if (result.containsKey(name)) {
          throw error("duplicate object member '" + name + "'");
        }
        skipWhitespace();
        expect(':');
        result.put(name, parseValue(depth));
        skipWhitespace();
        if (consume('}')) {
          return result;
        }
        expect(',');
      }
    }

    private List<Object> parseArray(int depth) {
      offset++;
      skipWhitespace();
      var result = new ArrayList<>();
      if (consume(']')) {
        return result;
      }
      while (true) {
        result.add(parseValue(depth));
        skipWhitespace();
        if (consume(']')) {
          return result;
        }
        expect(',');
      }
    }

    private String parseString() {
      expect('"');
      var result = new StringBuilder();
      while (!atEnd()) {
        char current = input.charAt(offset++);
        if (current == '"') {
          return result.toString();
        }
        if (current < 0x20) {
          throw error("unescaped control character in string");
        }
        if (current != '\\') {
          result.append(current);
          continue;
        }
        if (atEnd()) {
          throw error("unterminated escape sequence");
        }
        char escaped = input.charAt(offset++);
        switch (escaped) {
          case '"', '\\', '/' -> result.append(escaped);
          case 'b' -> result.append('\b');
          case 'f' -> result.append('\f');
          case 'n' -> result.append('\n');
          case 'r' -> result.append('\r');
          case 't' -> result.append('\t');
          case 'u' -> appendUnicodeEscape(result);
          default -> throw error("invalid escape sequence");
        }
      }
      throw error("unterminated string");
    }

    private void appendUnicodeEscape(StringBuilder result) {
      char first = parseHexCodeUnit();
      if (Character.isLowSurrogate(first)) {
        throw error("unpaired low surrogate");
      }
      if (!Character.isHighSurrogate(first)) {
        result.append(first);
        return;
      }
      if (offset + 2 > input.length()
          || input.charAt(offset) != '\\'
          || input.charAt(offset + 1) != 'u') {
        throw error("unpaired high surrogate");
      }
      offset += 2;
      char second = parseHexCodeUnit();
      if (!Character.isLowSurrogate(second)) {
        throw error("high surrogate must be followed by a low surrogate");
      }
      result.append(first).append(second);
    }

    private char parseHexCodeUnit() {
      if (offset + 4 > input.length()) {
        throw error("incomplete unicode escape");
      }
      int value = 0;
      for (int index = 0; index < 4; index++) {
        int digit = Character.digit(input.charAt(offset++), 16);
        if (digit < 0) {
          throw error("invalid unicode escape");
        }
        value = (value << 4) | digit;
      }
      return (char) value;
    }

    private BigDecimal parseNumber() {
      int start = offset;
      consume('-');
      if (atEnd()) {
        throw error("incomplete number");
      }
      if (consume('0')) {
        if (!atEnd() && Character.isDigit(input.charAt(offset))) {
          throw error("leading zero in number");
        }
      } else {
        requireDigits();
      }
      if (consume('.')) {
        requireDigits();
      }
      if (consume('e') || consume('E')) {
        if (!consume('+')) {
          consume('-');
        }
        requireDigits();
      }
      try {
        return new BigDecimal(input.substring(start, offset));
      } catch (NumberFormatException exception) {
        throw new BazelApplicationModelException(
            "Invalid JSON at offset " + start + ": malformed number", exception);
      }
    }

    private void requireDigits() {
      int start = offset;
      while (!atEnd() && Character.isDigit(input.charAt(offset))) {
        offset++;
      }
      if (start == offset) {
        throw error("expected a digit");
      }
    }

    private Object parseLiteral(String literal, Object value) {
      if (!input.startsWith(literal, offset)) {
        throw error("invalid literal");
      }
      offset += literal.length();
      return value;
    }

    private void expect(char expected) {
      if (!consume(expected)) {
        throw error("expected '" + expected + "'");
      }
    }

    private boolean consume(char expected) {
      if (!atEnd() && input.charAt(offset) == expected) {
        offset++;
        return true;
      }
      return false;
    }

    private void skipWhitespace() {
      while (!atEnd()) {
        char current = input.charAt(offset);
        if (current != ' ' && current != '\n' && current != '\r' && current != '\t') {
          return;
        }
        offset++;
      }
    }

    private boolean atEnd() {
      return offset == input.length();
    }

    private BazelApplicationModelException error(String message) {
      return StrictJson.error(message, offset);
    }
  }
}
